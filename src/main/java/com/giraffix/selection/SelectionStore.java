package com.giraffix.selection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static com.giraffix.selection.SelectionModels.*;

@Component
public class SelectionStore {
    public final JdbcTemplate db;
    public final JsonSupport json;
    private final TransactionTemplate tx;
    public static final String ACTIVE="('QUEUED','SCREENING','VERIFYING')";
    public SelectionStore(JdbcTemplate db,JsonSupport json,PlatformTransactionManager tm){this.db=db;this.json=json;tx=new TransactionTemplate(tm);}
    public <T> T transaction(Supplier<T> work){return tx.execute(s->work.get());}
    public record Request(UUID id,UUID owner,UUID product,String bodyHash,Context context,String contextHash,
            SelectionPolicy.Snapshot policy,UUID manual,String status,int rounds,int verifications,int retries,
            long spent,long fence,Instant lease,Instant deadline,UUID selected,Instant created,int directorPromptVersion) {}
    public final RowMapper<Request> requestMapper=(r,n)->mapRequest(r);
    private Request mapRequest(java.sql.ResultSet r) throws java.sql.SQLException { return new Request(r.getObject("id",UUID.class),r.getObject("owner_id",UUID.class),
            r.getObject("product_id",UUID.class),r.getString("body_hash"),json.read(r.getString("context_json"),Context.class),r.getString("context_hash"),
            json.read(r.getString("policy_json"),SelectionPolicy.Snapshot.class),r.getObject("manual_concept_id",UUID.class),r.getString("status"),
            r.getInt("rounds"),r.getInt("verification_calls"),r.getInt("retries"),r.getLong("spent_micros"),r.getLong("fence"),
            instant(r.getTimestamp("lease_until")),instant(r.getTimestamp("deadline")),r.getObject("selected_evaluation_id",UUID.class),instant(r.getTimestamp("created_at")),r.getInt("director_prompt_version")); }
    public Request get(UUID id){return db.query("SELECT * FROM selection_requests WHERE id=?",requestMapper,id).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Selection request not found"));}
    public Request existing(UUID owner,String key){return db.query("SELECT * FROM selection_requests WHERE owner_id=? AND idempotency_key=?",requestMapper,owner,key).stream().findFirst().orElse(null);}
    public Request claim(UUID id){return transaction(()->{
        Instant now=Instant.now();
        if(db.update("UPDATE selection_requests SET fence=fence+1,lease_until=? WHERE id=? AND status IN "+ACTIVE+" AND (lease_until IS NULL OR lease_until<=?)",
                stamp(now.plusSeconds(150)),id,stamp(now))!=1)return null;
        return get(id);
    });}
    public <T> T fenced(Request request,Supplier<T> work){return transaction(()->{
        var current=db.query("SELECT fence,lease_until,status FROM selection_requests WHERE id=? FOR UPDATE",
                (r,n)->new Object[]{r.getLong(1),r.getTimestamp(2),r.getString(3)},request.id()).get(0);
        var lease=(Timestamp)current[1];
        if((long)current[0]!=request.fence() || lease==null || !lease.toInstant().isAfter(Instant.now())
                || !List.of("QUEUED","SCREENING","VERIFYING").contains(current[2]))throw new StaleWorker();
        return work.get();
    });}
    public void finish(Request request,String outcome){fenced(request,()->{db.update("UPDATE selection_requests SET status=?,lease_until=NULL WHERE id=?",outcome,request.id());return null;});}
    public void yield(Request request){fenced(request,()->{db.update("UPDATE selection_requests SET lease_until=NULL WHERE id=?",request.id());return null;});}
    public List<UUID> pending(){return pending(10);}
    public List<UUID> pending(int limit){return db.query("SELECT id FROM selection_requests WHERE status IN "+ACTIVE+" AND (lease_until IS NULL OR lease_until<=?) ORDER BY created_at,id LIMIT ?",
            (r,n)->r.getObject(1,UUID.class),stamp(Instant.now()),limit);}
    public static Timestamp stamp(Instant instant){return Timestamp.from(instant);}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    public static class StaleWorker extends RuntimeException {}
}
