package com.giraffix.selection;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static com.giraffix.selection.SelectionModels.*;
import static com.giraffix.selection.SelectionStore.*;

@Service
public class SelectionWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SelectionWorker.class);
    private final SelectionStore store;
    private final SelectionContextService contexts;
    private final SelectionAssets assets;
    private final ConceptEvaluator evaluator;
    private final ScorePolicy scoring;
    @org.springframework.beans.factory.annotation.Value("${giraffix.generation.max-cost-micros:2000000}") private long generationCost=2000000;
    @org.springframework.beans.factory.annotation.Value("${giraffix.generation.review-reserve-micros:300000}") private long reviewCost=300000;
    @org.springframework.beans.factory.annotation.Value("${giraffix.generation.deadline-seconds:600}") private int generationDeadline=600;
    @org.springframework.beans.factory.annotation.Value("${giraffix.generation.prompt-reserve-micros:400000}") private long promptCost=400000;
    @jakarta.annotation.PostConstruct
    public void validateGenerationLimits(){if(reviewCost<1 || promptCost<1 || generationCost<=reviewCost+promptCost || generationDeadline<1)throw new IllegalArgumentException("Invalid generation limits");}
    public SelectionWorker(SelectionStore store,SelectionContextService contexts,SelectionAssets assets,ConceptEvaluator evaluator,ScorePolicy scoring){
        this.store=store;this.contexts=contexts;this.assets=assets;this.evaluator=evaluator;this.scoring=scoring;
    }
    public void run(UUID id){
        Request request=store.claim(id);if(request==null)return;
        final long claimedFence=request.fence();
        try (var scope=assets.openScope();var operation=org.slf4j.MDC.putCloseable("operationId",id.toString())) {
            // A previous process may have dispatched a call before dying. Never replay it blindly.
            if(count("SELECT COUNT(*) FROM selection_attempts WHERE request_id=? AND status IN ('STARTED','RECEIVED')",id)>0){store.finish(request,"PROVIDER_FAILED");return;}
            if(ConceptPickerRules.enabled(request.policy())){
                runPicker(request);
                var completed=store.get(id);
                log.info("stage=concept_picker_finished requestId={} productId={} status={} rounds={}",
                        id,completed.product(),completed.status(),completed.rounds());
                return;
            }
            while(true){
                request=store.get(id);
                if(request.fence()!=claimedFence)throw new StaleWorker();
                if(!request.deadline().isAfter(Instant.now())){store.finish(request,"NO_MATCH_WITHIN_BUDGET");return;}
                contexts.authorize(request.owner(),request.product());
                var passing=store.db.query("""
                    SELECT e.id,e.result_json,e.revision_id,e.numerator,r.analysis_json FROM concept_evaluations e
                    JOIN concept_revisions r ON r.id=e.revision_id
                    WHERE e.request_id=? AND e.stage='SCREEN' AND e.passes=TRUE AND e.verification_status='PENDING'
                    ORDER BY e.numerator DESC,e.revision_id
                    """,(r,n)->new Screen(r.getObject(1,UUID.class),store.json.read(r.getString(2),Evaluation.class),r.getObject(3,UUID.class),
                        r.getInt(4),store.json.mapper.readTree(r.getString(5)).at("/concept/mechanism").asText()),id);
                var mechanismHistory=mechanismHistory(request);
                passing=new ArrayList<>(passing);
                passing.sort(Comparator.comparingInt(Screen::numerator).reversed()
                        .thenComparing(s->mechanismHistory.getOrDefault(s.mechanism(),Instant.MIN))
                        .thenComparing(s->s.value().conceptId()));
                if(!passing.isEmpty()){
                    var screen=passing.get(0);var candidate=candidate(screen.revision());
                    if(candidate==null){markSkipped(request,screen.id());continue;}
                    candidate=withFingerprint(candidate);
                    var result=call(request,"VERIFY",List.of(candidate),screen.value());
                    if(result==null)return;
                    Request current=request;Candidate verified=candidate;
                    UUID evaluation=store.fenced(current,()->save(current,null,verified,result.candidates().get(0),"VERIFY",screen.id()));
                    if(scoring.score(result.candidates().get(0),request.policy()).passes()){
                        if(reserve(request,verified,evaluation,result.candidates().get(0)))return;
                    }
                    if(request.manual()!=null){store.finish(request,"NO_MATCH_WITHIN_BUDGET");return;}
                    continue;
                }
                var batches=store.db.query("SELECT id,candidates_json FROM selection_batches WHERE request_id=? AND completed=FALSE ORDER BY round",
                        (r,n)->new Batch(r.getObject(1,UUID.class),store.json.read(r.getString(2),Candidate[].class)),id);
                Batch batch;
                if(!batches.isEmpty())batch=batches.get(0);
                else {
                    if(request.rounds()>=request.policy().maxRounds()){store.finish(request,"NO_MATCH_WITHIN_BUDGET");return;}
                    List<Candidate> sampled=sample(request);
                    if(sampled.isEmpty()){store.finish(request,emptyOutcome(request));return;}
                    var prepared=sampled.stream().map(this::withFingerprint).toList();
                    Request current=request;
                    batch=store.fenced(current,()->{
                        UUID batchId=UUID.randomUUID();
                        store.db.update("INSERT INTO selection_batches(id,request_id,round,candidates_json) VALUES(?,?,?,?)",
                                batchId,id,current.rounds()+1,store.json.write(prepared));
                        for(var item:prepared)store.db.update("INSERT INTO selection_seen(request_id,concept_id) VALUES(?,?)",id,item.conceptId());
                        store.db.update("UPDATE selection_requests SET rounds=rounds+1,status='SCREENING' WHERE id=?",id);
                        return new Batch(batchId,prepared.toArray(Candidate[]::new));
                    });
                }
                var result=call(request,"SCREEN",Arrays.asList(batch.candidates()),null);
                if(result==null)return;
                Request current=request;Batch screened=batch;
                store.fenced(current,()->{
                    for(var value:result.candidates()){
                        Candidate candidate=Arrays.stream(screened.candidates()).filter(c->c.conceptId().equals(value.conceptId())).findFirst().orElseThrow();
                        save(current,screened.id(),candidate,value,"SCREEN",null);
                    }
                    store.db.update("UPDATE selection_batches SET completed=TRUE WHERE id=?",screened.id());
                    return null;
                });
            }
        } catch(StaleWorker ignored) {
            // Another worker owns the request. It alone may publish results.
        } catch(ResponseStatusException ex){
            log.warn("stage=selection_failed requestId={} status={}",id,ex.getStatusCode().value());
            finishSafely(request,ex.getStatusCode()==HttpStatus.CONFLICT?"STALE_CONTEXT":ex.getStatusCode()==HttpStatus.NOT_FOUND?"STALE_CONTEXT":"PROVIDER_FAILED");
        } catch(RuntimeException ex){log.error("stage=selection_failed requestId={} errorType={}",id,ex.getClass().getSimpleName());finishSafely(request,"PROVIDER_FAILED");}
    }

    private void runPicker(Request claimed) {
        while (true) {
            Request request = store.get(claimed.id());
            if (request.fence() != claimed.fence()) throw new StaleWorker();
            if (!request.deadline().isAfter(Instant.now())) {
                store.finish(request, "NO_MATCH_WITHIN_BUDGET");
                return;
            }
            contexts.authorize(request.owner(), request.product());
            // A completed batch may already exist after restart. Choose before drawing again.
            if (reservePickerWinner(request, false)) return;
            var unfinished = store.db.query("SELECT id,candidates_json FROM selection_batches WHERE request_id=? AND completed=FALSE ORDER BY round",
                    (r,n) -> new Batch(r.getObject(1, UUID.class), store.json.read(r.getString(2), Candidate[].class)), request.id());
            Batch batch;
            if (!unfinished.isEmpty()) {
                batch = unfinished.get(0);
            } else {
                if (request.rounds() >= request.policy().maxRounds() || (request.manual() != null && request.rounds() > 0)) {
                    if (!reservePickerWinner(request, true)) store.finish(request, "NO_MATCH_WITHIN_BUDGET");
                    return;
                }
                List<Candidate> candidates = sample(request);
                if (candidates.isEmpty()) {
                    // Small libraries can run out before three complete batches.
                    if (!reservePickerWinner(request, true)) store.finish(request, emptyOutcome(request));
                    return;
                }
                batch = store.fenced(request, () -> {
                    UUID batchId = UUID.randomUUID();
                    store.db.update("INSERT INTO selection_batches(id,request_id,round,candidates_json) VALUES(?,?,?,?)",
                            batchId, request.id(), request.rounds() + 1, store.json.write(candidates));
                    for (var candidate : candidates) store.db.update("INSERT INTO selection_seen(request_id,concept_id) VALUES(?,?)",
                            request.id(), candidate.conceptId());
                    store.db.update("UPDATE selection_requests SET rounds=rounds+1,status='SCREENING' WHERE id=?", request.id());
                    return new Batch(batchId, candidates.toArray(Candidate[]::new));
                });
            }
            int round = store.get(request.id()).rounds();
            log.info("stage=concept_picker_batch_started requestId={} productId={} batchId={} round={} minimumScore={} conceptIds={}",
                    request.id(),request.product(),batch.id(),round,request.policy().threshold(),
                    store.json.write(Arrays.stream(batch.candidates()).map(Candidate::conceptId).toList()));
            Evaluated result = call(request, "SCREEN", Arrays.asList(batch.candidates()), null);
            if (result == null) return;
            Batch evaluated = batch;
            store.fenced(request, () -> {
                // Exclusions and evaluations only publish for the exact captured product context.
                // The product lock also serializes exclusions from concurrent requests.
                if (!contexts.current(request.context())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Product changed during concept picking");
                for (var value : result.candidates()) {
                    Candidate candidate = Arrays.stream(evaluated.candidates()).filter(c -> c.conceptId().equals(value.conceptId())).findFirst().orElseThrow();
                    save(request, evaluated.id(), candidate, value, "SCREEN", null);
                }
                store.db.update("UPDATE selection_batches SET completed=TRUE WHERE id=?", evaluated.id());
                return null;
            });
            // Explicit projection logs validated, committed results without private text or images.
            logPickerResults(request, batch, round, result);
        }
    }

    private void logPickerResults(Request request, Batch batch, int round, Evaluated result) {
        var scores = result.candidates().stream().map(value -> new PickerLogScore(
                value.conceptId(),value.compatibilityScore(),value.classification())).toList();
        log.info("stage=concept_picker_batch_completed requestId={} productId={} batchId={} round={} minimumScore={} evaluations={} notSuitableConceptIds={} classifiedNotSuitableConceptIds={}",
                request.id(),request.product(),batch.id(),round,request.policy().threshold(),store.json.write(scores),
                store.json.write(result.notSuitableConceptIds()),store.json.write(result.candidates().stream()
                        .filter(value -> "NOT_SUITABLE".equals(value.classification())).map(Evaluation::conceptId).toList()));
    }

    private boolean reservePickerWinner(Request request, boolean fallback) {
        String qualifying = fallback ? "" : " AND e.classification='STRONG' AND e.compatibility_score>=?";
        List<Object> args = new ArrayList<>(List.of(request.id(), request.owner(), request.product()));
        if (!fallback) args.add(request.policy().threshold());
        var ranked = store.db.query("""
            SELECT e.id,e.revision_id,e.result_json FROM concept_evaluations e
            JOIN concept_revisions r ON r.id=e.revision_id
            WHERE e.request_id=? AND e.stage='SCREEN' AND e.compatibility_score IS NOT NULL AND r.status='APPROVED'
            AND NOT EXISTS(SELECT 1 FROM concept_allocation_claims a WHERE a.owner_id=? AND a.product_id=? AND a.concept_id=r.concept_id)
            """ + qualifying + " ORDER BY e.compatibility_score DESC,r.concept_id",
                (r,n) -> new PickerWinner(r.getObject(1, UUID.class), r.getObject(2, UUID.class), store.json.read(r.getString(3), Evaluation.class)), args.toArray());
        for (var winner : ranked) {
            Candidate candidate = candidate(winner.revision());
            if (candidate != null && reserve(request, withFingerprint(candidate), winner.evaluation(), winner.value(), fallback)) {
                if ("SELECTED".equals(store.get(request.id()).status()))
                    log.info("stage=concept_picker_selected requestId={} productId={} conceptId={} compatibilityScore={} classification={} fallbackUsed={}",
                            request.id(),request.product(),winner.value().conceptId(),winner.value().compatibilityScore(),winner.value().classification(),fallback);
                return true;
            }
        }
        return false;
    }

    private Evaluated call(Request request,String stage,List<Candidate> candidates,Evaluation exact){
        String inputHash=store.json.hash(List.of(request.context(),candidates,exact==null?"":exact,request.policy()));
        var cached=store.db.query("SELECT result_json FROM selection_attempts WHERE request_id=? AND stage=? AND input_hash=? AND status='COMPLETED' ORDER BY created_at DESC LIMIT 1",
                (r,n)->r.getString(1),request.id(),stage,inputHash);
        if(!cached.isEmpty()){
            var result=store.json.read(cached.get(0),Evaluated.class);
            validate(request,candidates,exact,result);
            return result;
        }
        while(true){
            Request latest=store.get(request.id());
            if(!latest.deadline().isAfter(Instant.now()) || latest.spent()+latest.policy().maxAttemptCostMicros()>latest.policy().maxCostMicros()
                    || (stage.equals("VERIFY") && latest.verifications()>=latest.policy().maxVerificationCalls())){
                store.finish(request,"NO_MATCH_WITHIN_BUDGET");return null;
            }
            UUID attempt=store.fenced(request,()->{
                UUID attemptId=UUID.randomUUID();
                store.db.update("INSERT INTO selection_attempts(id,request_id,stage,status,reserved_micros,input_hash,created_at,model_snapshot) VALUES(?,?,?,'STARTED',?,?,?,?)",
                        attemptId,request.id(),stage,latest.policy().maxAttemptCostMicros(),inputHash,stamp(Instant.now()),request.policy().model());
                store.db.update("UPDATE selection_requests SET spent_micros=spent_micros+?,verification_calls=verification_calls+?,status=? WHERE id=?",
                        latest.policy().maxAttemptCostMicros(),stage.equals("VERIFY")?1:0,stage.equals("VERIFY")?"VERIFYING":"SCREENING",request.id());
                return attemptId;
            });
            try {
                var result=evaluator.evaluate(request.context(),candidates,exact,request.policy());
                if(result==null || result.usage()==null || result.usage().inputTokens()<0 || result.usage().outputTokens()<0)throw new IllegalArgumentException("Missing usage");
                long cost=Math.addExact(Math.multiplyExact(result.usage().inputTokens(),request.policy().inputRate()),
                        Math.multiplyExact(result.usage().outputTokens(),request.policy().outputRate())+999999)/1000000;
                store.fenced(request,()->{
                    store.db.update("UPDATE selection_attempts SET status='RECEIVED',actual_micros=?,input_tokens=?,output_tokens=?,provider_id=?,result_json=? WHERE id=?",
                            cost,result.usage().inputTokens(),result.usage().outputTokens(),result.usage().providerId(),store.json.write(result),attempt);
                    store.db.update("UPDATE selection_requests SET spent_micros=spent_micros-?+? WHERE id=?",request.policy().maxAttemptCostMicros(),cost,request.id());return null;
                });
                if(cost>request.policy().maxAttemptCostMicros() || result.usage().inputTokens()>request.policy().maxInputTokens()
                        || result.usage().outputTokens()>request.policy().maxOutputTokens()){
                    store.finish(request,"NO_MATCH_WITHIN_BUDGET");return null;
                }
                validate(request,candidates,exact,result);
                store.fenced(request,()->{store.db.update("UPDATE selection_attempts SET status='COMPLETED' WHERE id=?",attempt);return null;});
                return result;
            } catch(StaleWorker ex){throw ex;}
            catch(RuntimeException ex){
                log.warn("stage=selection_provider_attempt_failed requestId={} attemptId={} errorType={}",request.id(),attempt,ex.getClass().getSimpleName());
                store.fenced(request,()->{
                    // Unknown usage keeps the conservative reservation. Do not refund timeouts.
                    store.db.update("UPDATE selection_attempts SET status=CASE WHEN status IN ('RECEIVED','COMPLETED') THEN 'INVALID' ELSE 'FAILED_UNKNOWN_COST' END WHERE id=?",attempt);return null;
                });
                if(ex instanceof ResponseStatusException || ex instanceof ClaudeSelectionEvaluator.EnvelopeExceeded
                        || ex instanceof org.springframework.web.client.ResourceAccessException
                        || store.get(request.id()).retries()>=request.policy().maxRetries()){
                    store.finish(request,ex instanceof ResponseStatusException response && response.getStatusCode()==HttpStatus.CONFLICT?"STALE_CONTEXT":"PROVIDER_FAILED");return null;
                }
                store.fenced(request,()->{store.db.update("UPDATE selection_requests SET retries=retries+1 WHERE id=?",request.id());return null;});
            }
        }
    }
    private void validate(Request request,List<Candidate> candidates,Evaluation exact,Evaluated result){
        if(ConceptPickerRules.enabled(request.policy())){
            ConceptPickerRules.validate(candidates,result,request.policy().threshold());
            return;
        }
        if(result.candidates()==null || result.candidates().size()!=candidates.size())throw new IllegalArgumentException("Missing candidates");
        Set<UUID> seen=new HashSet<>();
        for(var value:result.candidates()){
            var card=candidates.stream().filter(c->c.conceptId().equals(value.conceptId())).findFirst().orElseThrow();
            if(!seen.add(value.conceptId()) || value.conceptVersion()!=card.revision())throw new IllegalArgumentException("Duplicate or stale candidate");
            scoring.score(value,request.policy());
            if(exact!=null && !exact.adaptation().equals(value.adaptation()))throw new IllegalArgumentException("Verification changed the adaptation");
            var evidence=store.json.mapper.createObjectNode();
            evidence.set("product",store.json.mapper.readTree(request.context().factsJson()));
            evidence.set("concept",store.json.mapper.readTree(card.analysisJson()).path("concept"));
            evidence.put("intent",request.context().intent());evidence.put("format",request.context().format());
            evidence.put("controls",Objects.toString(request.context().controls(),""));
            for(var reason:value.reasons())for(String ref:reason.evidenceRefs()){
                if(ref==null || ref.length()>200 || !ref.startsWith("/") || evidence.at(ref).isMissingNode() || evidence.at(ref).isNull())
                    throw new IllegalArgumentException("Unresolved evidence reference");
            }
        }
    }
    private UUID save(Request request,UUID batch,Candidate candidate,Evaluation value,String stage,UUID screen){
        if(ConceptPickerRules.enabled(request.policy()))return savePickerEvaluation(request,batch,candidate,value);
        var verdict=scoring.score(value,request.policy());UUID id=UUID.randomUUID();
        store.db.update("INSERT INTO concept_evaluations(id,request_id,batch_id,revision_id,stage,result_json,numerator,passes,created_at,concept_fidelity,product_fidelity,intent_fit,visual_clarity,renderability) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,request.id(),batch,candidate.revisionId(),stage,store.json.write(value),verdict.numerator(),verdict.passes(),stamp(Instant.now()),
                value.ratings().conceptFidelity(),value.ratings().productFidelity(),value.ratings().intentFit(),value.ratings().visualClarity(),value.ratings().renderability());
        if(screen!=null)store.db.update("UPDATE concept_evaluations SET verified_evaluation_id=?,verification_status='DONE' WHERE id=?",id,screen);
        if(value.conceptLevelMismatch() && verdict.numerator()!=null && verdict.numerator()<request.policy().lowScoreThreshold()*4
                && value.uncertainties().isEmpty() && value.reasons().stream().anyMatch(r->r.dimension().equals("conceptFidelity") && r.evidenceRefs().stream().anyMatch(ref->ref.startsWith("/concept/preserve/")))){
            // Serialize projection replacement across different requests with the same owner/context.
            store.db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",request.owner());
            String policyHash=store.json.hash(request.policy());
            store.db.update("DELETE FROM concept_match_cache WHERE owner_id=? AND context_hash=? AND revision_id=? AND policy_hash=?",request.owner(),request.contextHash(),candidate.revisionId(),policyHash);
            store.db.update("INSERT INTO concept_match_cache(owner_id,context_hash,revision_id,policy_hash,evaluation_id,expires_at) VALUES(?,?,?,?,?,?)",
                    request.owner(),request.contextHash(),candidate.revisionId(),policyHash,id,stamp(Instant.now().plusSeconds(request.policy().cooldownDays()*86400L)));
        }
        return id;
    }

    private UUID savePickerEvaluation(Request request, UUID batch, Candidate candidate, Evaluation value) {
        UUID id = UUID.randomUUID();
        store.db.update("""
            INSERT INTO concept_evaluations(id,request_id,batch_id,revision_id,stage,result_json,numerator,passes,
                compatibility_score,classification,created_at) VALUES(?,?,?,?,'SCREEN',?,?,?,?,?,?)
            """, id, request.id(), batch, candidate.revisionId(), store.json.write(value), value.compatibilityScore() * 4,
                ConceptPickerRules.qualifies(value, request.policy().threshold()), value.compatibilityScore(), value.classification(), stamp(Instant.now()));
        if (value.compatibilityScore() < request.policy().threshold()) {
            // The caller holds the product lock. The primary key enforces product/concept isolation.
            store.db.update("""
                INSERT INTO product_concept_exclusions(product_id,concept_id,revision_id,compatibility_score,classification,
                    minimum_score,request_id,created_at)
                SELECT ?,?,?,?,?,?,?,? WHERE NOT EXISTS(
                    SELECT 1 FROM product_concept_exclusions WHERE product_id=? AND concept_id=?)
                """, request.product(), candidate.conceptId(), candidate.revisionId(), value.compatibilityScore(), value.classification(),
                    request.policy().threshold(), request.id(), stamp(Instant.now()), request.product(), candidate.conceptId());
        }
        return id;
    }

    private boolean reserve(Request request,Candidate candidate,UUID evaluation,Evaluation value){
        return reserve(request,candidate,evaluation,value,false);
    }
    private boolean reserve(Request request,Candidate candidate,UUID evaluation,Evaluation value,boolean fallback){
        return store.fenced(request,()->{
            if(!request.deadline().isAfter(Instant.now())){store.db.update("UPDATE selection_requests SET status='NO_MATCH_WITHIN_BUDGET',lease_until=NULL WHERE id=?",request.id());return true;}
            if(!contexts.current(request.context())){store.db.update("UPDATE selection_requests SET status='STALE_CONTEXT',lease_until=NULL WHERE id=?",request.id());return true;}
            store.db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",candidate.conceptId());
            if(count("SELECT COUNT(*) FROM concept_revisions WHERE id=? AND status='APPROVED'",candidate.revisionId())!=1)return false;
            if(count("SELECT COUNT(*) FROM concept_allocation_claims WHERE owner_id=? AND product_id=? AND concept_id=?",request.owner(),request.product(),candidate.conceptId())>0)return false;
            UUID allocation=UUID.randomUUID(),job=UUID.randomUUID();var now=Instant.now();
            store.db.update("INSERT INTO concept_allocations(id,request_id,owner_id,product_id,concept_id,evaluation_id,state,created_at) VALUES(?,?,?,?,?,?,'RESERVED',?)",
                    allocation,request.id(),request.owner(),request.product(),candidate.conceptId(),evaluation,stamp(now));
            store.db.update("INSERT INTO concept_allocation_claims(owner_id,product_id,concept_id,allocation_id) VALUES(?,?,?,?)",request.owner(),request.product(),candidate.conceptId(),allocation);
            // Normalize only the generation brief; preserve selection snapshots and idempotency hashes.
            Context context = request.context();
            Context generationContext = new Context(context.ownerId(), context.productId(), context.factsJson(),
                    context.intent(), context.format(), context.controls(), context.images(),
                    Objects.toString(context.aspectRatio(), "1:1"), context.brandLogo(), Objects.toString(context.quality(), "high"));
            store.db.update("INSERT INTO generation_jobs(id,allocation_id,brief_json,status,deadline,created_at,director_prompt_version) VALUES(?,?,?,'QUEUED',?,?,?)",
                    job,allocation,store.json.write(new Brief(generationContext,candidate,value,request.policy(),
                        new GenerationLimits(generationCost,reviewCost,generationDeadline,promptCost))),stamp(now.plusSeconds(generationDeadline)),stamp(now),request.directorPromptVersion());
            store.db.update("UPDATE selection_requests SET status='SELECTED',selected_evaluation_id=?,fallback_used=?,lease_until=NULL WHERE id=?",evaluation,fallback,request.id());
            return true;
        });
    }
    private List<Candidate> sample(Request request){
        String manual=request.manual()==null?"":" AND r.concept_id=?";
        String exclusions=ConceptPickerRules.enabled(request.policy())?
                " AND NOT EXISTS(SELECT 1 FROM product_concept_exclusions x WHERE x.product_id=? AND x.concept_id=r.concept_id)":"";
        List<Object> args=new ArrayList<>(List.of(request.owner(),request.product(),request.id(),request.owner(),request.contextHash(),store.json.hash(request.policy()),stamp(Instant.now())));
        if(ConceptPickerRules.enabled(request.policy()))args.add(request.product());
        if(request.manual()!=null)args.add(request.manual());args.add(request.manual()==null?request.policy().batchSize():1);
        return store.db.query("""
            SELECT r.id,r.concept_id,r.revision,r.analysis_json,c.image_bucket,c.image_key,c.image_media_type
            FROM concept_revisions r JOIN concepts c ON c.id=r.concept_id
            WHERE r.status='APPROVED'
            AND NOT EXISTS(SELECT 1 FROM concept_allocation_claims a WHERE a.owner_id=? AND a.product_id=? AND a.concept_id=r.concept_id)
            AND NOT EXISTS(SELECT 1 FROM selection_seen s WHERE s.request_id=? AND s.concept_id=r.concept_id)
            AND NOT EXISTS(SELECT 1 FROM concept_match_cache m WHERE m.owner_id=? AND m.context_hash=? AND m.policy_hash=? AND m.revision_id=r.id AND m.expires_at>?)
            """+exclusions+manual+" ORDER BY RANDOM() LIMIT ?",this::mapCandidate,args.toArray());
    }
    private String emptyOutcome(Request request){
        if(count("SELECT COUNT(*) FROM selection_seen WHERE request_id=?",request.id())>0)return "NO_MATCH_WITHIN_BUDGET";
        String manual=request.manual()==null?"":" AND r.concept_id='"+request.manual()+"'";
        long available=count("SELECT COUNT(*) FROM concept_revisions r WHERE r.status='APPROVED'"+manual);
        long consumed=count("SELECT COUNT(*) FROM concept_revisions r JOIN concept_allocation_claims c ON c.concept_id=r.concept_id JOIN concept_allocations a ON a.id=c.allocation_id WHERE r.status='APPROVED' AND c.owner_id=? AND c.product_id=? AND a.state='CONSUMED'"+manual,request.owner(),request.product());
        if(available==0 || consumed==available)return "POOL_EXHAUSTED";
        long reserved=count("SELECT COUNT(*) FROM concept_allocations a JOIN concept_revisions r ON r.concept_id=a.concept_id WHERE r.status='APPROVED' AND a.owner_id=? AND a.product_id=? AND a.state='RESERVED'"+manual,request.owner(),request.product());
        return reserved+consumed>=available?"SELECTION_BUSY":"TEMPORARILY_SUPPRESSED";
    }
    private Candidate candidate(UUID revision){
        return store.db.query("SELECT r.id,r.concept_id,r.revision,r.analysis_json,c.image_bucket,c.image_key,c.image_media_type FROM concept_revisions r JOIN concepts c ON c.id=r.concept_id WHERE r.id=? AND r.status='APPROVED'",
                this::mapCandidate,revision).stream().findFirst().orElse(null);
    }
    private Candidate mapCandidate(java.sql.ResultSet r,int row) throws java.sql.SQLException {
        return new Candidate(r.getObject(2,UUID.class),r.getObject(1,UUID.class),r.getInt(3),r.getString(4),new Asset(r.getString(5),r.getString(6),r.getString(7),null));
    }
    private Candidate withFingerprint(Candidate candidate){return new Candidate(candidate.conceptId(),candidate.revisionId(),candidate.revision(),candidate.analysisJson(),assets.fingerprint(candidate.reference()));}
    private void markSkipped(Request request,UUID screen){store.fenced(request,()->{store.db.update("UPDATE concept_evaluations SET verification_status='INELIGIBLE' WHERE id=?",screen);return null;});}
    private Map<String,Instant> mechanismHistory(Request request){
        Map<String,Instant> result=new HashMap<>();
        store.db.query("SELECT r.analysis_json,a.created_at FROM concept_allocations a JOIN concept_evaluations e ON e.id=a.evaluation_id JOIN concept_revisions r ON r.id=e.revision_id WHERE a.owner_id=? AND a.product_id=? AND a.state='CONSUMED'",
                (org.springframework.jdbc.core.RowCallbackHandler)row->{String mechanism=store.json.mapper.readTree(row.getString(1)).at("/concept/mechanism").asText();
                    result.merge(mechanism,row.getTimestamp(2).toInstant(),(a,b)->a.isAfter(b)?a:b);},request.owner(),request.product());
        return result;
    }
    private long count(String sql,Object... args){return store.db.queryForObject(sql,Long.class,args);}
    private void finishSafely(Request request,String status){try{store.finish(request,status);}catch(StaleWorker ignored){}}
    private record Screen(UUID id,Evaluation value,UUID revision,int numerator,String mechanism){}
    private record Batch(UUID id,Candidate[] candidates){}
    private record PickerWinner(UUID evaluation,UUID revision,Evaluation value){}
    private record PickerLogScore(UUID conceptId,Integer compatibilityScore,String classification){}
}
