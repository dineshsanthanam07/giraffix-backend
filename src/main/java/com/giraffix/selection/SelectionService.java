package com.giraffix.selection;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static com.giraffix.selection.SelectionModels.*;
import static com.giraffix.selection.SelectionStore.stamp;

@Service
public class SelectionService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SelectionService.class);
    private final SelectionStore store;
    private final SelectionContextService contexts;
    private final SelectionPolicy policy;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.giraffix.prompt.PromptTemplates prompts;
    @org.springframework.beans.factory.annotation.Autowired
    private com.giraffix.usage.UsageService usage;
    public SelectionService(SelectionStore store,SelectionContextService contexts,SelectionPolicy policy){this.store=store;this.contexts=contexts;this.policy=policy;}
    public Status start(String subject,String key,Start body){
        return status(subject,startId(subject,key,body));
    }
    public UUID startId(String subject,String key,Start body){
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A 1-128 character Idempotency-Key is required");
        UUID owner=contexts.owner(subject);
        contexts.authorize(owner,body.productId());
        String hash=store.json.hash(body);
        var existing=store.existing(owner,key);
        if(existing!=null){same(existing,hash);log.debug("stage=selection_reused selectionId={}",existing.id());return existing.id();}
        if(body.conceptId()!=null && store.db.queryForObject("SELECT COUNT(*) FROM concept_revisions WHERE concept_id=? AND status='APPROVED'",Integer.class,body.conceptId())!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Approved concept not found");
        Context context=contexts.build(owner,body);
        UUID id=store.transaction(()->{
            // Serializes concurrent reuse of an owner's idempotency key, even across different products.
            store.db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owner);
            var previous=store.existing(owner,key);
            if(previous!=null){same(previous,hash);return previous.id();}
            if(!contexts.current(context))throw new ResponseStatusException(HttpStatus.CONFLICT,"Product changed while capturing context");
            var requestId=UUID.randomUUID();var now=Instant.now();
            var snapshot=policy.snapshot();
            UUID brand=store.db.queryForObject("SELECT brand_id FROM products WHERE id=?",UUID.class,body.productId());
            usage.trackAd(requestId,owner,body.productId(),brand,snapshot.maxCostMicros());
            store.db.update("""
                INSERT INTO selection_requests(id,owner_id,product_id,idempotency_key,body_hash,context_json,context_hash,policy_json,
                    manual_concept_id,status,deadline,created_at,director_prompt_version) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?)
                """,requestId,owner,body.productId(),key,hash,store.json.write(context),store.json.hash(context),store.json.write(snapshot),
                    body.conceptId(),stamp(now.plusSeconds(policy.deadlineSeconds)),stamp(now),
                    prompts==null?1:prompts.current(com.giraffix.prompt.PromptTemplates.Kind.DIRECTOR).version());
            return requestId;
        });
        log.info("stage=selection_queued selectionId={} productId={}",id,body.productId());
        return id;
    }
    private void same(SelectionStore.Request request,String hash){if(!request.bodyHash().equals(hash))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key was already used for different input");}
    public Status status(String subject,UUID id){
        return store.db.query("""
                SELECT s.status,s.rounds,s.verification_calls,s.spent_micros,s.created_at,s.fallback_used,
                       e.result_json,g.id AS job_id,g.status AS job_status
                FROM selection_requests s
                JOIN users u ON u.id=s.owner_id
                JOIN products p ON p.id=s.product_id
                JOIN brands b ON b.id=p.brand_id AND b.user_id=u.id
                LEFT JOIN concept_evaluations e ON e.id=s.selected_evaluation_id
                LEFT JOIN concept_allocations a ON a.request_id=s.id
                LEFT JOIN generation_jobs g ON g.allocation_id=a.id
                WHERE s.id=? AND u.google_id=?
                """, (r,n)->new Status(id,r.getString("status"),r.getInt("rounds"),r.getInt("verification_calls"),
                r.getLong("spent_micros"),r.getString("result_json")==null?null:store.json.read(r.getString("result_json"),Evaluation.class),
                r.getObject("job_id",UUID.class),r.getString("job_status"),r.getTimestamp("created_at").toInstant(),r.getBoolean("fallback_used")),
                id,subject).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Selection request not found"));
    }
}
