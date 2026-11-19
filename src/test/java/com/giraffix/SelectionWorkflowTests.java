package com.giraffix;

import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.*;
import com.giraffix.concept.service.ConceptLibrary;
import com.giraffix.generation.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.giraffix.selection.SelectionStore.stamp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"giraffix.selection.worker-enabled=false","giraffix.selection.admin-subjects=reviewer","giraffix.selection.picker-enabled=false",
        "spring.jpa.show-sql=false","giraffix.ai.api-key=test-only","spring.flyway.baseline-on-migrate=false","giraffix.api.legacy-selection-enabled=true"})
class SelectionWorkflowTests {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        boolean pg=System.getProperty("selection.test.jdbc")!=null;
        r.add("spring.datasource.url",()->System.getProperty("selection.test.jdbc","jdbc:h2:mem:selection_checks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        r.add("spring.datasource.username",()->pg?"selection_test":"sa");r.add("spring.datasource.password",()->"");
        r.add("spring.datasource.driver-class-name",()->pg?"org.postgresql.Driver":"org.h2.Driver");
    }
    @Autowired SelectionService service;
    @Autowired SelectionWorker worker;
    @Autowired SelectionStore store;
    @Autowired SelectionPolicy policy;
    @Autowired ConceptLibrary library;
    @Autowired GenerationWorker generation;
    @Autowired SelectionContextService contexts;
    @Autowired JdbcTemplate db;
    @MockitoBean ConceptEvaluator evaluator;
    @MockitoBean SelectionAssets assets;
    @MockitoBean ImageGenerator generator;
    @MockitoBean OutputReviewer reviewer;
    @MockitoBean GeneratedAssetStorage outputs;
    @MockitoBean AdPromptWriter promptWriter;
    UUID owner,product,brand,concept;

    @BeforeEach void setup() throws Exception {
        db.update("UPDATE selection_requests SET selected_evaluation_id=NULL");
        db.update("UPDATE concept_evaluations SET verified_evaluation_id=NULL");
        for(String table:List.of("usage_ad_calls","usage_operations","product_concept_exclusions","creations","outbox_events","generation_jobs","concept_allocation_claims","concept_allocations","concept_match_cache",
                "concept_evaluations","selection_attempts","selection_seen","selection_batches","selection_requests","concept_revisions","concepts","product_images","products","brands","users"))db.update("DELETE FROM "+table);
        owner=UUID.randomUUID();brand=UUID.randomUUID();product=UUID.randomUUID();
        db.update("INSERT INTO users(id,google_id,email,onboarding_completed,created_at) VALUES(?,'selection-owner','owner@example.com',TRUE,?)",owner,stamp(Instant.now()));
        db.update("INSERT INTO brands(id,user_id,name,created_at,updated_at) VALUES(?,?,'Brand',?,?)",brand,owner,stamp(Instant.now()),stamp(Instant.now()));
        db.update("INSERT INTO products(id,brand_id,name,category,created_at,updated_at) VALUES(?,?,'Bottle','Drinkware',?,?)",product,brand,stamp(Instant.now()),stamp(Instant.now()));
        db.update("INSERT INTO product_images(id,product_id,filename,media_type,position,size_bytes,storage_url,created_at) VALUES(?,?,'front.png','image/png',0,100,'https://owned/image',?)",UUID.randomUUID(),product,stamp(Instant.now()));
        when(assets.product(any(),any(),any())).thenReturn(new Asset("test","products/test/front.png","image/png","product-hash"));
        when(assets.fingerprint(any())).thenAnswer(call->{Asset a=call.getArgument(0);return new Asset(a.bucket(),a.key(),a.mediaType(),"reference-hash");});
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{
            List<Candidate> cards=call.getArgument(1);Evaluation exact=call.getArgument(2);
            return new Evaluated(cards.stream().map(c->value(c,exact==null?"An unchanged bottle floats within a frame.":exact.adaptation(),4,false)).toList(),new Usage(100,50,"test-provider"));
        });
        when(generator.available()).thenReturn(true);
        when(generator.generate(any(),any(),any())).thenReturn(new ImageGenerator.Result(ProductImageProcessorTests.fixture("png"),"image/png","operation-test",100000));
        when(outputs.put(any(),any(),any())).thenReturn(new Asset("test","creations/test/image","image/png","output-hash"));
        when(reviewer.review(any(),any())).thenReturn(new OutputReviewer.Review(true,"Identity and relationship preserved",10000));
        when(promptWriter.write(any(),anyLong(),any(),anyInt())).thenReturn(new AdPromptWriter.Result(
                "Render the unchanged product in the selected frame concept, exactly 1:1.","prompt-model","creative-director-v2","prompt-operation",10000));
        policy.batchSize=10;policy.maxRounds=3;policy.maxVerificationCalls=3;policy.maxRetries=1;policy.deadlineSeconds=90;
        policy.maxCostMicros=2500000;policy.maxAttemptCostMicros=350000;
        policy.pickerEnabled=false;policy.threshold=80;
        concept=seed(true);
    }
    UUID seed(boolean approved){
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO concepts(id,image_bucket,image_key,image_url,image_filename,image_media_type,image_size_bytes,
                usable,analysis_json,schema_version,created_by,created_at) VALUES(?,'test',?,'https://private/reference','reference.png','image/png',100,TRUE,?,1,'creator',?)
            """,id,"concept/"+id+"/reference.png",ConceptFixtures.VALID,stamp(Instant.now()));
        db.update("INSERT INTO concept_revisions(id,concept_id,revision,analysis_json,status,created_at) VALUES(?,?,1,?,?,?)",
                id,id,ConceptFixtures.VALID,approved?"APPROVED":"DRAFT",stamp(Instant.now()));return id;
    }
    Evaluation value(Candidate c,String adaptation,int rating,boolean mismatch){
        return new Evaluation(c.conceptId(),c.revision(),adaptation,new Ratings(rating,rating,rating,rating,rating),List.of(),List.of(),
                ScorePolicy.DIMENSIONS.stream().map(d->new Reason(d,"SUPPORTED",List.of("/concept/preserve/0"))).toList(),mismatch);
    }
    Start input(UUID manual){return new Start(product,"Show effortless freedom","square",null,manual);}
    Status start(UUID manual){return service.start("selection-owner",UUID.randomUUID().toString(),input(manual));}
    Status run(UUID manual){var request=start(manual);worker.run(request.id());return service.status("selection-owner",request.id());}
    long count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}

    void enablePicker(int poolSize) {
        policy.pickerEnabled=true;policy.batchSize=5;
        for(int i=1;i<poolSize;i++)seed(true);
    }
    Evaluation picked(Candidate c,int score,String classification) {
        return new Evaluation(c.conceptId(),c.revision(),"Place the recognizable bottle inside a meaningful suspended frame.",
                null,List.of(),List.of(),List.of(),false,score,classification);
    }
    Evaluated picks(List<Candidate> cards,int score,String classification,int threshold) {
        return new Evaluated(cards.stream().map(c->picked(c,score,classification)).toList(),new Usage(100,50,"picker-test"),
                score<threshold?cards.stream().map(Candidate::conceptId).toList():List.of());
    }

    @Test void pickerAcceptsOnlyProductIdAndLoadsSavedContextWithIdempotentDefaults() {
        enablePicker(5);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{Context context=call.getArgument(0);
            assertThat(context.intent()).isEqualTo(Start.DEFAULT_INTENT);assertThat(context.format()).isEqualTo(Start.DEFAULT_FORMAT);
            assertThat(context.images()).hasSize(1);
            assertThat(store.json.mapper.readTree(context.factsJson()).path("name").asText()).isEqualTo("Bottle");
            assertThat(store.json.mapper.readTree(context.factsJson()).path("brandName").asText()).isEqualTo("Brand");
            return picks(call.getArgument(1),85,"STRONG",80);});
        var request=service.start("selection-owner","product-only",new Start(product,null,null,null,null));
        worker.run(request.id());assertThat(service.status("selection-owner",request.id()).status()).isEqualTo("SELECTED");
        var replay=service.start("selection-owner","product-only",new Start(product,Start.DEFAULT_INTENT,Start.DEFAULT_FORMAT,null,null));
        assertThat(replay.id()).isEqualTo(request.id());verify(evaluator,times(1)).evaluate(any(),anyList(),any(),any());
    }

    Status selectedAd() {
        enablePicker(1);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call -> picks(call.getArgument(1),85,"STRONG",80));
        var request=service.start("selection-owner",UUID.randomUUID().toString(),new Start(product,null,"Square ad",null,null,"1:1"));
        worker.run(request.id());
        return service.status("selection-owner",request.id());
    }

    @Test void preRatioIdempotencyAndContextSnapshotsKeepTheirOriginalHashes() {
        var input=input(null);
        String legacyInput="{\"productId\":\""+product+"\",\"intent\":\"Show effortless freedom\",\"format\":\"square\",\"controls\":null,\"conceptId\":null}";
        var first=service.start("selection-owner","legacy-replay",input);
        db.update("UPDATE selection_requests SET body_hash=? WHERE id=?",JsonSupport.digest(legacyInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)),first.id());
        assertThat(service.start("selection-owner","legacy-replay",input).id()).isEqualTo(first.id());
        var context=store.get(first.id()).context();
        var oldContext=store.json.mapper.createObjectNode();
        oldContext.put("ownerId",context.ownerId().toString());oldContext.put("productId",context.productId().toString());
        oldContext.put("factsJson",context.factsJson());oldContext.put("intent",context.intent());oldContext.put("format",context.format());
        oldContext.putNull("controls");oldContext.set("images",store.json.mapper.valueToTree(context.images()));
        assertThat(store.json.hash(context)).isEqualTo(store.json.hash(oldContext));
    }

    @Test void adFlowPersistsPromptBeforeRenderingAndPublishesOneImageWithRatio() throws Exception {
        var selected=selectedAd();
        when(generator.generate(any(),any(),any())).thenAnswer(call -> {
            Brief brief=call.getArgument(1);
            assertThat(brief.context().aspectRatio()).isEqualTo("1:1");
            assertThat(brief.imagePrompt()).contains("unchanged product");
            assertThat(db.queryForObject("SELECT prompt_text FROM generation_jobs WHERE id=?",String.class,selected.generationJobId())).isEqualTo(brief.imagePrompt());
            return new ImageGenerator.Result(ProductImageProcessorTests.fixture("png"),"image/png","image-operation",100000);
        });
        generation.run(selected.generationJobId());generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT aspect_ratio FROM creations",String.class)).isEqualTo("1:1");
        assertThat(db.queryForObject("SELECT image_prompt FROM creations",String.class)).contains("unchanged product");
        assertThat(db.queryForObject("SELECT spent_micros FROM generation_jobs",Long.class)).isEqualTo(120000);
        verify(promptWriter,times(1)).write(any(),anyLong(),any(),anyInt());
        verify(generator,times(1)).generate(any(),any(),any());
    }

    @Test void completedPromptRecoveryDoesNotWriteOrChargePromptAgain() throws Exception {
        var selected=selectedAd();
        db.update("UPDATE generation_jobs SET status='PROMPT_READY',prompt_text='Saved prompt',prompt_version='creative-director-v2',prompt_cost_micros=15000 WHERE id=?",selected.generationJobId());
        when(generator.generate(any(),any(),any())).thenReturn(new ImageGenerator.Result(ProductImageProcessorTests.fixture("png"),"image/png","image-operation",100000));
        generation.run(selected.generationJobId());
        verifyNoInteractions(promptWriter);
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT image_prompt FROM creations",String.class)).isEqualTo("Saved prompt");
        assertThat(db.queryForObject("SELECT spent_micros FROM generation_jobs",Long.class)).isEqualTo(125000);
    }

    @Test void unknownPromptOutcomeRequiresReconciliationWithoutRendering() {
        var selected=selectedAd();
        when(promptWriter.write(any(),anyLong(),any(),anyInt())).thenThrow(new RuntimeException("Unknown prompt transport outcome"));
        generation.run(selected.generationJobId());generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        verify(promptWriter,times(1)).write(any(),anyLong(),any(),anyInt());
        verify(generator,never()).generate(any(),any(),any());
    }

    @Test void completedReviewResumesPublicationWithoutAnyPaidCall() {
        var selected=selectedAd();
        String output=store.json.write(new Asset("test","creations/saved","image/png","saved-hash"));
        String review=store.json.write(new OutputReviewer.Review(true,"Already reviewed",10000));
        db.update("UPDATE generation_jobs SET status='REVIEWING',output_json=?,review_started=TRUE,review_json=?,prompt_text='Saved prompt',spent_micros=120000 WHERE id=?",
                output,review,selected.generationJobId());
        generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT spent_micros FROM generation_jobs",Long.class)).isEqualTo(120000);
        verifyNoInteractions(promptWriter,generator,reviewer,outputs);
    }

    @Test void transientPublicationFailureReusesDurableReviewAndChargesOnce() {
        var selected=selectedAd();
        var contextSpy=spy(contexts);
        var failed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            if(db.queryForObject("SELECT review_json FROM generation_jobs WHERE id=?",String.class,selected.generationJobId())!=null
                    && failed.compareAndSet(false,true))throw new org.springframework.dao.CannotAcquireLockException("Simulated publication lock failure");
            return call.callRealMethod();
        }).when(contextSpy).current(any());
        var pipeline=new GenerationWorker(store,contextSpy,generator,outputs,reviewer,promptWriter,assets);
        try{
            pipeline.run(selected.generationJobId());
            assertThat(failed).isTrue();
            assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("REVIEWING");
            assertThat(count("creations")).isZero();
            assertThat(generation.pending(10)).doesNotContain(selected.generationJobId());
            clearInvocations(promptWriter,generator,reviewer,outputs);
            db.update("UPDATE generation_jobs SET next_attempt_at=? WHERE id=?",stamp(Instant.now().minusSeconds(1)),selected.generationJobId());
            pipeline.run(selected.generationJobId());
            assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("SUCCEEDED");
            assertThat(count("creations")).isEqualTo(1);
            assertThat(db.queryForObject("SELECT spent_micros FROM generation_jobs",Long.class)).isEqualTo(120000);
            verifyNoInteractions(promptWriter,generator,reviewer,outputs);
        }finally{pipeline.close();}
    }

    @Test void interruptedReviewWithoutSavedResultIsNeverRepeated() {
        var selected=selectedAd();
        db.update("UPDATE generation_jobs SET status='REVIEWING',review_started=TRUE WHERE id=?",selected.generationJobId());
        generation.run(selected.generationJobId());
        generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        verifyNoInteractions(promptWriter,generator,reviewer,outputs);
    }

    @Test void providerWaitBackoffDoesNotKeepClaimingTheSameJob() {
        var selected=selectedAd();
        when(generator.available()).thenReturn(false);
        generation.run(selected.generationJobId());
        generation.run(selected.generationJobId());
        assertThat(generation.pending(10)).doesNotContain(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("WAITING_FOR_PROVIDER");
        verify(generator,times(1)).available();
        verifyNoInteractions(promptWriter,reviewer,outputs);
    }

    @Test void expiredDeadlineDuringReviewCannotPublishOrRepeatReview() {
        var selected=selectedAd();
        when(reviewer.review(any(),any())).thenAnswer(call->{
            db.update("UPDATE generation_jobs SET deadline=? WHERE id=?",stamp(Instant.now().minusSeconds(1)),selected.generationJobId());
            return new OutputReviewer.Review(true,"Reviewed",10000);
        });
        generation.run(selected.generationJobId());
        generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("FAILED");
        assertThat(count("creations")).isZero();
        assertThat(count("concept_allocation_claims")).isZero();
        verify(reviewer,times(1)).review(any(),any());
    }

    @Test void providerStagesExecuteOutsideDatabaseTransactions() throws Exception {
        var selected=selectedAd();
        when(promptWriter.write(any(),anyLong(),any(),anyInt())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new AdPromptWriter.Result("Render the unchanged product exactly 1:1.","model","v2","prompt-op",10000);
        });
        when(generator.generate(any(),any(),any())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ImageGenerator.Result(ProductImageProcessorTests.fixture("png"),"image/png","image-op",100000);
        });
        when(reviewer.review(any(),any())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new OutputReviewer.Review(true,"Reviewed",10000);
        });
        generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("SUCCEEDED");
    }

    @Test void unknownSelectionTransportOutcomeKeepsReservationAndDoesNotRetry() {
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenThrow(new org.springframework.web.client.ResourceAccessException("Unknown provider timeout"));
        assertThat(run(null).status()).isEqualTo("PROVIDER_FAILED");
        verify(evaluator,times(1)).evaluate(any(),anyList(),any(),any());
        assertThat(count("selection_attempts")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT spent_micros FROM selection_requests",Long.class)).isEqualTo(350000);
    }

    @Test void crashedPromptCallIsNotRedispatched() {
        var selected=selectedAd();
        db.update("UPDATE generation_jobs SET status='PROMPTING',lease_until=NULL WHERE id=?",selected.generationJobId());
        generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        verifyNoInteractions(promptWriter);
        verify(generator,never()).generate(any(),any(),any());
    }

    @Test void brandEditDuringRenderingCannotPublishStaleImage() throws Exception {
        var selected=selectedAd();
        when(generator.generate(any(),any(),any())).thenAnswer(call -> {
            db.update("UPDATE brands SET description='Changed brand' WHERE id=?",brand);
            return new ImageGenerator.Result(ProductImageProcessorTests.fixture("png"),"image/png","image-operation",100000);
        });
        generation.run(selected.generationJobId());
        assertThat(count("creations")).isZero();
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("STALE_CONTEXT");
        verifyNoInteractions(reviewer);
    }

    @Test void expiredDeadlineAfterRenderingDoesNotReviewOrPublish() throws Exception {
        var selected=selectedAd();
        when(generator.generate(any(),any(),any())).thenAnswer(call -> {
            db.update("UPDATE generation_jobs SET deadline=? WHERE id=?",stamp(Instant.now().minusSeconds(1)),selected.generationJobId());
            return new ImageGenerator.Result(ProductImageProcessorTests.fixture("png"),"image/png","image-operation",100000);
        });
        generation.run(selected.generationJobId());
        assertThat(count("creations")).isZero();
        verifyNoInteractions(reviewer);
    }

    @Test void wrongOutputRatioIsRejectedWithoutCroppingOrPublication() throws Exception {
        enablePicker(1);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call -> picks(call.getArgument(1),85,"STRONG",80));
        var request=service.start("selection-owner",UUID.randomUUID().toString(),new Start(product,null,"Portrait ad",null,null,"4:5"));
        worker.run(request.id());
        var selected=service.status("selection-owner",request.id());
        when(generator.generate(any(),any(),any())).thenReturn(new ImageGenerator.Result(ProductImageProcessorTests.fixture("png"),"image/png","image-operation",100000));
        generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("OUTPUT_REJECTED");
        assertThat(count("creations")).isZero();
        verifyNoInteractions(reviewer);
        verify(outputs,never()).put(any(),any(),any());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void pickerLogsAllScoresExclusionsAndWinnerWithoutPrivateContent(org.springframework.boot.test.system.CapturedOutput output) {
        enablePicker(5);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);
            var values=new ArrayList<Evaluation>();
            for(int i=0;i<cards.size();i++) {
                int score=i==0?91:i==1?40:i==2?70:85;
                String classification=i==0?"STRONG":i==1?"NOT_SUITABLE":"CONDITIONAL";
                var card=cards.get(i);
                values.add(new Evaluation(card.conceptId(),card.revision(),"PRIVATE_ADAPTATION_MARKER",null,
                        List.of(),List.of(),List.of(),false,score,classification));
            }
            return new Evaluated(values,new Usage(100,50,"picker"),List.of(cards.get(1).conceptId(),cards.get(2).conceptId()));});
        var result=run(null);
        var completed=output.getOut().lines().filter(line->line.contains("stage=concept_picker_batch_completed")
                && line.contains("requestId="+result.id())).findFirst().orElseThrow();
        assertThat(completed).contains("productId="+product,"round=1","minimumScore=80","notSuitableConceptIds=", "classifiedNotSuitableConceptIds=");
        var scores=store.json.mapper.readTree(completed.substring(completed.indexOf("evaluations=")+"evaluations=".length(),completed.indexOf(" notSuitableConceptIds=")));
        assertThat(scores.size()).isEqualTo(5);
        assertThat(scores.get(0).path("compatibilityScore").asInt()).isEqualTo(91);
        assertThat(scores.get(1).path("classification").asText()).isEqualTo("NOT_SUITABLE");
        for(var score:scores)assertThat(score.propertyNames()).containsExactlyInAnyOrder("conceptId","compatibilityScore","classification");
        var excluded=store.json.mapper.readTree(completed.substring(completed.indexOf(" notSuitableConceptIds=")+" notSuitableConceptIds=".length(),
                completed.indexOf(" classifiedNotSuitableConceptIds=")));
        assertThat(excluded.size()).isEqualTo(2);
        assertThat(excluded.get(0).asText()).isEqualTo(scores.get(1).path("conceptId").asText());
        assertThat(excluded.get(1).asText()).isEqualTo(scores.get(2).path("conceptId").asText());
        assertThat(output.getOut()).contains("stage=concept_picker_batch_started", "stage=concept_picker_selected requestId="+result.id(),
                "conceptId="+result.selected().conceptId(),"compatibilityScore=91 classification=STRONG fallbackUsed=false",
                "stage=concept_picker_finished requestId="+result.id()).doesNotContain("PRIVATE_ADAPTATION_MARKER");
    }

    @Test void pickerStopsAfterFirstQualifyingBatchAndFeedsGeneration() {
        enablePicker(15);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->picks(call.getArgument(1),85,"STRONG",80));
        var result=run(null);
        assertThat(result.status()).isEqualTo("SELECTED");assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.selected().compatibilityScore()).isEqualTo(85);assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.verificationCalls()).isZero();assertThat(count("concept_evaluations")).isEqualTo(5);
        assertThat(count("product_concept_exclusions")).isZero();
        generation.run(result.generationJobId());
        assertThat(service.status("selection-owner",result.id()).generationStatus()).isEqualTo("SUCCEEDED");
        verify(evaluator,times(1)).evaluate(any(),anyList(),isNull(),any());
        var brief=store.json.read(db.queryForObject("SELECT brief_json FROM generation_jobs WHERE id=?",String.class,result.generationJobId()),Brief.class);
        assertThat(brief.evaluation().compatibilityScore()).isEqualTo(85);
    }
    @Test void pickerDrawsFreshSecondBatchAndPersistsBelowThresholdExclusions() {
        enablePicker(15);var calls=new java.util.concurrent.atomic.AtomicInteger();Set<UUID> seen=new HashSet<>();
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);
            for(var card:cards)assertThat(seen.add(card.conceptId())).isTrue();
            return calls.incrementAndGet()==1?picks(cards,75,"CONDITIONAL",80):picks(cards,90,"STRONG",80);});
        var result=run(null);assertThat(result.rounds()).isEqualTo(2);assertThat(result.selected().compatibilityScore()).isEqualTo(90);
        assertThat(count("product_concept_exclusions")).isEqualTo(5);assertThat(seen).hasSize(10);
        assertThat(db.queryForList("SELECT product_id FROM product_concept_exclusions",UUID.class)).containsOnly(product);
    }
    @Test void pickerFallsBackToBestAcrossAllThreeRoundsAndSkipsSavedPairsNextRequest() {
        enablePicker(15);var calls=new java.util.concurrent.atomic.AtomicInteger();Set<UUID> seen=new HashSet<>();
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);
            for(var card:cards)assertThat(seen.add(card.conceptId())).isTrue();
            return picks(cards,80-calls.incrementAndGet()*10,"CONDITIONAL",80);});
        var result=run(null);assertThat(result.status()).isEqualTo("SELECTED");assertThat(result.rounds()).isEqualTo(3);
        assertThat(result.fallbackUsed()).isTrue();assertThat(result.selected().compatibilityScore()).isEqualTo(70);
        assertThat(count("product_concept_exclusions")).isEqualTo(15);assertThat(count("selection_seen")).isEqualTo(15);
        clearInvocations(evaluator);var next=run(null);assertThat(next.selected()).isNull();verifyNoInteractions(evaluator);
    }
    @Test void pickerConditionalAboveThresholdDoesNotQualifyOrBecomeExcluded() {
        enablePicker(15);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->picks(call.getArgument(1),99,"CONDITIONAL",80));
        var result=run(null);assertThat(result.rounds()).isEqualTo(3);assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.selected().classification()).isEqualTo("CONDITIONAL");assertThat(count("product_concept_exclusions")).isZero();
    }
    @Test void pickerFallbackUsesHighestScoreEvenWhenClassifiedNotSuitable() {
        enablePicker(5);policy.maxRounds=1;
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);
            var values=new ArrayList<>(cards.stream().map(c->picked(c,70,"CONDITIONAL")).toList());
            values.set(0,picked(cards.get(0),75,"NOT_SUITABLE"));
            return new Evaluated(values,new Usage(100,50,"picker"),cards.stream().map(Candidate::conceptId).toList());});
        var result=run(null);assertThat(result.fallbackUsed()).isTrue();assertThat(result.selected().compatibilityScore()).isEqualTo(75);
        assertThat(result.selected().classification()).isEqualTo("NOT_SUITABLE");
    }
    @Test void pickerAcceptsExactThresholdAndUsesConfiguredBatchAndThreshold() {
        enablePicker(5);policy.batchSize=2;policy.threshold=90;
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);
            assertThat(cards).hasSize(2);return picks(cards,90,"STRONG",90);});
        var result=run(null);assertThat(result.fallbackUsed()).isFalse();assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.selected().compatibilityScore()).isEqualTo(90);assertThat(count("product_concept_exclusions")).isZero();
    }
    @Test void pickerSmallPoolUsesAvailableConceptsAndFallsBackWhenExhausted() {
        enablePicker(2);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->picks(call.getArgument(1),60,"CONDITIONAL",80));
        var result=run(null);assertThat(result.rounds()).isEqualTo(1);assertThat(result.fallbackUsed()).isTrue();
        assertThat(count("concept_evaluations")).isEqualTo(2);
    }
    @Test void pickerExclusionsAreScopedToProductAndSurviveProductEdits() {
        enablePicker(1);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->picks(call.getArgument(1),60,"CONDITIONAL",80));
        var first=run(null);assertThat(count("product_concept_exclusions")).isEqualTo(1);
        db.update("UPDATE products SET description='Edited product' WHERE id=?",product);
        var again=run(null);assertThat(again.selected()).isNull();
        UUID other=UUID.randomUUID();
        db.update("INSERT INTO products(id,brand_id,name,category,created_at,updated_at) VALUES(?,?,'Other bottle','Drinkware',?,?)",other,brand,stamp(Instant.now()),stamp(Instant.now()));
        db.update("INSERT INTO product_images(id,product_id,filename,media_type,position,size_bytes,storage_url,created_at) VALUES(?,?,'front.png','image/png',0,100,'https://owned/other',?)",UUID.randomUUID(),other,stamp(Instant.now()));
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->picks(call.getArgument(1),90,"STRONG",80));
        var request=service.start("selection-owner",UUID.randomUUID().toString(),new Start(other,"Launch","square",null,null));
        worker.run(request.id());assertThat(service.status("selection-owner",request.id()).selected().conceptId()).isEqualTo(first.selected().conceptId());
    }
    @Test void pickerRejectsInvalidResponseWithoutSavingEvaluationsOrExclusions() {
        enablePicker(5);policy.maxRetries=0;
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->new Evaluated(List.of(),new Usage(100,50,"picker"),List.of()));
        assertThat(run(null).status()).isEqualTo("PROVIDER_FAILED");assertThat(count("concept_evaluations")).isZero();
        assertThat(count("product_concept_exclusions")).isZero();assertThat(count("generation_jobs")).isZero();
    }
    @Test void pickerContextChangeDoesNotPersistExclusionsOrSelectStaleWinner() {
        enablePicker(5);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{db.update("UPDATE products SET description='Changed during picking' WHERE id=?",product);
            return picks(call.getArgument(1),60,"CONDITIONAL",80);});
        assertThat(run(null).status()).isEqualTo("STALE_CONTEXT");assertThat(count("product_concept_exclusions")).isZero();
        assertThat(count("concept_evaluations")).isZero();
    }
    @Test void pickerUsesSnapshotWhenRuntimePropertiesChangeAndIdempotencyAvoidsReevaluation() {
        enablePicker(15);var request=start(null);
        policy.batchSize=1;policy.threshold=1;policy.maxRounds=1;
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);
            assertThat(cards).hasSize(5);return picks(cards,70,"STRONG",80);});
        worker.run(request.id());var result=service.status("selection-owner",request.id());
        assertThat(result.rounds()).isEqualTo(3);assertThat(result.fallbackUsed()).isTrue();
        clearInvocations(evaluator);worker.run(request.id());verifyNoInteractions(evaluator);
    }
    @Test void pickerCompletedResponsesRecoverWithoutAnotherProviderCall() {
        enablePicker(5);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->picks(call.getArgument(1),85,"STRONG",80));
        var result=run(null);
        for(String table:List.of("outbox_events","generation_jobs","concept_allocation_claims","concept_allocations"))db.update("DELETE FROM "+table);
        db.update("UPDATE selection_requests SET selected_evaluation_id=NULL,status='QUEUED',lease_until=NULL WHERE id=?",result.id());
        db.update("DELETE FROM concept_evaluations");db.update("UPDATE selection_batches SET completed=FALSE");clearInvocations(evaluator);
        worker.run(result.id());assertThat(service.status("selection-owner",result.id()).status()).isEqualTo("SELECTED");
        assertThat(count("concept_evaluations")).isEqualTo(5);verifyNoInteractions(evaluator);
    }

    @Test void pickerConcurrentRequestsShareExclusionsWithoutDuplicateAllocations() throws Exception {
        enablePicker(5);var barrier=new CyclicBarrier(2);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{barrier.await(5,TimeUnit.SECONDS);
            return picks(call.getArgument(1),70,"CONDITIONAL",80);});
        var first=start(null);var second=start(null);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->worker.run(first.id()));var b=pool.submit(()->worker.run(second.id()));
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(service.status("selection-owner",first.id()).status()).isEqualTo("SELECTED");
        assertThat(service.status("selection-owner",second.id()).status()).isEqualTo("SELECTED");
        assertThat(count("product_concept_exclusions")).isEqualTo(5);assertThat(count("concept_allocations")).isEqualTo(2);
        assertThat(db.queryForList("SELECT concept_id FROM concept_allocations",UUID.class)).doesNotHaveDuplicates();
    }

    @Test void selectsVerifiesAndCreatesExactlyOnce(){
        var selected=run(null);assertThat(selected.status()).isEqualTo("SELECTED");
        assertThat(selected.rounds()).isEqualTo(1);assertThat(selected.verificationCalls()).isEqualTo(1);
        assertThat(count("selection_attempts")).isEqualTo(2);assertThat(count("concept_evaluations")).isEqualTo(2);
        assertThat(count("outbox_events")).isZero();assertThat(count("concept_allocations")).isEqualTo(1);
        worker.run(selected.id());generation.run(selected.generationJobId());generation.run(selected.generationJobId());
        assertThat(count("creations")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT state FROM concept_allocations",String.class)).isEqualTo("CONSUMED");
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("SUCCEEDED");
        verify(generator,times(1)).generate(any(),any(),any());
        assertThat(run(concept).status()).isEqualTo("POOL_EXHAUSTED");
    }
    @Test void manualPathOnlyEvaluatesRequestedConceptAndDoesNotSubstitute(){
        seed(true);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> c=call.getArgument(1);
            assertThat(c).hasSize(1);assertThat(c.get(0).conceptId()).isEqualTo(concept);
            return new Evaluated(List.of(value(c.get(0),"Execution",2,false)),new Usage(100,50,"provider"));});
        assertThat(run(concept).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");assertThat(count("generation_jobs")).isZero();
        verify(evaluator,times(1)).evaluate(any(),anyList(),any(),any());
    }
    @Test void idempotencyRejectsDifferentBodyAndPreservesOriginalRequest(){
        var first=service.start("selection-owner","same",input(null));
        assertThat(service.start("selection-owner","same",input(null)).id()).isEqualTo(first.id());
        assertThatThrownBy(()->service.start("selection-owner","same",new Start(product,"different","square",null,null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(count("selection_requests")).isEqualTo(1);
    }
    @Test void jsonProductsRemainValidButSelectionRequiresOwnedImages(){
        db.update("DELETE FROM product_images WHERE product_id=?",product);
        assertThatThrownBy(()->start(null)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(count("products")).isEqualTo(1);verifyNoInteractions(evaluator);
    }
    @Test void draftsAreExcludedAndOnlyAdminCanApprove(){
        db.update("UPDATE concept_revisions SET status='DRAFT'");
        assertThat(run(null).status()).isEqualTo("POOL_EXHAUSTED");
        assertThatThrownBy(()->library.review("selection-owner",concept,concept,true)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));
        library.review("reviewer",concept,concept,true);assertThat(run(null).status()).isEqualTo("SELECTED");
    }
    @Test void revisionEditsAreImmutableAndNeedFreshApproval(){
        var revision=library.revise("reviewer",concept,store.json.mapper.readTree(ConceptFixtures.VALID));
        assertThat(revision.revision()).isEqualTo(2);assertThat(revision.status()).isEqualTo("DRAFT");
        library.review("reviewer",concept,revision.id(),true);
        assertThat(library.revisions("reviewer",concept)).extracting(ConceptLibrary.Revision::status).containsExactly("APPROVED","REVOKED");
        assertThat(run(null).selected().conceptVersion()).isEqualTo(2);
    }
    @Test void unknownCriticalInformationDoesNotPoisonNegativeCache(){
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> c=call.getArgument(1);var v=value(c.get(0),"Execution",0,true);
            return new Evaluated(List.of(new Evaluation(v.conceptId(),v.conceptVersion(),v.adaptation(),new Ratings(null,0,0,0,0),v.hardViolations(),List.of("Missing product evidence"),v.reasons(),true)),new Usage(100,50,"provider"));});
        assertThat(run(null).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");assertThat(count("concept_match_cache")).isZero();
    }
    @Test void groundedCooldownIsScopedToExactContextAndExpires(){
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> c=call.getArgument(1);return new Evaluated(List.of(value(c.get(0),"Execution",0,true)),new Usage(100,50,"provider"));});
        assertThat(run(null).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");assertThat(count("concept_match_cache")).isEqualTo(1);
        assertThat(run(null).status()).isEqualTo("TEMPORARILY_SUPPRESSED");
        var different=service.start("selection-owner","changed-intent",new Start(product,"Different intent","square",null,null));worker.run(different.id());
        assertThat(service.status("selection-owner",different.id()).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");
        db.update("UPDATE concept_match_cache SET expires_at=?",stamp(Instant.now().minusSeconds(1)));
        assertThat(run(null).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");
    }
    @Test void invalidResponseRetriesSamePersistedBatchAndNeverCreatesZeroScores(){
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenThrow(new IllegalArgumentException("Invalid response"));
        assertThat(run(null).status()).isEqualTo("PROVIDER_FAILED");assertThat(count("concept_evaluations")).isZero();
        assertThat(count("selection_attempts")).isEqualTo(2);assertThat(count("selection_batches")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT spent_micros FROM selection_requests",Long.class)).isEqualTo(700000);
    }
    @Test void verificationCannotRewriteAdaptation(){
        when(evaluator.evaluate(any(),anyList(),notNull(),any())).thenAnswer(call->{List<Candidate> c=call.getArgument(1);return new Evaluated(List.of(value(c.get(0),"Changed execution",4,false)),new Usage(100,50,"provider"));});
        assertThat(run(null).status()).isEqualTo("PROVIDER_FAILED");assertThat(count("concept_allocations")).isZero();
    }
    @Test void productChangesDuringVerificationInvalidateReservation(){
        when(evaluator.evaluate(any(),anyList(),notNull(),any())).thenAnswer(call->{db.update("UPDATE products SET description='Edited while evaluating' WHERE id=?",product);
            List<Candidate> c=call.getArgument(1);Evaluation exact=call.getArgument(2);return new Evaluated(List.of(value(c.get(0),exact.adaptation(),4,false)),new Usage(100,50,"provider"));});
        assertThat(run(null).status()).isEqualTo("STALE_CONTEXT");assertThat(count("concept_allocations")).isZero();
    }
    @Test void revocationDuringVerificationPreventsReservation(){
        when(evaluator.evaluate(any(),anyList(),notNull(),any())).thenAnswer(call->{library.review("reviewer",concept,concept,false);
            List<Candidate> c=call.getArgument(1);Evaluation exact=call.getArgument(2);return new Evaluated(List.of(value(c.get(0),exact.adaptation(),4,false)),new Usage(100,50,"provider"));});
        assertThat(run(null).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");assertThat(count("concept_allocations")).isZero();
    }
    @Test void selectionDeadlineAndMoneyStopCalls(){
        var request=start(null);db.update("UPDATE selection_requests SET deadline=? WHERE id=?",stamp(Instant.now().minusSeconds(1)),request.id());
        worker.run(request.id());assertThat(service.status("selection-owner",request.id()).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");verifyNoInteractions(evaluator);
        request=start(null);db.update("UPDATE selection_requests SET spent_micros=? WHERE id=?",policy.maxCostMicros,request.id());
        worker.run(request.id());assertThat(service.status("selection-owner",request.id()).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");verifyNoInteractions(evaluator);
    }
    @Test void lostWorkerWithUnknownProviderAttemptIsNeverRedispatched(){
        var request=start(null);var claimed=store.claim(request.id());
        db.update("INSERT INTO selection_attempts(id,request_id,stage,status,reserved_micros,created_at) VALUES(?,?,'SCREEN','STARTED',350000,?)",UUID.randomUUID(),request.id(),stamp(Instant.now()));
        db.update("UPDATE selection_requests SET lease_until=? WHERE id=?",stamp(Instant.now().minusSeconds(1)),request.id());
        worker.run(request.id());assertThat(service.status("selection-owner",request.id()).status()).isEqualTo("PROVIDER_FAILED");verifyNoInteractions(evaluator);
        assertThatThrownBy(()->store.fenced(claimed,()->null)).isInstanceOf(SelectionStore.StaleWorker.class);
    }
    @Test void unavailableGenerationProviderIsExplicitAndKeepsReservation(){
        var selected=run(null);when(generator.available()).thenReturn(false);generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("WAITING_FOR_PROVIDER");
        assertThat(count("concept_allocation_claims")).isEqualTo(1);assertThat(run(concept).status()).isEqualTo("SELECTION_BUSY");
    }
    @Test void generationTimeoutRequiresReconciliationAndNeverDuplicatesBilling(){
        var selected=run(null);when(generator.generate(any(),any(),any())).thenThrow(new RuntimeException("Unknown timeout"));
        generation.run(selected.generationJobId());generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(count("concept_allocation_claims")).isEqualTo(1);verify(generator,times(1)).generate(any(),any(),any());
        verify(outputs,never()).cleanup(any(),any(),any());
    }
    @Test void rejectedOutputReleasesButDoesNotEraseHistory(){
        var selected=run(null);when(reviewer.review(any(),any())).thenReturn(new OutputReviewer.Review(false,"Product was altered",10000));
        generation.run(selected.generationJobId());assertThat(count("creations")).isZero();assertThat(count("concept_allocation_claims")).isZero();
        assertThat(db.queryForObject("SELECT state FROM concept_allocations",String.class)).isEqualTo("RELEASED");assertThat(count("concept_evaluations")).isEqualTo(2);
        verify(outputs,times(1)).cleanup(any(),eq(selected.generationJobId()),any());
    }
    @Test void editingProductDoesNotResetConsumedHistory(){
        var selected=run(null);generation.run(selected.generationJobId());
        db.update("UPDATE products SET description='Updated product facts' WHERE id=?",product);
        assertThat(run(concept).status()).isEqualTo("POOL_EXHAUSTED");
    }
    @Test void generationRechecksRevocationBeforeDispatch(){
        var selected=run(null);library.review("reviewer",concept,concept,false);generation.run(selected.generationJobId());
        assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("STALE_CONTEXT");verify(generator,never()).generate(any(),any(),any());
    }
    @Test void concurrentRequestsCannotReserveSameConcept() throws Exception {
        var first=start(concept);var second=start(concept);var gate=new CyclicBarrier(2);
        when(evaluator.evaluate(any(),anyList(),notNull(),any())).thenAnswer(call->{gate.await(10,TimeUnit.SECONDS);
            List<Candidate> c=call.getArgument(1);Evaluation exact=call.getArgument(2);return new Evaluated(List.of(value(c.get(0),exact.adaptation(),4,false)),new Usage(100,50,"provider"));});
        var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->worker.run(first.id()));var b=pool.submit(()->worker.run(second.id()));a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(count("concept_allocations")).isEqualTo(1);assertThat(count("concept_allocation_claims")).isEqualTo(1);
        assertThat(List.of(service.status("selection-owner",first.id()).status(),service.status("selection-owner",second.id()).status())).contains("SELECTED");
    }
    @Test void concurrentIdempotencyCreatesOneRequest() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->service.start("selection-owner","concurrent",input(null)));var b=pool.submit(()->service.start("selection-owner","concurrent",input(null)));
            assertThat(a.get(10,TimeUnit.SECONDS).id()).isEqualTo(b.get(10,TimeUnit.SECONDS).id());}finally{pool.shutdownNow();}
        assertThat(count("selection_requests")).isEqualTo(1);
    }
    @Test void concurrentCooldownProjectionDoesNotLoseEvaluations() throws Exception {
        var first=start(concept);var second=start(concept);var gate=new CyclicBarrier(2);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{gate.await(10,TimeUnit.SECONDS);List<Candidate> cards=call.getArgument(1);
            return new Evaluated(List.of(value(cards.get(0),"Execution",0,true)),new Usage(100,50,"provider"));});
        var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->worker.run(first.id()));var b=pool.submit(()->worker.run(second.id()));a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(count("concept_evaluations")).isEqualTo(2);assertThat(count("concept_match_cache")).isEqualTo(1);
        assertThat(service.status("selection-owner",first.id()).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");
        assertThat(service.status("selection-owner",second.id()).status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");
    }
    @Test void otherUsersCannotPollSelection(){
        var request=start(null);db.update("INSERT INTO users(id,google_id,email,onboarding_completed,created_at) VALUES(?,'stranger','stranger@example.com',TRUE,?)",UUID.randomUUID(),stamp(Instant.now()));
        assertThatThrownBy(()->service.status("stranger",request.id())).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
    @Test void threeBatchesAreBoundedAndNeverResampleValidFailures(){
        for(int i=0;i<34;i++)seed(true);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);
            return new Evaluated(cards.stream().map(c->value(c,"Execution",2,false)).toList(),new Usage(100,50,"provider"));});
        var result=run(null);assertThat(result.status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");assertThat(result.rounds()).isEqualTo(3);
        assertThat(count("selection_seen")).isEqualTo(30);assertThat(count("concept_evaluations")).isEqualTo(30);verify(evaluator,times(3)).evaluate(any(),anyList(),any(),any());
    }
    @Test void verificationAttemptsIncludingRetriesRespectRequestCap(){
        policy.maxVerificationCalls=1;
        when(evaluator.evaluate(any(),anyList(),notNull(),any())).thenThrow(new IllegalArgumentException("Invalid verification"));
        var result=run(null);assertThat(result.verificationCalls()).isEqualTo(1);assertThat(result.status()).isEqualTo("NO_MATCH_WITHIN_BUDGET");
        verify(evaluator,times(2)).evaluate(any(),anyList(),any(),any());assertThat(count("concept_allocations")).isZero();
    }
    @Test void completedProviderResponsesResumeWithoutRepeatingCallsOrCharges(){
        policy.maxVerificationCalls=1;
        var result=run(null);long spent=result.costMicros();
        // Simulate recovery after responses persisted but before their projections committed.
        for(String table:List.of("outbox_events","generation_jobs","concept_allocation_claims","concept_allocations"))db.update("DELETE FROM "+table);
        db.update("UPDATE selection_requests SET selected_evaluation_id=NULL,status='QUEUED',lease_until=NULL WHERE id=?",result.id());
        db.update("UPDATE concept_evaluations SET verified_evaluation_id=NULL,verification_status='PENDING'");db.update("DELETE FROM concept_evaluations");
        db.update("UPDATE selection_batches SET completed=FALSE");clearInvocations(evaluator);
        worker.run(result.id());var recovered=service.status("selection-owner",result.id());
        assertThat(recovered.status()).isEqualTo("SELECTED");assertThat(recovered.costMicros()).isEqualTo(spent);
        assertThat(count("concept_evaluations")).isEqualTo(2);verifyNoInteractions(evaluator);
    }
    @Test void staleWorkerCannotCommitProviderResponseOrAdoptNewFence(){
        var result=start(null);
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{
            db.update("UPDATE selection_requests SET fence=fence+1,lease_until=? WHERE id=?",stamp(Instant.now().plusSeconds(150)),result.id());
            List<Candidate> cards=call.getArgument(1);return new Evaluated(List.of(value(cards.get(0),"Execution",4,false)),new Usage(100,50,"provider"));});
        worker.run(result.id());assertThat(count("concept_evaluations")).isZero();assertThat(count("concept_match_cache")).isZero();
        assertThat(count("concept_allocations")).isZero();assertThat(store.get(result.id()).status()).isEqualTo("SCREENING");
    }
    @Test void crashedGenerationIsNotRedispatched(){
        var selected=run(null);db.update("UPDATE generation_jobs SET status='GENERATING',lease_until=? WHERE id=?",stamp(Instant.now().minusSeconds(1)),selected.generationJobId());
        generation.run(selected.generationJobId());assertThat(service.status("selection-owner",selected.id()).generationStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        verify(generator,never()).generate(any(),any(),any());assertThat(count("concept_allocation_claims")).isEqualTo(1);
    }
    @Test void unauthenticatedAndCsrfFailuresReachSecurityChain() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters((jakarta.servlet.Filter)webContext.getBean("springSecurityFilterChain")).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/selection-requests/"+UUID.randomUUID()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var principal=mock(org.springframework.security.oauth2.core.oidc.user.OidcUser.class);when(principal.getSubject()).thenReturn("selection-owner");
        var auth=new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(principal,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")),"google");
        var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",new org.springframework.security.core.context.SecurityContextImpl(auth));
        String body=store.json.write(input(null));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/selection-requests").session(session)
                .header("Idempotency-Key","security").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var csrf=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/security/csrf").session(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        var cookie=csrf.getCookie("XSRF-TOKEN");assertThat(cookie).isNotNull();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/selection-requests").session(session).cookie(cookie)
                .header("X-XSRF-TOKEN",cookie.getValue()).header("Idempotency-Key","security").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
    }
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing","duplicate","unknown","version","evidence","nullRating"})
    void malformedMatchingResultsNeverBecomeEvaluations(String mutation){
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call->{List<Candidate> cards=call.getArgument(1);var v=value(cards.get(0),"Execution",4,false);
            List<Evaluation> values=switch(mutation){
                case "missing"->List.of();case "duplicate"->List.of(v,v);
                case "unknown"->List.of(new Evaluation(UUID.randomUUID(),1,v.adaptation(),v.ratings(),v.hardViolations(),v.uncertainties(),v.reasons(),false));
                case "version"->List.of(new Evaluation(v.conceptId(),999,v.adaptation(),v.ratings(),v.hardViolations(),v.uncertainties(),v.reasons(),false));
                case "evidence"->List.of(new Evaluation(v.conceptId(),1,v.adaptation(),v.ratings(),v.hardViolations(),v.uncertainties(),
                        ScorePolicy.DIMENSIONS.stream().map(d->new Reason(d,"SUPPORTED",List.of("/does-not-exist"))).toList(),false));
                default->List.of(new Evaluation(v.conceptId(),1,v.adaptation(),new Ratings(null,4,4,4,4),v.hardViolations(),List.of(),v.reasons(),false));
            };return new Evaluated(values,new Usage(100,50,"provider"));});
        assertThat(run(null).status()).isEqualTo("PROVIDER_FAILED");assertThat(count("concept_evaluations")).isZero();assertThat(count("concept_match_cache")).isZero();
    }
    @Test void databaseConstrainsRatingsAndPostgresActiveAllocations() throws Exception {
        var selected=run(null);
        assertThatThrownBy(()->db.update("UPDATE concept_evaluations SET product_fidelity=5"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        try(var connection=db.getDataSource().getConnection()){
            if(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")){
                assertThat(db.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='uq_active_concept_allocation'",Long.class)).isEqualTo(1);
                var another=start(concept);
                assertThatThrownBy(()->db.update("INSERT INTO concept_allocations(id,request_id,owner_id,product_id,concept_id,evaluation_id,state,created_at) VALUES(?,?,?,?,?,?,'RESERVED',?)",
                        UUID.randomUUID(),another.id(),owner,product,concept,store.get(selected.id()).selected(),stamp(Instant.now())))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            }
        }
        assertThat(count("concept_allocation_claims")).isEqualTo(1);
    }
    @Test void creationHistoryAndImageDeliveryRemainPrivate() throws Exception {
        var selected=run(null);generation.run(selected.generationJobId());
        var controller=new CreationController(store,assets,mock(GeneratedAdDeletionQueue.class));
        var user=mock(org.springframework.security.oauth2.core.oidc.user.OidcUser.class);when(user.getSubject()).thenReturn("selection-owner");
        var history=controller.list(user,product,0,20);assertThat(history).hasSize(1);
        UUID creation=history.get(0).id();assertThat(history.get(0).imageUrl()).isEqualTo("/api/v1/creations/"+creation+"/image");
        byte[] image=ProductImageProcessorTests.fixture("png");when(assets.read(any())).thenReturn(image);
        var response=controller.image(user,creation);assertThat(response.getBody()).isEqualTo(image);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        db.update("INSERT INTO users(id,google_id,email,onboarding_completed,created_at) VALUES(?,'other-owner','other@example.com',TRUE,?)",UUID.randomUUID(),stamp(Instant.now()));
        when(user.getSubject()).thenReturn("other-owner");
        assertThatThrownBy(()->controller.list(user,product,0,20)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(()->controller.image(user,creation)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
    @Test void legacyBlankProductFactsCannotEnterSelection(){
        db.update("UPDATE products SET name=' ' WHERE id=?",product);
        assertThatThrownBy(()->start(null)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(count("selection_requests")).isZero();verifyNoInteractions(evaluator);
    }
}
