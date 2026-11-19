package com.giraffix;

import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.*;
import com.giraffix.usage.UsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.giraffix.selection.SelectionStore.stamp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:usage_analytics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "giraffix.ai.api-key=test-only","spring.jpa.show-sql=false","giraffix.selection.worker-enabled=false",
        "giraffix.storage.cleanup.worker-enabled=false","giraffix.progress.worker-enabled=false",
        "giraffix.selection.admin-subjects=usage-admin"})
class UsageAnalyticsTests {
    @Autowired UsageService usage;
    @Autowired SelectionService selections;
    @Autowired SelectionStore store;
    @Autowired JdbcTemplate db;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @MockitoBean SelectionAssets assets;
    UUID owner,other,brand,product;
    MockMvc mvc;

    @BeforeEach void setup(){
        db.update("UPDATE selection_requests SET selected_evaluation_id=NULL");
        db.update("UPDATE concept_evaluations SET verified_evaluation_id=NULL");
        for(String table:List.of("usage_ad_calls","usage_operations","product_concept_exclusions","creations","generation_jobs",
                "concept_allocation_claims","concept_allocations","concept_match_cache","concept_evaluations","selection_attempts",
                "selection_seen","selection_batches","selection_requests","concept_revisions","concepts","product_images","products","brands","users"))
            db.update("DELETE FROM "+table);
        owner=user("usage-owner");other=user("usage-other");user("usage-admin");brand=UUID.randomUUID();product=UUID.randomUUID();
        db.update("INSERT INTO brands(id,user_id,name,created_at,updated_at) VALUES(?,?,'Brand',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",brand,owner);
        db.update("INSERT INTO products(id,brand_id,name,category,created_at,updated_at) VALUES(?,?,'Bottle','Drinkware',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",product,brand);
        db.update("INSERT INTO product_images(id,product_id,filename,media_type,position,size_bytes,storage_url,created_at) VALUES(?,?,'front.png','image/png',0,10,'https://owned/photo',CURRENT_TIMESTAMP)",UUID.randomUUID(),product);
        when(assets.product(any(),any(),any())).thenReturn(new Asset("test","products/test/photo","image/png","hash"));
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
    }
    UUID user(String subject){
        UUID id=UUID.randomUUID();db.update("INSERT INTO users(id,google_id,email,full_name,onboarding_completed,created_at) VALUES(?,?,?,?,TRUE,CURRENT_TIMESTAMP)",
                id,subject,subject+"@example.com","Name "+subject);return id;
    }
    MockHttpSession session(String subject){
        var user=mock(OidcUser.class);when(user.getSubject()).thenReturn(subject);
        var auth=new OAuth2AuthenticationToken(user,List.of(new SimpleGrantedAuthority("ROLE_USER")),"google");
        var session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new SecurityContextImpl(auth));return session;
    }
    UUID queued(String key){return selections.startId("usage-owner",key,new Start(product,null,null,null,null));}
    UUID completed(){
        UUID operation=queued("completed"),concept=UUID.randomUUID(),evaluation=UUID.randomUUID(),allocation=UUID.randomUUID(),job=UUID.randomUUID();
        db.update("INSERT INTO concepts(id,image_bucket,image_key,image_url,image_filename,image_media_type,image_size_bytes,usable,analysis_json,schema_version,created_by,created_at) VALUES(?,'test','ref','https://ref','ref.png','image/png',10,TRUE,?,1,'admin',CURRENT_TIMESTAMP)",concept,ConceptFixtures.VALID);
        db.update("INSERT INTO concept_revisions(id,concept_id,revision,analysis_json,status,created_at) VALUES(?,?,1,?,'APPROVED',CURRENT_TIMESTAMP)",concept,concept,ConceptFixtures.VALID);
        db.update("INSERT INTO concept_evaluations(id,request_id,revision_id,stage,result_json,numerator,passes,created_at) VALUES(?,?,?,'SCREEN','{}',340,TRUE,CURRENT_TIMESTAMP)",evaluation,operation,concept);
        db.update("INSERT INTO concept_allocations(id,request_id,owner_id,product_id,concept_id,evaluation_id,state,created_at) VALUES(?,?,?,?,?,?,'CONSUMED',CURRENT_TIMESTAMP)",allocation,operation,owner,product,concept,evaluation);
        db.update("INSERT INTO generation_jobs(id,allocation_id,brief_json,status,spent_micros,deadline,created_at,prompt_text,provider_id,review_started) VALUES(?,?,'{}','SUCCEEDED',200,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'prompt','render-id',TRUE)",job,allocation);
        db.update("UPDATE selection_requests SET status='SELECTED',spent_micros=100 WHERE id=?",operation);
        for(int i=1;i<=2;i++)db.update("INSERT INTO selection_attempts(id,request_id,stage,status,reserved_micros,actual_micros,input_tokens,output_tokens,model_snapshot,created_at) VALUES(?,?,'SCREEN','COMPLETED',100,?,?,?,'select-model',CURRENT_TIMESTAMP)",
                UUID.randomUUID(),operation,i==1?40:60,i*10L,(long)i);
        store.transaction(()->{
            usage.recordAdCall(job,"PROMPT","prompt-model","prompt-id",30L,3L,50);
            usage.recordAdCall(job,"RENDER","image-model","render-id",40L,4L,100);
            usage.recordAdCall(job,"REVIEW","review-model","review-id",50L,5L,50);return null;
        });
        return operation;
    }

    @Test void totalsDoNotMultiplyCostsAcrossSelectionAttemptsAndGenerationStages(){
        UUID id=completed();var totals=usage.summary(null,null,null,null,null,null);
        assertThat(totals.requests()).isEqualTo(1);assertThat(totals.completedRequests()).isEqualTo(1);
        assertThat(totals.accountedCostMicros()).isEqualTo(300);assertThat(totals.totalEstimatedExposureMicros()).isEqualTo(300);
        assertThat(totals.pendingEstimateMicros()).isZero();assertThat(totals.recordedInputTokens()).isEqualTo(150);
        assertThat(totals.recordedOutputTokens()).isEqualTo(15);assertThat(totals.incompleteTokenOperations()).isZero();
        assertThat(usage.calls(id,0,100)).hasSize(5);
        var models=usage.models(null,null,owner,product,brand,UsageService.Kind.AD,0,100);
        assertThat(models).hasSize(4);assertThat(models.stream().mapToLong(UsageService.ModelUsage::recordedInputTokens).sum()).isEqualTo(150);
        assertThat(models.stream().mapToLong(UsageService.ModelUsage::estimatedCostMicros).sum()).isEqualTo(300);
        assertThat(usage.users(null,null,0,100).get(0).email()).isEqualTo("usage-owner@example.com");
        assertThat(usage.daily(null,null,null,null,null,null)).hasSize(1);
    }

    @Test void synchronousAnalysisRecordsRealTokensAndFailedPaidOutput(){
        usage.execute("usage-owner",UsageService.Kind.PRODUCT_ANALYSIS,()->{
            UsageService.providerStarted();UsageService.providerUsage("product-model","provider",100,10);return "ok";
        });
        assertThatThrownBy(()->usage.execute("usage-other",UsageService.Kind.CONCEPT_ANALYSIS,()->{
            UsageService.providerStarted();UsageService.providerUsage("concept-model","provider-2",200,20);
            throw new IllegalStateException("Invalid provider output");
        })).isInstanceOf(IllegalStateException.class);
        var mine=usage.summary(null,null,owner,null,null,UsageService.Kind.PRODUCT_ANALYSIS);
        assertThat(mine.requests()).isEqualTo(1);assertThat(mine.recordedInputTokens()).isEqualTo(100);
        assertThat(mine.accountedCostMicros()).isEqualTo(300);assertThat(mine.completedRequests()).isEqualTo(1);
        var theirs=usage.summary(null,null,other,null,null,UsageService.Kind.CONCEPT_ANALYSIS);
        assertThat(theirs.failedRequests()).isEqualTo(1);assertThat(theirs.accountedCostMicros()).isEqualTo(600);
        assertThat(usage.models(null,null,null,null,null,null,0,100)).hasSize(2);
    }

    @Test void unknownUsageAndPreparationFailuresAreDistinguishedWithoutFabricatedTokens(){
        assertThatThrownBy(()->usage.execute("usage-owner",UsageService.Kind.PRODUCT_ANALYSIS,()->{
            UsageService.providerStarted();throw new IllegalStateException("Timeout");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->usage.execute("usage-owner",UsageService.Kind.PRODUCT_ANALYSIS,()->{throw new IllegalArgumentException("Bad image");}))
                .isInstanceOf(IllegalArgumentException.class);
        var totals=usage.summary(null,null,owner,null,null,null);
        assertThat(totals.requests()).isEqualTo(2);assertThat(totals.uncertainUsageOperations()).isEqualTo(1);assertThat(totals.failedRequests()).isEqualTo(2);
        assertThat(totals.incompleteTokenOperations()).isEqualTo(1);assertThat(totals.recordedInputTokens()).isZero();
        assertThat(totals.totalEstimatedExposureMicros()).isEqualTo(350000);
        // A previous scope's identity cannot leak into a later unrelated call.
        UsageService.providerUsage("unscoped","ignored",999,999);
        assertThat(usage.summary(null,null,owner,null,null,null).recordedInputTokens()).isZero();
    }

    @Test void aSuccessfulResponseWithMissingUsageIsStillSuccessfulButCostIsUncertain(){
        usage.execute("usage-owner",UsageService.Kind.PRODUCT_ANALYSIS,()->{UsageService.providerStarted();return "valid response";});
        var totals=usage.summary(null,null,owner,null,null,null);
        assertThat(totals.completedRequests()).isEqualTo(1);assertThat(totals.failedRequests()).isZero();
        assertThat(totals.uncertainUsageOperations()).isEqualTo(1);assertThat(totals.incompleteTokenOperations()).isEqualTo(1);
    }

    @Test void noUsageLimitsExistAndIdempotentAdReplayIsNotCountedAgain(){
        UUID id=queued("same");assertThat(queued("same")).isEqualTo(id);
        for(int i=0;i<8;i++)queued("unlimited-"+i);
        var totals=usage.summary(null,null,owner,product,brand,UsageService.Kind.AD);
        assertThat(totals.requests()).isEqualTo(9);assertThat(totals.activeRequests()).isEqualTo(9);
        assertThat(totals.totalEstimatedExposureMicros()).isEqualTo(9*4500000L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) IN ('usage_limits','usage_limit_changes')",Integer.class)).isZero();
    }

    @Test void historicalBackfillIsIdempotentAndPreservesOriginalDateAndMissingTokens(){
        UUID id=completed();
        db.update("DELETE FROM usage_ad_calls WHERE operation_id=?",id);db.update("DELETE FROM usage_operations WHERE id=?",id);
        Instant old=Instant.parse("2025-06-02T00:00:00Z");
        db.update("UPDATE selection_requests SET created_at=? WHERE id=?",stamp(old),id);
        db.update("UPDATE selection_attempts SET model_snapshot=NULL WHERE request_id=?",id);
        usage.backfill();usage.backfill();
        assertThat(usage.operation(id).createdAt()).isEqualTo(old);assertThat(usage.operation(id).tokenDataComplete()).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM usage_operations WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM selection_attempts WHERE model_snapshot IS NOT NULL",Integer.class)).isEqualTo(2);
        var past=usage.summary(LocalDate.parse("2025-06-01"),LocalDate.parse("2025-07-01"),owner,null,null,null);
        assertThat(past.accountedCostMicros()).isEqualTo(300);assertThat(past.incompleteTokenOperations()).isEqualTo(1);
        assertThat(usage.summary(null,null,owner,null,null,null).requests()).isZero();
    }

    @Test void usageSurvivesProductAndSavedAdDeletion(){
        UUID id=completed();
        db.update("DELETE FROM product_images WHERE product_id=?",product);db.update("DELETE FROM products WHERE id=?",product);
        db.update("UPDATE generation_jobs SET status='DELETED'");
        var totals=usage.summary(null,null,owner,product,brand,UsageService.Kind.AD);
        assertThat(totals.requests()).isEqualTo(1);assertThat(totals.accountedCostMicros()).isEqualTo(300);
        assertThat(usage.operation(id).brandId()).isEqualTo(brand);
    }

    @Test void adminReportsRequireAllowlistedSessionAndExposeOnlyReadApis() throws Exception {
        UUID id=completed();
        for(String route:List.of("","/users","/operations","/daily","/models","/operations/"+id,"/operations/"+id+"/calls")){
            mvc.perform(get("/api/v1/admin/usage"+route)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/usage"+route).session(session("usage-owner"))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/usage"+route).session(session("usage-admin"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/admin/usage").session(session("usage-admin")))
                .andExpect(jsonPath("$.unit").value("USD_MICROS")).andExpect(jsonPath("$.usage.recordedInputTokens").value(150));
        mvc.perform(get("/api/v1/admin/usage?userId="+other).session(session("usage-admin"))).andExpect(jsonPath("$.usage.requests").value(0));
        for(String query:List.of("size=0","size=101","page=-1","from=2025-01-01&to=2027-01-01","from=2026-01-01&to=2025-01-01","kind=INVALID"))
            mvc.perform(get("/api/v1/admin/usage/operations?"+query).session(session("usage-admin"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/usage/operations/"+UUID.randomUUID()).session(session("usage-admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/admin/usage").session(session("usage-owner"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/usage").session(session("usage-admin"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Usage analytics")));
        var docs=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var paths=json.readTree(docs).path("paths");
        for(String path:paths.propertyNames())if(path.startsWith("/api/v1/admin/usage")){
            assertThat(path).doesNotContain("limits");assertThat(paths.path(path).has("put")).isFalse();
            assertThat(paths.path(path).has("post")).isFalse();assertThat(paths.path(path).has("delete")).isFalse();
        }
    }
}
