package com.giraffix.product.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** Durable storage intent committed in the same transaction as product removal. */
@Service
public class ProductImageDeletionQueue {
    private static final Logger log = LoggerFactory.getLogger(ProductImageDeletionQueue.class);
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final S3ImageStorage storage;

    public ProductImageDeletionQueue(JdbcTemplate db, PlatformTransactionManager manager, S3ImageStorage storage) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
        this.storage = storage;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID productId, List<String> urls) {
        for (var object : storage.productObjects(productId, urls)) {
            db.update("""
                    INSERT INTO product_image_deletions(id,product_id,bucket,object_key,created_at,next_attempt_at)
                    VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), productId, object.bucket(), object.key());
        }
    }

    /** Each claim commits before S3 I/O; crashed claims become eligible again after two minutes. */
    @Transactional(propagation = Propagation.NEVER)
    public void processPending() {
        // Use the database clock for due times and leases across application replicas.
        var ids = db.query("""
                SELECT id FROM product_image_deletions
                WHERE next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                ORDER BY next_attempt_at,created_at,id LIMIT 20
                """, (row, index) -> row.getObject(1, UUID.class));
        for (UUID id : ids) {
            UUID token = UUID.randomUUID();
            Work work = transactions.execute(status -> {
                if (db.update("""
                        UPDATE product_image_deletions
                        SET lease_token=?,lease_until=CURRENT_TIMESTAMP + INTERVAL '120' SECOND,attempts=attempts+1
                        WHERE id=? AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                        """, token, id) != 1) {
                    return null;
                }
                return db.queryForObject("SELECT product_id,bucket,object_key,attempts FROM product_image_deletions WHERE id=?",
                        (row, index) -> new Work(row.getObject(1, UUID.class),
                                new S3ImageStorage.StoredObject(row.getString(2), row.getString(3)), row.getInt(4)), id);
            });
            if (work == null) continue;
            try {
                // DeleteObject is idempotent, including a replay after remote success / local crash.
                storage.deleteProductObject(work.productId(), work.object());
            } catch (RuntimeException ex) {
                long delaySeconds = Math.min(3600, 30L << Math.min(work.attempts() - 1, 7));
                db.update("""
                        UPDATE product_image_deletions
                        SET lease_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP + CAST(? AS BIGINT) * INTERVAL '1' SECOND,last_error_type=?
                        WHERE id=? AND lease_token=?
                        """, delaySeconds, ex.getClass().getSimpleName(), id, token);
                log.warn("stage=product_deletion_cleanup_deferred cleanupId={} productId={} attempt={} retrySeconds={} errorType={}",
                        id, work.productId(), work.attempts(), delaySeconds, ex.getClass().getSimpleName());
                continue;
            }
            db.update("DELETE FROM product_image_deletions WHERE id=? AND lease_token=?", id, token);
        }
    }

    private record Work(UUID productId, S3ImageStorage.StoredObject object, int attempts) {}
}
