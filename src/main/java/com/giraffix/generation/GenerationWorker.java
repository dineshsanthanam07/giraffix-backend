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
    private final java.util.concurrent.ScheduledExecutorService leases = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task,"generation-lease-renewal");thread.setDaemon(true);return thread;
    });
    @jakarta.annotation.PreDestroy
    public void close() {leases.shutdownNow();}
    public GenerationWorker(SelectionStore store,SelectionContextService contexts,ImageGenerator provider,GeneratedAssetStorage storage,OutputReviewer reviewer,AdPromptWriter promptWriter){
        this.store=store;this.contexts=contexts;this.provider=provider;this.storage=storage;this.reviewer=reviewer;this.promptWriter=promptWriter;
    }
    public void poll(){
        var ids=store.db.query("SELECT id FROM generation_jobs WHERE status IN ('QUEUED','WAITING_FOR_PROVIDER','PROMPTING','PROMPT_READY','GENERATING','REVIEWING') AND (lease_until IS NULL OR lease_until<=?) ORDER BY created_at LIMIT 10",
                (r,n)->r.getObject(1,UUID.class),stamp(Instant.now()));
        for(var id:ids)run(id);
    }
    public void run(UUID id){
        Job job=store.transaction(()->{
            if(store.db.update("UPDATE generation_jobs SET fence=fence+1,lease_until=? WHERE id=? AND status IN ('QUEUED','WAITING_FOR_PROVIDER','PROMPTING','PROMPT_READY','GENERATING','REVIEWING') AND (lease_until IS NULL OR lease_until<=?)",
                    stamp(Instant.now().plusSeconds(150)),id,stamp(Instant.now()))!=1)return null;
            return get(id);
        });
        if(job==null)return;
        final long maxCost=job.brief().generationLimits().maxCostMicros();
        final long reviewReserve=job.brief().generationLimits().reviewReserveMicros();
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
        try{
            if(job.status().equals("GENERATING") || job.status().equals("PROMPTING")){
                fenced(job,()->store.db.update("UPDATE generation_jobs SET status='RECONCILIATION_REQUIRED',lease_until=NULL WHERE id=?",id));return;
            }
            if(!job.deadline().isAfter(Instant.now())){release(job,"FAILED");return;}
            if(job.status().equals("REVIEWING")) {review(job);return;}
            if(!provider.available()){
                fenced(job,()->store.db.update("UPDATE generation_jobs SET status='WAITING_FOR_PROVIDER',lease_until=NULL WHERE id=?",id));return;
            }
            if (!current(job)) {release(job,"STALE_CONTEXT");return;}
            if (job.brief().context().aspectRatio() != null && job.promptText() == null) {
                long reserve = job.brief().generationLimits().promptReserveMicros();
                if (reserve < 1) {release(job,"FAILED");return;}
                fenced(job, () -> store.db.update("UPDATE generation_jobs SET status='PROMPTING',spent_micros=? WHERE id=?",maxCost,id));
                log.info("stage=ad_prompt_started jobId={} aspectRatio={}",id,job.brief().context().aspectRatio());
                stage="PROMPTING";
                var prompt = promptWriter.write(job.brief(),reserve,job.deadline());
                if (prompt == null || prompt.prompt() == null || prompt.prompt().isBlank() || prompt.prompt().length()>30000
                        || prompt.model()==null || prompt.version()==null || prompt.costMicros()<0 || prompt.costMicros()>reserve)
                    throw new IllegalArgumentException("Invalid creative direction result");
                fenced(job, () -> store.db.update("UPDATE generation_jobs SET status='PROMPT_READY',prompt_text=?,prompt_model=?,prompt_version=?,prompt_provider_id=?,prompt_cost_micros=?,spent_micros=? WHERE id=?",
                        prompt.prompt(),prompt.model(),prompt.version(),prompt.providerId(),prompt.costMicros(),prompt.costMicros(),id));
                log.info("stage=ad_prompt_completed jobId={} promptVersion={}",id,prompt.version());
            }
            Job prepared = get(id);
            if (prepared.fence()!=job.fence()) throw new SelectionStore.StaleWorker();
            if (!prepared.deadline().isAfter(Instant.now())) {release(prepared,"FAILED");return;}
            boolean valid=store.transaction(()->{
                // Same lock order as selection; approval writers lock the stable concept row.
                if(!contexts.current(job.brief().context()))return false;
                store.db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",job.brief().concept().conceptId());
                if(store.db.queryForObject("SELECT COUNT(*) FROM concept_revisions WHERE id=? AND status='APPROVED'",Integer.class,job.brief().concept().revisionId())!=1)return false;
                // Lease check and dispatch state persist in this same transaction.
                assertFence(job);
                store.db.update("UPDATE generation_jobs SET status='GENERATING',spent_micros=? WHERE id=?",maxCost,id);
                store.db.update("UPDATE outbox_events SET delivered=TRUE WHERE job_id=?",id);
                return true;
            });
            if(!valid){release(job,"STALE_CONTEXT");return;}
            Brief renderBrief = prepared.brief().withPrompt(prepared.promptText());
            log.info("stage=ad_render_started jobId={} aspectRatio={}",id,renderBrief.context().aspectRatio());
            stage="GENERATING";
            var output=provider.generate(id,renderBrief,new ImageGenerator.Budget(maxCost-reviewReserve-prepared.promptCost(),job.deadline()));
            if(output==null || output.image()==null || output.costMicros()<0 || output.costMicros()>maxCost-reviewReserve-prepared.promptCost())
                throw new IllegalStateException("Invalid generation result or budget exceeded");
            boolean correctRatio = renderBrief.context().aspectRatio()==null
                    || ImageAspectRatios.matches(output.image(),renderBrief.context().aspectRatio());
            stage="STORING";
            var asset=storage.put(job.brief().context().ownerId(),id,output.image());
            fenced(job,()->store.db.update("UPDATE generation_jobs SET status=?,output_json=?,provider_id=?,spent_micros=? WHERE id=?",
                    correctRatio?"REVIEWING":"GENERATING",store.json.write(asset),output.operationId(),output.costMicros()+reviewReserve+prepared.promptCost(),id));
            if (!correctRatio) {
                release(job,"OUTPUT_REJECTED");return;
            }
            var reviewing=get(id);
            if(reviewing.fence()!=job.fence())throw new SelectionStore.StaleWorker();
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
    private void review(Job job){
        final long reviewReserve=job.brief().generationLimits().reviewReserveMicros();
        if (!job.deadline().isAfter(Instant.now())) {release(job,"FAILED");return;}
        if (!current(job)) {release(job,"STALE_CONTEXT");return;}
        // Review attempts are persisted. Recovery never repeats a possibly billed review blindly.
        if(job.reviewStarted()){
            fenced(job,()->store.db.update("UPDATE generation_jobs SET status='RECONCILIATION_REQUIRED',lease_until=NULL WHERE id=?",job.id()));return;
        }
        fenced(job,()->store.db.update("UPDATE generation_jobs SET review_started=TRUE WHERE id=?",job.id()));
        var asset=store.json.read(job.outputJson(),Asset.class);
        var result=reviewer.review(job.brief().withPrompt(job.promptText()),asset);
        if(result==null || result.costMicros()<0 || result.costMicros()>reviewReserve)throw new IllegalStateException("Output review exceeded budget");
        if(!result.accepted()){release(job,"OUTPUT_REJECTED");return;}
        store.transaction(()->{
            if (!job.deadline().isAfter(Instant.now())) throw new IllegalStateException("Generation deadline exceeded before publication");
            if (!contexts.current(job.brief().context())) throw new IllegalStateException("Product or brand changed during generation");
            store.db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",job.brief().concept().conceptId());
            assertFence(job);
            if(store.db.queryForObject("SELECT COUNT(*) FROM concept_revisions WHERE id=? AND status='APPROVED'",Integer.class,job.brief().concept().revisionId())!=1)
                throw new IllegalStateException("Concept was revoked");
            var rows=store.db.queryForList("SELECT * FROM concept_allocations WHERE id=? AND state='RESERVED' FOR UPDATE",job.allocation());
            if(rows.size()!=1)throw new IllegalStateException("Allocation is no longer reserved");
            UUID evaluation=store.db.queryForObject("SELECT evaluation_id FROM concept_allocations WHERE id=?",UUID.class,job.allocation());
            store.db.update("INSERT INTO creations(id,job_id,owner_id,product_id,concept_id,evaluation_id,asset_json,review_json,created_at,aspect_ratio,image_prompt,prompt_version,quality) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(),job.id(),job.brief().context().ownerId(),job.brief().context().productId(),job.brief().concept().conceptId(),evaluation,
                    job.outputJson(),store.json.write(result),stamp(Instant.now()),job.brief().context().aspectRatio(),job.promptText(),job.promptVersion(),Objects.toString(job.brief().context().quality(),"high"));
            store.db.update("UPDATE concept_allocations SET state='CONSUMED' WHERE id=?",job.allocation());
            store.db.update("UPDATE generation_jobs SET status='SUCCEEDED',lease_until=NULL,spent_micros=spent_micros-?+? WHERE id=?",reviewReserve,result.costMicros(),job.id());return null;
        });
    }
    private void release(Job job,String state){fenced(job,()->{
        store.db.update("DELETE FROM concept_allocation_claims WHERE allocation_id=?",job.allocation());
        store.db.update("UPDATE concept_allocations SET state='RELEASED' WHERE id=? AND state='RESERVED'",job.allocation());
        store.db.update("UPDATE generation_jobs SET status=?,lease_until=NULL WHERE id=?",state,job.id());
    });}
    private boolean current(Job job) {
        return store.transaction(() -> {
            if (!contexts.current(job.brief().context())) return false;
            store.db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",job.brief().concept().conceptId());
            return store.db.queryForObject("SELECT COUNT(*) FROM concept_revisions WHERE id=? AND status='APPROVED'",Integer.class,job.brief().concept().revisionId())==1;
        });
    }
    private void fenced(Job job,Runnable action){store.transaction(()->{assertFence(job);action.run();return null;});}
    private void assertFence(Job job){
        var current=store.db.query("SELECT * FROM generation_jobs WHERE id=? FOR UPDATE",(r,n)->new Object[]{r.getLong("fence"),r.getTimestamp("lease_until"),r.getString("status")},job.id()).get(0);
        if((long)current[0]!=job.fence() || current[1]==null || !((java.sql.Timestamp)current[1]).toInstant().isAfter(Instant.now())
                || !List.of("QUEUED","WAITING_FOR_PROVIDER","PROMPTING","PROMPT_READY","GENERATING","REVIEWING").contains(current[2]))throw new SelectionStore.StaleWorker();
    }
    private Job get(UUID id){return store.db.query("SELECT * FROM generation_jobs WHERE id=?",(r,n)->new Job(id,r.getObject("allocation_id",UUID.class),
            store.json.read(r.getString("brief_json"),Brief.class),r.getString("status"),r.getLong("fence"),r.getTimestamp("deadline").toInstant(),r.getString("output_json"),r.getBoolean("review_started"),r.getString("prompt_text"),r.getLong("prompt_cost_micros"),r.getString("prompt_version")),id).get(0);}
    private record Job(UUID id,UUID allocation,Brief brief,String status,long fence,Instant deadline,String outputJson,boolean reviewStarted,String promptText,long promptCost,String promptVersion){}
}
