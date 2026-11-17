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
    private final SelectionStore store;
    private final SelectionContextService contexts;
    private final SelectionPolicy policy;
    public SelectionService(SelectionStore store,SelectionContextService contexts,SelectionPolicy policy){this.store=store;this.contexts=contexts;this.policy=policy;}
    public Status start(String subject,String key,Start body){
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A 1-128 character Idempotency-Key is required");
        UUID owner=contexts.owner(subject);
        contexts.authorize(owner,body.productId());
        String hash=store.json.hash(body);
        var existing=store.existing(owner,key);
        if(existing!=null){same(existing,hash);return status(subject,existing.id());}
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
            store.db.update("""
                INSERT INTO selection_requests(id,owner_id,product_id,idempotency_key,body_hash,context_json,context_hash,policy_json,
                    manual_concept_id,status,deadline,created_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?)
                """,requestId,owner,body.productId(),key,hash,store.json.write(context),store.json.hash(context),store.json.write(policy.snapshot()),
                    body.conceptId(),stamp(now.plusSeconds(policy.deadlineSeconds)),stamp(now));
            return requestId;
        });
        return status(subject,id);
    }
    private void same(SelectionStore.Request request,String hash){if(!request.bodyHash().equals(hash))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key was already used for different input");}
    public Status status(String subject,UUID id){
        var request=store.get(id);UUID owner=contexts.owner(subject);
        if(!owner.equals(request.owner()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Selection request not found");
        contexts.authorize(owner,request.product());
        Evaluation selected=request.selected()==null?null:store.json.read(store.db.queryForObject("SELECT result_json FROM concept_evaluations WHERE id=?",String.class,request.selected()),Evaluation.class);
        var jobs=store.db.query("SELECT g.id,g.status FROM generation_jobs g JOIN concept_allocations a ON a.id=g.allocation_id WHERE a.request_id=?",
                (r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2)},id);
        return new Status(id,request.status(),request.rounds(),request.verifications(),request.spent(),selected,
                jobs.isEmpty()?null:(UUID)jobs.get(0)[0],jobs.isEmpty()?null:(String)jobs.get(0)[1],request.created(),
                Boolean.TRUE.equals(store.db.queryForObject("SELECT fallback_used FROM selection_requests WHERE id=?",Boolean.class,id)));
    }
}
