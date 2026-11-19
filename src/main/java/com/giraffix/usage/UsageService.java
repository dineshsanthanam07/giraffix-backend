package com.giraffix.usage;

import com.giraffix.selection.SelectionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Observational analytics only: no quota, spending cap, admission gate, or billing action. */
@Service
public class UsageService {
    public enum Kind { AD, PRODUCT_ANALYSIS, CONCEPT_ANALYSIS }
    public record Summary(long requests,long completedRequests,long failedRequests,long activeRequests,long uncertainUsageOperations,
                          long recordedInputTokens,long recordedOutputTokens,long incompleteTokenOperations,
                          long accountedCostMicros,long pendingEstimateMicros,long totalEstimatedExposureMicros){}
    public record Operation(UUID id,Kind kind,UUID ownerId,UUID productId,UUID brandId,String state,String outcome,String status,
                            long accountedCostMicros,long totalEstimatedExposureMicros,long plannedCostMicros,
                            long recordedInputTokens,long recordedOutputTokens,boolean tokenDataComplete,Instant createdAt){}
    public record UserUsage(UUID userId,String fullName,String email,Summary usage){}
    public record DailyUsage(LocalDate day,Summary usage){}
    public record ModelUsage(String model,String stage,long recordedCalls,long recordedInputTokens,long recordedOutputTokens,
                             long incompleteTokenCalls,long estimatedCostMicros){}
    public record ModelCall(String stage,String model,String providerId,Long inputTokens,Long outputTokens,Long estimatedCostMicros){}
    private static final ThreadLocal<Call> CURRENT=new ThreadLocal<>();
    private final SelectionStore store;
    private final long generationEstimate,analysisEstimate,inputRate,outputRate;

    public UsageService(SelectionStore store,
            @Value("${giraffix.generation.max-cost-micros:2000000}") long generationEstimate,
            @Value("${giraffix.usage.analysis-estimate-micros:350000}") long analysisEstimate,
            @Value("${giraffix.usage.analysis-input-micros-per-million:2000000}") long inputRate,
            @Value("${giraffix.usage.analysis-output-micros-per-million:10000000}") long outputRate){
        if(generationEstimate<0 || analysisEstimate<0 || inputRate<0 || outputRate<0)
            throw new IllegalArgumentException("Usage cost estimates/rates cannot be negative");
        this.store=store;this.generationEstimate=generationEstimate;this.analysisEstimate=analysisEstimate;
        this.inputRate=inputRate;this.outputRate=outputRate;
    }

    /** Adopt historical ads once. The mutex is only for replica startup, not user requests. */
    @jakarta.annotation.PostConstruct public void backfill(){
        while(Boolean.TRUE.equals(store.transaction(()->{
            store.db.queryForList("SELECT name FROM usage_tracking_locks WHERE name='BACKFILL' FOR UPDATE");
            var missing=store.db.queryForList("""
                    SELECT s.id,s.owner_id,s.product_id,s.policy_json,s.created_at,p.brand_id,c.brand_id AS saved_brand,g.brief_json
                    FROM selection_requests s
                    LEFT JOIN products p ON p.id=s.product_id
                    LEFT JOIN concept_allocations a ON a.request_id=s.id
                    LEFT JOIN generation_jobs g ON g.allocation_id=a.id
                    LEFT JOIN creations c ON c.job_id=g.id
                    WHERE NOT EXISTS(SELECT 1 FROM usage_operations u WHERE u.id=s.id) LIMIT 100
                    """);
            for(var row:missing){
                var policy=store.json.mapper.readTree((String)row.get("policy_json"));
                long estimate=generationEstimate;
                if(row.get("brief_json")!=null)estimate=store.json.mapper.readTree((String)row.get("brief_json"))
                        .path("generationLimits").path("maxCostMicros").asLong(generationEstimate);
                UUID operation=(UUID)row.get("id");
                insert(operation,Kind.AD,(UUID)row.get("owner_id"),(UUID)row.get("product_id"),
                        (UUID)(row.get("brand_id")==null?row.get("saved_brand"):row.get("brand_id")),
                        Math.addExact(policy.path("maxCostMicros").asLong(0),estimate),instant(row.get("created_at")));
                store.db.update("UPDATE selection_attempts SET model_snapshot=? WHERE request_id=? AND model_snapshot IS NULL",
                        policy.path("model").asText(null),operation);
            }
            return !missing.isEmpty();
        }))){/* Batches release the startup mutex. */}
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void trackAd(UUID operation,UUID owner,UUID product,UUID brand,long selectionEstimate){
        insert(operation,Kind.AD,owner,product,brand,Math.addExact(selectionEstimate,generationEstimate),now());
    }

    /** Observe synchronous analysis including failures; does not limit how often it may run. */
    public <T> T execute(String subject,Kind kind,Supplier<T> work){
        if(kind==Kind.AD)throw new IllegalArgumentException("Ads are tracked by their durable request");
        UUID id=store.transaction(()->{
            UUID owner=store.db.query("SELECT id FROM users WHERE google_id=?",(r,n)->r.getObject(1,UUID.class),subject)
                    .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found"));
            UUID operation=UUID.randomUUID();insert(operation,kind,owner,null,null,analysisEstimate,now());return operation;
        });
        Call previous=CURRENT.get();Call call=new Call(id);CURRENT.set(call);
        try{
            T result=work.get();call.finish(true);return result;
        }catch(RuntimeException failure){
            try{call.finish(false);}catch(RuntimeException accounting){failure.addSuppressed(accounting);}
            throw failure;
        }finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }

    public static void providerStarted(){
        providerStarted(null);
    }
    public static void providerStarted(String model){
        Call call=CURRENT.get();if(call==null)return;
        call.dispatched=true;
        call.owner().store.db.update("UPDATE usage_operations SET state='STARTED',provider_started=TRUE,model=? WHERE id=?",bounded(model),call.id);
    }
    public static void providerRejected(int status){
        Call call=CURRENT.get();
        if(call!=null && Set.of(400,401,403,404,413,422,429).contains(status)){call.known=true;call.cost=0;}
    }
    public static void providerUsage(String model,String provider,long input,long output){
        Call call=CURRENT.get();if(call==null)return;
        if(input<0 || output<0){
            call.owner().store.db.update("UPDATE usage_operations SET model=?,provider_id=? WHERE id=?",bounded(model),bounded(provider),call.id);
            return;
        }
        call.cost=call.owner().cost(input,output);call.known=true;
        call.owner().store.db.update("""
                UPDATE usage_operations SET model=?,provider_id=?,input_tokens=?,output_tokens=?,accounted_micros=? WHERE id=?
                """,bounded(model),bounded(provider),input,output,call.cost,call.id);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void recordAdCall(UUID job,String stage,String model,String provider,Long input,Long output,long cost){
        UUID operation=store.db.queryForObject("SELECT a.request_id FROM generation_jobs g JOIN concept_allocations a ON a.id=g.allocation_id WHERE g.id=?",UUID.class,job);
        store.db.update("INSERT INTO usage_ad_calls(operation_id,stage,model,provider_id,input_tokens,output_tokens,estimated_micros) VALUES(?,?,?,?,?,?,?)",
                operation,stage,bounded(model),bounded(provider),input,output,cost);
    }

    private final class Call {
        final UUID id;boolean dispatched,known,finished;long cost;
        Call(UUID id){this.id=id;}
        UsageService owner(){return UsageService.this;}
        void finish(boolean succeeded){
            if(finished)return;
            store.db.update("UPDATE usage_operations SET state=?,outcome=?,accounted_micros=? WHERE id=?",
                    dispatched && !known?"UNKNOWN":succeeded?"COMPLETED":"FAILED",succeeded?"SUCCEEDED":"FAILED",
                    known?cost:dispatched?analysisEstimate:0,id);
            finished=true;
        }
    }
    private long cost(long input,long output){return Math.addExact(Math.addExact(Math.multiplyExact(input,inputRate),Math.multiplyExact(output,outputRate)),999999)/1000000;}
    private void insert(UUID id,Kind kind,UUID owner,UUID product,UUID brand,long estimate,Instant created){
        store.db.update("""
                INSERT INTO usage_operations(id,kind,owner_id,product_id,brand_id,reserved_micros,state,created_at)
                VALUES(?,?,?,?,?,?,'RESERVED',?)
                """,id,kind.name(),owner,product,brand,estimate,Timestamp.from(created));
    }
    private Instant now(){return store.db.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();}
    private static Instant instant(Object value){
        if(value instanceof Timestamp timestamp)return timestamp.toInstant();
        if(value instanceof OffsetDateTime date)return date.toInstant();
        return ((java.util.Date)value).toInstant();
    }
    private static String bounded(String value){return value==null?null:value.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(value.length(),255));}

    // One row per operation; aggregate calls separately so costs are never multiplied by joins.
    private static final String SOURCE="""
            SELECT b.*,CASE WHEN b.state IN ('RESERVED','STARTED','ACTIVE','UNKNOWN')
              THEN CASE WHEN b.reserved_micros>b.accounted_micros THEN b.reserved_micros ELSE b.accounted_micros END
              ELSE b.accounted_micros END AS exposure_micros
            FROM (
              SELECT u.id,u.kind,u.owner_id,u.product_id,u.brand_id,u.created_at,u.reserved_micros,
                CASE WHEN u.kind<>'AD' THEN u.outcome
                  WHEN s.id IS NULL OR g.status='RECONCILIATION_REQUIRED' THEN 'UNKNOWN'
                  WHEN s.status IN ('QUEUED','SCREENING','VERIFYING') OR g.status IN ('QUEUED','WAITING_FOR_PROVIDER','PROMPTING','PROMPT_READY','GENERATING','REVIEWING') THEN 'PENDING'
                  WHEN g.status IN ('SUCCEEDED','DELETED') THEN 'SUCCEEDED'
                  WHEN s.status='SELECTED' AND g.id IS NULL THEN 'UNKNOWN' ELSE 'FAILED' END AS execution_outcome,
                CASE WHEN u.kind='AD' THEN COALESCE(g.status,s.status,'UNKNOWN') ELSE u.outcome END AS workflow_status,
                CASE WHEN u.kind<>'AD' THEN u.state
                  WHEN s.id IS NULL THEN 'UNKNOWN'
                  WHEN s.status IN ('QUEUED','SCREENING','VERIFYING') THEN 'ACTIVE'
                  WHEN g.status IN ('QUEUED','WAITING_FOR_PROVIDER','PROMPTING','PROMPT_READY','GENERATING','REVIEWING') THEN 'ACTIVE'
                  WHEN g.status='RECONCILIATION_REQUIRED' THEN 'UNKNOWN'
                  WHEN g.status IN ('SUCCEEDED','DELETED') THEN 'COMPLETED'
                  WHEN s.status='SELECTED' AND g.id IS NULL THEN 'UNKNOWN' ELSE 'FAILED' END AS state,
                CASE WHEN u.kind='AD' THEN COALESCE(s.spent_micros,0)+COALESCE(g.spent_micros,0)
                  ELSE u.accounted_micros END AS accounted_micros,
                COALESCE(u.input_tokens,0)+COALESCE(t.input_tokens,0)+COALESCE(c.input_tokens,0) AS input_tokens,
                COALESCE(u.output_tokens,0)+COALESCE(t.output_tokens,0)+COALESCE(c.output_tokens,0) AS output_tokens,
                CASE WHEN u.kind<>'AD' THEN NOT(u.provider_started=TRUE AND (u.input_tokens IS NULL OR u.output_tokens IS NULL))
                  WHEN s.id IS NULL OR COALESCE(t.missing_tokens,0)>0 OR g.status IN ('PROMPTING','GENERATING','RECONCILIATION_REQUIRED')
                    OR (g.prompt_text IS NOT NULL AND COALESCE(c.prompt_known,0)=0)
                    OR (g.provider_id IS NOT NULL AND COALESCE(c.render_known,0)=0)
                    OR (g.review_started=TRUE AND COALESCE(c.review_known,0)=0) THEN FALSE ELSE TRUE END AS token_data_complete
              FROM usage_operations u
              LEFT JOIN selection_requests s ON u.kind='AD' AND s.id=u.id
              LEFT JOIN concept_allocations a ON a.request_id=s.id
              LEFT JOIN generation_jobs g ON g.allocation_id=a.id
              LEFT JOIN (SELECT request_id,SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,
                SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL THEN 1 ELSE 0 END) AS missing_tokens
                FROM selection_attempts GROUP BY request_id) t ON t.request_id=u.id
              LEFT JOIN (SELECT operation_id,SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,
                MAX(CASE WHEN stage='PROMPT' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL THEN 1 ELSE 0 END) AS prompt_known,
                MAX(CASE WHEN stage='RENDER' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL THEN 1 ELSE 0 END) AS render_known,
                MAX(CASE WHEN stage='REVIEW' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL THEN 1 ELSE 0 END) AS review_known
                FROM usage_ad_calls GROUP BY operation_id) c ON c.operation_id=u.id
            ) b
            """;
    private static final String TOTALS="""
            COUNT(*) AS requests,
            COALESCE(SUM(CASE WHEN execution_outcome='SUCCEEDED' THEN 1 ELSE 0 END),0) AS completed,
            COALESCE(SUM(CASE WHEN execution_outcome='FAILED' THEN 1 ELSE 0 END),0) AS failed,
            COALESCE(SUM(CASE WHEN state IN ('RESERVED','STARTED','ACTIVE') THEN 1 ELSE 0 END),0) AS active,
            COALESCE(SUM(CASE WHEN state='UNKNOWN' THEN 1 ELSE 0 END),0) AS unknowns,
            COALESCE(SUM(input_tokens),0) AS inputs,COALESCE(SUM(output_tokens),0) AS outputs,
            COALESCE(SUM(CASE WHEN token_data_complete=FALSE THEN 1 ELSE 0 END),0) AS incomplete,
            COALESCE(SUM(accounted_micros),0) AS accounted,
            COALESCE(SUM(exposure_micros-accounted_micros),0) AS pending,
            COALESCE(SUM(exposure_micros),0) AS exposure
            """;

    public Summary summary(LocalDate from,LocalDate to,UUID owner,UUID product,UUID brand,Kind kind){
        Filter f=filter(from,to,owner,product,brand,kind);
        return store.db.queryForObject("SELECT "+TOTALS+" FROM ("+SOURCE+") v WHERE "+f.sql(),this::mapSummary,f.args().toArray());
    }
    public List<UserUsage> users(LocalDate from,LocalDate to,int page,int size){
        return users(from,to,null,null,null,null,page,size);
    }
    public List<UserUsage> users(LocalDate from,LocalDate to,UUID owner,UUID product,UUID brand,Kind kind,int page,int size){
        com.giraffix.shared.Pages.of(page,size);Filter f=filter(from,to,owner,product,brand,kind);
        var args=new ArrayList<>(f.args());args.add(size);args.add((long)page*size);
        return store.db.query("SELECT totals.*,u.full_name,u.email FROM (SELECT owner_id,"+TOTALS+" FROM ("+SOURCE+") v WHERE "+f.sql()+
                " GROUP BY owner_id) totals LEFT JOIN users u ON u.id=totals.owner_id ORDER BY exposure DESC,owner_id LIMIT ? OFFSET ?",
                (r,n)->new UserUsage(r.getObject("owner_id",UUID.class),r.getString("full_name"),r.getString("email"),mapSummary(r,n)),args.toArray());
    }
    public List<DailyUsage> daily(LocalDate from,LocalDate to,UUID owner,UUID product,UUID brand,Kind kind){
        Filter f=filter(from,to,owner,product,brand,kind);
        return store.db.query("SELECT CAST(created_at AT TIME ZONE 'UTC' AS DATE) AS usage_day,"+TOTALS+" FROM ("+SOURCE+") v WHERE "+f.sql()+
                " GROUP BY CAST(created_at AT TIME ZONE 'UTC' AS DATE) ORDER BY usage_day",
                (r,n)->new DailyUsage(r.getObject("usage_day",LocalDate.class),mapSummary(r,n)),f.args().toArray());
    }
    public List<Operation> operations(LocalDate from,LocalDate to,UUID owner,UUID product,UUID brand,Kind kind,int page,int size){
        com.giraffix.shared.Pages.of(page,size);Filter f=filter(from,to,owner,product,brand,kind);
        var args=new ArrayList<>(f.args());args.add(size);args.add((long)page*size);
        return store.db.query("SELECT * FROM ("+SOURCE+") v WHERE "+f.sql()+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::mapOperation,args.toArray());
    }
    public Operation operation(UUID id){
        return store.db.query("SELECT * FROM ("+SOURCE+") v WHERE id=?",this::mapOperation,id).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Usage operation not found"));
    }
    private Operation mapOperation(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Operation(r.getObject("id",UUID.class),Kind.valueOf(r.getString("kind")),r.getObject("owner_id",UUID.class),
                r.getObject("product_id",UUID.class),r.getObject("brand_id",UUID.class),r.getString("state"),r.getString("execution_outcome"),r.getString("workflow_status"),r.getLong("accounted_micros"),
                r.getLong("exposure_micros"),r.getLong("reserved_micros"),r.getLong("input_tokens"),r.getLong("output_tokens"),
                r.getBoolean("token_data_complete"),r.getTimestamp("created_at").toInstant());
    }
    private Summary mapSummary(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Summary(r.getLong("requests"),r.getLong("completed"),r.getLong("failed"),r.getLong("active"),r.getLong("unknowns"),
                r.getLong("inputs"),r.getLong("outputs"),r.getLong("incomplete"),r.getLong("accounted"),r.getLong("pending"),r.getLong("exposure"));
    }

    private static final String CALLS="""
            SELECT operation_id,stage,model,provider_id,input_tokens,output_tokens,estimated_micros,
              CAST(operation_id AS VARCHAR(36)) || ':' || stage AS call_id FROM usage_ad_calls
            UNION ALL
            SELECT request_id,'SELECTION_' || stage,model_snapshot,provider_id,input_tokens,output_tokens,
              CASE WHEN actual_micros IS NULL THEN reserved_micros ELSE actual_micros END,CAST(id AS VARCHAR(36)) FROM selection_attempts
            UNION ALL
            SELECT id,kind,model,provider_id,input_tokens,output_tokens,accounted_micros,CAST(id AS VARCHAR(36)) FROM usage_operations
              WHERE kind<>'AD' AND provider_started=TRUE
            """;
    public List<ModelCall> calls(UUID id,int page,int size){
        com.giraffix.shared.Pages.of(page,size);operation(id);
        return store.db.query("SELECT * FROM ("+CALLS+") calls WHERE operation_id=? ORDER BY stage,provider_id NULLS LAST,call_id LIMIT ? OFFSET ?",
                (r,n)->new ModelCall(r.getString("stage"),r.getString("model"),r.getString("provider_id"),r.getObject("input_tokens",Long.class),
                        r.getObject("output_tokens",Long.class),r.getObject("estimated_micros",Long.class)),id,size,(long)page*size);
    }
    public List<ModelUsage> models(LocalDate from,LocalDate to,UUID owner,UUID product,UUID brand,Kind kind,int page,int size){
        com.giraffix.shared.Pages.of(page,size);Filter f=filter(from,to,owner,product,brand,kind);
        var args=new ArrayList<>(f.args());args.add(size);args.add((long)page*size);
        return store.db.query("""
                SELECT calls.model,calls.stage,COUNT(*) AS calls,COALESCE(SUM(calls.input_tokens),0) AS inputs,
                  COALESCE(SUM(calls.output_tokens),0) AS outputs,COALESCE(SUM(calls.estimated_micros),0) AS cost,
                  SUM(CASE WHEN calls.input_tokens IS NULL OR calls.output_tokens IS NULL THEN 1 ELSE 0 END) AS incomplete
                FROM (
                """+CALLS+") calls JOIN usage_operations v ON v.id=calls.operation_id WHERE "+f.sql()+
                " GROUP BY calls.model,calls.stage ORDER BY inputs DESC,calls.stage,calls.model NULLS LAST LIMIT ? OFFSET ?",
                (r,n)->new ModelUsage(r.getString("model"),r.getString("stage"),r.getLong("calls"),r.getLong("inputs"),r.getLong("outputs"),r.getLong("incomplete"),r.getLong("cost")),args.toArray());
    }
    public LocalDate currentMonth(){return now().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);}
    private Filter filter(LocalDate from,LocalDate to,UUID owner,UUID product,UUID brand,Kind kind){
        if(from==null)from=currentMonth();if(to==null)to=from.plusMonths(1);
        if(from.getYear()<1970 || to.getYear()>9999 || !to.isAfter(from) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>366)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use an exclusive end date within 366 days of the start");
        var args=new ArrayList<Object>();args.add(Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
        args.add(Timestamp.from(to.atStartOfDay(ZoneOffset.UTC).toInstant()));
        String sql="v.created_at>=? AND v.created_at<?";
        if(owner!=null){sql+=" AND v.owner_id=?";args.add(owner);}
        if(product!=null){sql+=" AND v.product_id=?";args.add(product);}
        if(brand!=null){sql+=" AND v.brand_id=?";args.add(brand);}
        if(kind!=null){sql+=" AND v.kind=?";args.add(kind.name());}
        return new Filter(sql,args);
    }
    private record Filter(String sql,List<Object> args){}
}
