package com.giraffix.generation;

import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.giraffix.selection.SelectionStore.stamp;

@Service
public class GenerationWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GenerationWorker.class);
    private final SelectionStore store;
    private final SelectionContextService contexts;
    private final ImageGenerator provider;
    private final GeneratedAssetStorage storage;
    private final OutputReviewer reviewer;
    private final AdPromptWriter promptWriter;
    private final SelectionAssets assets;
    @org.springframework.beans.factory.annotation.Autowired
    private com.giraffix.usage.UsageService usage;
    private final java.util.concurrent.ScheduledExecutorService leases = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task,"generation-lease-renewal");thread.setDaemon(true);return thread;
    });
    @jakarta.annotation.PreDestroy
    public void close() {leases.shutdownNow();}
    public GenerationWorker(SelectionStore store,SelectionContextService contexts,ImageGenerator provider,GeneratedAssetStorage storage,OutputReviewer reviewer,AdPromptWriter promptWriter,SelectionAssets assets){
        this.store=store;this.contexts=contexts;this.provider=provider;this.storage=storage;this.reviewer=reviewer;this.promptWriter=promptWriter;
        this.assets=assets;
    }
    public List<UUID> pending(int limit){
        Instant now=Instant.now();
        return store.db.query("SELECT id FROM generation_jobs WHERE status IN ('QUEUED','WAITING_FOR_PROVIDER','PROMPTING','PROMPT_READY','GENERATING','REVIEWING') AND (lease_until IS NULL OR lease_until<=?) AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY created_at,id LIMIT ?",
                (r,n)->r.getObject(1,UUID.class),stamp(now),stamp(now),limit);
    }
    public void poll(){for(var id:pending(10))run(id);}
    public void run(UUID id){
        Job job=store.transaction(()->{
            if(store.db.update("UPDATE generation_jobs SET fence=fence+1,lease_until=?,next_attempt_at=NULL WHERE id=? AND status IN ('QUEUED','WAITING_FOR_PROVIDER','PROMPTING','PROMPT_READY','GENERATING','REVIEWING') AND (lease_until IS NULL OR lease_until<=?) AND (next_attempt_at IS NULL OR next_attempt_at<=?)",
                    stamp(Instant.now().plusSeconds(150)),id,stamp(Instant.now()),stamp(Instant.now()))!=1)return null;
            return get(id);
        });
        if(job==null)return;
        // Long image calls keep their lease; a process crash still becomes reclaimable promptly.
        var renewal = leases.scheduleAtFixedRate(() -> {
            try {
                store.db.update("UPDATE generation_jobs SET lease_until=? WHERE id=? AND fence=? AND lease_until>? AND status IN ('QUEUED','WAITING_FOR_PROVIDER','PROMPTING','PROMPT_READY','GENERATING','REVIEWING')",
                        stamp(Instant.now().plusSeconds(150)),id,job.fence(),stamp(Instant.now()));
            } catch (RuntimeException ex) {
                log.warn("stage=generation_lease_renewal_failed jobId={} errorType={}",id,ex.getClass().getSimpleName());
            }
        },30,30,java.util.concurrent.TimeUnit.SECONDS);
        String stage=job.status();
        try(var scope=assets.openScope();var operation=org.slf4j.MDC.putCloseable("operationId",id.toString())){
            log.info("stage=generation_claimed jobId={} state={} fence={}",id,job.status(),job.fence());
            if(job.status().equals("GENERATING") || job.status().equals("PROMPTING")){
                fenced(job,()->store.db.update("UPDATE generation_jobs SET status='RECONCILIATION_REQUIRED',lease_until=NULL WHERE id=?",id));return;
            }
            if(!job.deadline().isAfter(Instant.now())){release(job,"FAILED");return;}
            if(job.status().equals("REVIEWING")) {review(job);return;}
            if(!provider.available()){
                Instant retry=Instant.now().plusSeconds(60);
                Instant next=retry.isAfter(job.deadline())?job.deadline():retry;
                fenced(job,()->store.db.update("UPDATE generation_jobs SET status='WAITING_FOR_PROVIDER',lease_until=NULL,next_attempt_at=? WHERE id=?",stamp(next),id));return;
            }
            if (!current(job)) {release(job,"STALE_CONTEXT");return;}
            if(job.brief().context().aspectRatio()==null || job.brief().generationLimits().promptReserveMicros()<1){
                release(job,"FAILED");return;
            }
            stage="PROMPTING";
            Job prepared=preparePrompt(job);
            stage="GENERATING";
            Job reviewing=render(prepared);
            if(reviewing==null)return;
            stage="REVIEWING";
            review(reviewing);
        }catch(SelectionStore.StaleWorker ignored){}
        catch(PromptGenerationException ex) {
            log.warn("stage=ad_prompt_failed jobId={} code={} stopReason={} providerRequestId={}",id,ex.code(),ex.stopReason(),ex.providerId());
            try {fenced(job,()->{
                store.db.update("DELETE FROM concept_allocation_claims WHERE allocation_id=?",job.allocation());
                store.db.update("UPDATE concept_allocations SET state='RELEASED' WHERE id=? AND state='RESERVED'",job.allocation());
                long cost=ex.costMicros()==null?job.brief().generationLimits().promptReserveMicros():ex.costMicros();
                store.db.update("UPDATE generation_jobs SET status='PROMPT_FAILED',lease_until=NULL,failure_code=?,failure_stage='PROMPTING',failure_message=?,failure_provider_id=?,failure_stop_reason=?,prompt_cost_micros=?,spent_micros=? WHERE id=?",
                        ex.code(),ex.getMessage(),ex.providerId(),ex.stopReason(),cost,cost,id);
            });}catch(SelectionStore.StaleWorker ignored){}
        }
        catch(RuntimeException ex){
            log.warn("stage=generation_reconciliation_required jobId={} failedStage={} errorType={}",id,stage,ex.getClass().getSimpleName());
            // Dispatch/upload may have succeeded remotely. Keep occupancy until reconciled.
            String failedStage=stage;
            String code="PROMPTING".equals(stage)?"PROMPT_OUTCOME_UNKNOWN":"GENERATION_OUTCOME_UNKNOWN";
            String message="PROMPTING".equals(stage)?"Prompt writing failed with an uncertain outcome; inspect the provider request before retrying"
                    :"Generation failed with an uncertain outcome; inspect the job before retrying";
            try{fenced(job,()->store.db.update("UPDATE generation_jobs SET status='RECONCILIATION_REQUIRED',lease_until=NULL,failure_code=?,failure_stage=?,failure_message=? WHERE id=?",
                    code,failedStage,message,id));}
            catch(SelectionStore.StaleWorker ignored){}
        } finally {renewal.cancel(false);}
    }
    private Job preparePrompt(Job job){
        if(job.promptText()!=null)return job;
        long reserve=job.brief().generationLimits().promptReserveMicros();
        fenced(job,()->store.db.update("UPDATE generation_jobs SET status='PROMPTING',spent_micros=? WHERE id=?",job.brief().generationLimits().maxCostMicros(),job.id()));
        log.info("stage=ad_prompt_started jobId={} aspectRatio={}",job.id(),job.brief().context().aspectRatio());
        var prompt=promptWriter.write(job.brief(),reserve,job.deadline(),job.directorPromptVersion());
        if(prompt==null || prompt.prompt()==null || prompt.prompt().isBlank() || prompt.prompt().length()>30000
                || prompt.model()==null || prompt.version()==null || prompt.costMicros()<0 || prompt.costMicros()>reserve)
            throw new IllegalArgumentException("Invalid creative direction result");
        fenced(job,()->{
            store.db.update("UPDATE generation_jobs SET status='PROMPT_READY',prompt_text=?,prompt_model=?,prompt_version=?,prompt_provider_id=?,prompt_cost_micros=?,spent_micros=? WHERE id=?",
                    prompt.prompt(),prompt.model(),prompt.version(),prompt.providerId(),prompt.costMicros(),prompt.costMicros(),job.id());
            if(usage!=null)usage.recordAdCall(job.id(),"PROMPT",prompt.model(),prompt.providerId(),prompt.inputTokens(),prompt.outputTokens(),prompt.costMicros());
        });
        log.info("stage=ad_prompt_completed jobId={} promptVersion={}",job.id(),prompt.version());
        return new Job(job.id(),job.allocation(),job.brief(),"PROMPT_READY",job.fence(),job.deadline(),job.outputJson(),job.reviewStarted(),
                prompt.prompt(),prompt.costMicros(),prompt.version(),job.reviewJson(),job.directorPromptVersion());
    }
    private Job render(Job job){
        boolean valid=store.transaction(()->{
            if(!contexts.current(job.brief().context()))return false;
            store.db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",job.brief().concept().conceptId());
            if(store.db.queryForObject("SELECT COUNT(*) FROM concept_revisions WHERE id=? AND status='APPROVED'",Integer.class,job.brief().concept().revisionId())!=1)return false;
            if(!assertFence(job).isAfter(Instant.now())){release(job,"FAILED");return false;}
            store.db.update("UPDATE generation_jobs SET status='GENERATING',spent_micros=? WHERE id=?",job.brief().generationLimits().maxCostMicros(),job.id());
            return true;
        });
        if(!valid){try{release(job,"STALE_CONTEXT");}catch(SelectionStore.StaleWorker ignored){}return null;}
        Brief brief=job.brief().withPrompt(job.promptText());
        long budget=job.brief().generationLimits().maxCostMicros()-job.brief().generationLimits().reviewReserveMicros()-job.promptCost();
        log.info("stage=ad_render_started jobId={} aspectRatio={}",job.id(),brief.context().aspectRatio());
        var output=provider.generate(job.id(),brief,new ImageGenerator.Budget(budget,job.deadline()));
        if(output==null || output.image()==null || output.costMicros()<0 || output.costMicros()>budget)
            throw new IllegalStateException("Invalid generation result or budget exceeded");
        if(!ImageAspectRatios.matches(output.image(),brief.context().aspectRatio())){
            fenced(job,()->{
                store.db.update("UPDATE generation_jobs SET provider_id=?,spent_micros=? WHERE id=?",output.operationId(),output.costMicros()+job.promptCost(),job.id());
                if(usage!=null)usage.recordAdCall(job.id(),"RENDER",output.model(),output.operationId(),output.inputTokens(),output.outputTokens(),output.costMicros());
            });
            release(job,"OUTPUT_REJECTED");return null;
        }
        var asset=storage.put(job.brief().context().ownerId(),job.id(),output.image());
        fenced(job,()->{
            store.db.update("UPDATE generation_jobs SET status='REVIEWING',output_json=?,provider_id=?,spent_micros=? WHERE id=?",
                    store.json.write(asset),output.operationId(),output.costMicros()+job.brief().generationLimits().reviewReserveMicros()+job.promptCost(),job.id());
            if(usage!=null)usage.recordAdCall(job.id(),"RENDER",output.model(),output.operationId(),output.inputTokens(),output.outputTokens(),output.costMicros());
        });
        Job reviewing=get(job.id());
        if(reviewing.fence()!=job.fence())throw new SelectionStore.StaleWorker();
        return reviewing;
    }
    private void review(Job job){
        final long reviewReserve=job.brief().generationLimits().reviewReserveMicros();
        if (!job.deadline().isAfter(Instant.now())) {release(job,"FAILED");return;}
        if (!current(job)) {release(job,"STALE_CONTEXT");return;}
        // Review attempts are persisted. Recovery never repeats a possibly billed review blindly.
        if(job.reviewStarted() && job.reviewJson()==null){
            fenced(job,()->store.db.update("UPDATE generation_jobs SET status='RECONCILIATION_REQUIRED',lease_until=NULL WHERE id=?",job.id()));return;
        }
        OutputReviewer.Review result;
        if(job.reviewJson()!=null){
            result=store.json.read(job.reviewJson(),OutputReviewer.Review.class);
        }else{
            fenced(job,()->store.db.update("UPDATE generation_jobs SET review_started=TRUE WHERE id=?",job.id()));
            var asset=store.json.read(job.outputJson(),Asset.class);
            result=reviewer.review(job.brief().withPrompt(job.promptText()),asset);
            if(result==null || result.costMicros()<0 || result.costMicros()>reviewReserve)throw new IllegalStateException("Output review exceeded budget");
            OutputReviewer.Review completed=result;
            // Persist the paid result before publication; recovery can now repeat only DB work.
            fenced(job,()->{
                store.db.update("UPDATE generation_jobs SET review_json=?,spent_micros=spent_micros-?+? WHERE id=?",
                        store.json.write(completed),reviewReserve,completed.costMicros(),job.id());
                if(usage!=null)usage.recordAdCall(job.id(),"REVIEW",job.brief().selectionPolicy().model(),completed.providerId(),completed.inputTokens(),completed.outputTokens(),completed.costMicros());
            });
        }
        if(!result.accepted()){release(job,"OUTPUT_REJECTED");return;}
        publish(job,result);
    }
    private void publish(Job job,OutputReviewer.Review result){
        try{store.transaction(()->{
            if(!job.deadline().isAfter(Instant.now())){release(job,"FAILED");return null;}
            if(!contexts.current(job.brief().context())){release(job,"STALE_CONTEXT");return null;}
            store.db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",job.brief().concept().conceptId());
            if(!assertFence(job).isAfter(Instant.now())){release(job,"FAILED");return null;}
            if(store.db.queryForObject("SELECT COUNT(*) FROM concept_revisions WHERE id=? AND status='APPROVED'",Integer.class,job.brief().concept().revisionId())!=1)
                throw new IllegalStateException("Concept was revoked");
            var rows=store.db.queryForList("SELECT evaluation_id FROM concept_allocations WHERE id=? AND state='RESERVED' FOR UPDATE",job.allocation());
            if(rows.size()!=1)throw new IllegalStateException("Allocation is no longer reserved");
            UUID evaluation=(UUID)rows.get(0).get("evaluation_id");
            UUID brandId=store.db.queryForObject("SELECT brand_id FROM products WHERE id=?",UUID.class,job.brief().context().productId());
            store.db.update("INSERT INTO creations(id,job_id,owner_id,product_id,concept_id,evaluation_id,asset_json,review_json,created_at,aspect_ratio,image_prompt,prompt_version,quality,brand_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(),job.id(),job.brief().context().ownerId(),job.brief().context().productId(),job.brief().concept().conceptId(),evaluation,
                    job.outputJson(),store.json.write(result),stamp(Instant.now()),job.brief().context().aspectRatio(),job.promptText(),job.promptVersion(),Objects.toString(job.brief().context().quality(),"high"),brandId);
            store.db.update("UPDATE concept_allocations SET state='CONSUMED' WHERE id=?",job.allocation());
            store.db.update("UPDATE generation_jobs SET status='SUCCEEDED',lease_until=NULL,next_attempt_at=NULL WHERE id=?",job.id());
            log.info("stage=creation_persisted jobId={} conceptId={}",job.id(),job.brief().concept().conceptId());return null;
        });}catch(org.springframework.dao.TransientDataAccessException ex){
            // Nothing external happens in publication. Retry a transient DB failure with the saved review.
            fenced(job,()->store.db.update("UPDATE generation_jobs SET lease_until=NULL,next_attempt_at=? WHERE id=?",stamp(Instant.now().plusSeconds(5)),job.id()));
            log.warn("stage=publication_deferred jobId={} errorType={}",job.id(),ex.getClass().getSimpleName());
        }
    }
    private void release(Job job,String state){fenced(job,()->{
        store.db.update("DELETE FROM concept_allocation_claims WHERE allocation_id=?",job.allocation());
        store.db.update("UPDATE concept_allocations SET state='RELEASED' WHERE id=? AND state='RESERVED'",job.allocation());
        store.db.update("UPDATE generation_jobs SET status=?,lease_until=NULL WHERE id=?",state,job.id());
        log.info("stage=generation_finished jobId={} state={}",job.id(),state);
        if(job.outputJson()!=null && List.of("OUTPUT_REJECTED","STALE_CONTEXT","FAILED").contains(state)){
            Asset asset=store.json.read(job.outputJson(),Asset.class);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){storage.cleanup(job.brief().context().ownerId(),job.id(),asset);}
            });
        }
    });}
    private boolean current(Job job) {
        return store.transaction(() -> {
            if (!contexts.current(job.brief().context())) return false;
            store.db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",job.brief().concept().conceptId());
            return store.db.queryForObject("SELECT COUNT(*) FROM concept_revisions WHERE id=? AND status='APPROVED'",Integer.class,job.brief().concept().revisionId())==1;
        });
    }
    private void fenced(Job job,Runnable action){store.transaction(()->{assertFence(job);action.run();return null;});}
    private Instant assertFence(Job job){
        var current=store.db.query("SELECT fence,lease_until,status,deadline FROM generation_jobs WHERE id=? FOR UPDATE",(r,n)->new Object[]{r.getLong("fence"),r.getTimestamp("lease_until"),r.getString("status"),r.getTimestamp("deadline")},job.id()).get(0);
        if((long)current[0]!=job.fence() || current[1]==null || !((java.sql.Timestamp)current[1]).toInstant().isAfter(Instant.now())
                || !List.of("QUEUED","WAITING_FOR_PROVIDER","PROMPTING","PROMPT_READY","GENERATING","REVIEWING").contains(current[2]))throw new SelectionStore.StaleWorker();
        return ((java.sql.Timestamp)current[3]).toInstant();
    }
    private Job get(UUID id){return store.db.query("SELECT * FROM generation_jobs WHERE id=?",(r,n)->new Job(id,r.getObject("allocation_id",UUID.class),
            store.json.read(r.getString("brief_json"),Brief.class),r.getString("status"),r.getLong("fence"),r.getTimestamp("deadline").toInstant(),r.getString("output_json"),r.getBoolean("review_started"),r.getString("prompt_text"),r.getLong("prompt_cost_micros"),r.getString("prompt_version"),r.getString("review_json"),r.getInt("director_prompt_version")),id).get(0);}
    private record Job(UUID id,UUID allocation,Brief brief,String status,long fence,Instant deadline,String outputJson,boolean reviewStarted,String promptText,long promptCost,String promptVersion,String reviewJson,int directorPromptVersion){}
}
