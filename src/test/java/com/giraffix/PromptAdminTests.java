package com.giraffix;

import com.giraffix.prompt.PromptTemplates;
import com.giraffix.prompt.PromptTemplates.Kind;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:prompt_admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "giraffix.ai.api-key=test-only","spring.jpa.show-sql=false","giraffix.selection.worker-enabled=false",
        "giraffix.selection.admin-subjects=prompt-admin"})
class PromptAdminTests {
    @Autowired PromptTemplates prompts;
    @Autowired JdbcTemplate db;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    @BeforeEach void setup(){
        for(Kind kind:Kind.values()){
            int active=prompts.get("prompt-admin",kind).version();
            if(active!=1)prompts.publish("prompt-admin",kind,1,active);
        }
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
    }
    MockHttpSession session(String subject){
        var user=mock(OidcUser.class);when(user.getSubject()).thenReturn(subject);
        var roles=List.of(new SimpleGrantedAuthority("ROLE_USER"));doReturn(roles).when(user).getAuthorities();
        var auth=new OAuth2AuthenticationToken(user,roles,"google");
        var session=new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new SecurityContextImpl(auth));
        return session;
    }

    @Test void defaultsAreOriginalTemplatesAndDraftsAreNotActive(){
        assertThat(prompts.list("prompt-admin")).hasSize(4);
        var original=prompts.current(Kind.DIRECTOR);
        assertThat(original.version()).isEqualTo(1);
        assertThat(original.content()).contains("Return ONLY the final image-generation prompt.");
        var draft=prompts.draft("prompt-admin",Kind.DIRECTOR,"Use restrained lighting and preserve the product.",1);
        assertThat(prompts.current(Kind.DIRECTOR)).isEqualTo(original);
        assertThat(prompts.version(Kind.DIRECTOR,draft.version()).content()).contains("restrained lighting");
    }

    @Test void publishAndRollbackAreVersionedAndAudited(){
        var draft=prompts.draft("prompt-admin",Kind.DIRECTOR,"Published creative instructions",1);
        prompts.publish("prompt-admin",Kind.DIRECTOR,draft.version(),1);
        assertThat(prompts.current(Kind.DIRECTOR).version()).isEqualTo(draft.version());
        assertThat(prompts.version(Kind.DIRECTOR,1).content()).contains("Return ONLY");
        assertThatThrownBy(()->prompts.publish("prompt-admin",Kind.DIRECTOR,1,1))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        prompts.publish("prompt-admin",Kind.DIRECTOR,1,draft.version());
        var audit=prompts.publications("prompt-admin",Kind.DIRECTOR,0,20);
        assertThat(audit.get(0).version()).isEqualTo(1);
        assertThat(audit.get(0).previousVersion()).isEqualTo(draft.version());
        assertThat(audit.get(0).publishedBy()).isEqualTo("prompt-admin");
    }

    @Test void invalidAndConflictingEditsNeverChangeActivePrompt(){
        for(String content:List.of(" ","x".repeat(60001),"bad\0text","Unknown {{PLACEHOLDER}}"))
            assertThatThrownBy(()->prompts.draft("prompt-admin",Kind.DIRECTOR,content,1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->prompts.draft("prompt-admin",Kind.PICKER,"Missing threshold",1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->prompts.draft("prompt-admin",Kind.DIRECTOR,"Valid draft",999))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(prompts.current(Kind.DIRECTOR).version()).isEqualTo(1);
        assertThat(prompts.current(Kind.PICKER).version()).isEqualTo(1);
    }

    @Test void cacheReusesImmutableVersionsWithoutDatabaseReads(){
        var mockDb=mock(JdbcTemplate.class);
        var cached=new PromptTemplates(mockDb,mock(org.springframework.transaction.PlatformTransactionManager.class),"prompt-admin");
        var revision=new PromptTemplates.Revision(Kind.DIRECTOR.key,1,"Saved template","system",java.time.Instant.now());
        when(mockDb.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),eq(Kind.DIRECTOR.key),eq(1))).thenReturn(List.of(revision));
        assertThat(cached.version(Kind.DIRECTOR,1)).isEqualTo(revision);
        assertThat(cached.version(Kind.DIRECTOR,1)).isEqualTo(revision);
        assertThat(mockingDetails(mockDb).getInvocations()).hasSize(1);
    }

    @Test void currentPromptsHaveNoTimeBasedReadsAndOnlyRefreshOnceAfterNotification(){
        var mockDb=mock(JdbcTemplate.class);
        var cached=new PromptTemplates(mockDb,mock(org.springframework.transaction.PlatformTransactionManager.class),"prompt-admin");
        var original=new PromptTemplates.Revision(Kind.DIRECTOR.key,1,"Original","system",java.time.Instant.now());
        var updated=new PromptTemplates.Revision(Kind.DIRECTOR.key,2,"Published update","admin",java.time.Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(cached,"active",Map.of(Kind.DIRECTOR,original));
        org.springframework.test.util.ReflectionTestUtils.setField(cached,"stale",false);
        for(int i=0;i<10000;i++)assertThat(cached.current(Kind.DIRECTOR)).isEqualTo(original);
        // Even after old history evicts the revision LRU, the active template remains in memory.
        assertThat(cached.version(Kind.DIRECTOR,1)).isEqualTo(original);
        cached.changed(Kind.DIRECTOR.key,1); // Ignore our own already-applied publication.
        verifyNoInteractions(mockDb);
        when(mockDb.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of(updated));
        cached.changed(Kind.DIRECTOR.key,2);
        cached.changed(Kind.DIRECTOR.key,2); // Multiple hints coalesce into one refresh.
        for(int i=0;i<10000;i++)assertThat(cached.current(Kind.DIRECTOR)).isEqualTo(updated);
        assertThat(mockingDetails(mockDb).getInvocations()).hasSize(1);
        cached.invalidate(); // Reconnect repairs missed notifications.
        assertThat(cached.current(Kind.DIRECTOR)).isEqualTo(updated);
        assertThat(mockingDetails(mockDb).getInvocations()).hasSize(2);
    }

    @Test void editorAndApisRequireAdminAndSession() throws Exception {
        mvc.perform(get("/api/v1/admin/prompts")).andExpect(status().isUnauthorized());
        var normal=session("ordinary-user");
        mvc.perform(get("/admin/prompts").session(normal)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/prompts").session(normal)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/prompts/ad-creative-director/versions/1").session(normal)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/prompts").session(session("prompt-admin"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Save draft")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/v1/admin/prompts").session(session("prompt-admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
    }

    @Test void csrfProtectedDraftPublicationAndHistoryWorkFromUi() throws Exception {
        var admin=session("prompt-admin");
        String base="/api/v1/admin/prompts/ad-creative-director";
        String payload=json.writeValueAsString(Map.of("content","Draft saved through the admin editor","expectedActiveVersion",1));
        mvc.perform(post(base+"/versions").session(admin).contentType("application/json").content(payload)).andExpect(status().isForbidden());
        var csrf=mvc.perform(get("/api/v1/security/csrf").session(admin)).andExpect(status().isOk()).andReturn().getResponse();
        var token=json.readTree(csrf.getContentAsString());
        var saved=mvc.perform(post(base+"/versions").session(admin).cookie(csrf.getCookies())
                        .header(token.path("headerName").asText(),token.path("token").asText()).contentType("application/json").content(payload))
                .andExpect(status().isCreated()).andReturn().getResponse();
        int version=json.readTree(saved.getContentAsString()).path("version").asInt();
        assertThat(prompts.current(Kind.DIRECTOR).version()).isEqualTo(1);
        mvc.perform(post(base+"/publish").session(admin).cookie(csrf.getCookies())
                        .header(token.path("headerName").asText(),token.path("token").asText()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("version",version,"expectedActiveVersion",1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(version));
        mvc.perform(get(base+"/versions?size=1").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(base+"/versions?size=500").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(base+"/publications").session(admin)).andExpect(status().isOk());
        mvc.perform(get(base+"/versions/999999").session(admin)).andExpect(status().isNotFound());
    }
}
