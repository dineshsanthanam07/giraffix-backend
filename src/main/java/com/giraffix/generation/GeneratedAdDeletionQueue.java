package com.giraffix.generation;

import com.giraffix.selection.SelectionModels.Asset;
import com.giraffix.selection.SelectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Only explicit saved-ad deletion writes this queue; product deletion never does. */
@Service
public class GeneratedAdDeletionQueue {
    private static final Logger log=LoggerFactory.getLogger(GeneratedAdDeletionQueue.class);
    private final SelectionStore store;
    private final GeneratedAssetStorage storage;

    public GeneratedAdDeletionQueue(SelectionStore store,GeneratedAssetStorage storage){
        this.store=store;this.storage=storage;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(UUID owner,UUID job,Asset asset){
        storage.validateDeleteTarget(owner,job,asset);
        store.db.update("""
                INSERT INTO generated_ad_deletions(id,owner_id,job_id,asset_json,created_at,next_attempt_at)
                VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,UUID.randomUUID(),owner,job,store.json.write(asset));
    }

    @Transactional(propagation=Propagation.NEVER)
    public void processPending(){
        var ids=store.db.query("""
                SELECT id FROM generated_ad_deletions
                WHERE next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                ORDER BY next_attempt_at,created_at,id LIMIT 20
                """,(r,n)->r.getObject(1,UUID.class));
        for(UUID id:ids){
            UUID token=UUID.randomUUID();
            Work work=store.transaction(()->{
                if(store.db.update("""
                        UPDATE generated_ad_deletions
                        SET lease_token=?,lease_until=CURRENT_TIMESTAMP + INTERVAL '120' SECOND,attempts=attempts+1
                        WHERE id=? AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                        """,token,id)!=1)return null;
                return store.db.queryForObject("SELECT owner_id,job_id,asset_json,attempts FROM generated_ad_deletions WHERE id=?",
                        (r,n)->new Work(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                                store.json.read(r.getString(3),Asset.class),r.getInt(4)),id);
            });
            if(work==null)continue;
            try{
                storage.delete(work.owner(),work.job(),work.asset());
            }catch(RuntimeException ex){
                long delay=Math.min(3600,30L << Math.min(work.attempts()-1,7));
                store.db.update("""
                        UPDATE generated_ad_deletions
                        SET lease_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP + CAST(? AS BIGINT) * INTERVAL '1' SECOND,last_error_type=?
                        WHERE id=? AND lease_token=?
                        """,delay,ex.getClass().getSimpleName(),id,token);
                log.warn("stage=ad_deletion_cleanup_deferred cleanupId={} jobId={} retrySeconds={} errorType={}",
                        id,work.job(),delay,ex.getClass().getSimpleName());
                continue;
            }
            store.db.update("DELETE FROM generated_ad_deletions WHERE id=? AND lease_token=?",id,token);
        }
    }
    private record Work(UUID owner,UUID job,Asset asset,int attempts){}
}
