**Product deletion**

`DELETE /api/v1/products/{id}` requires the existing session, CSRF token, and product
ownership. It returns 204 after one database transaction removes the product/image
metadata and records each uploaded photo in `product_image_deletions`. The product
write lock coordinates deletion with edits and image append. External legacy
`imageUrl` values are never cleanup targets.

S3 deletion starts only from committed cleanup rows. If the database transaction
rolls back, the product, photo metadata, and S3 bytes remain intact. If S3 is unavailable,
the product remains deleted and its cleanup records stay available for retry.
Repeating DELETE after success returns the usual 404; clients need not repeat it to
retry S3 cleanup. Physical object removal follows the API's logical deletion and
has no fixed completion deadline during an outage.

Restart the backend to apply Flyway **V15** before using this implementation. The
queue intentionally has no product foreign key, so deleting the source row cannot
erase its storage cleanup intent. Bucket/key values are captured at enqueue time;
all targets are validated before product removal. A later bucket configuration
change defers old cleanup rather than redirecting it to a new bucket. Resolve old
work against its original bucket before retiring that storage configuration.

**Worker behavior**

- Separate single-thread scheduler; S3 calls do not block selection/generation polling.
- At most 20 eligible objects per scan; each claim commits before S3 I/O.
- Two-minute leases and UUID claim tokens coordinate workers across replicas.
- An expired claim can replay safely: deleting an absent S3 key succeeds.
- Failure retries after 30 seconds, doubling up to one hour, without a terminal retry cap.
- A successful delete acknowledges only its own claim and removes that queue row.
- A crash after remote success but before acknowledgment leaves retryable durable work.

| Property | Default | Purpose |
| --- | --- | --- |
| `giraffix.storage.cleanup.worker-enabled` | `true` | Enable scheduled cleanup; queue writes continue when disabled |
| `giraffix.storage.cleanup.poll-ms` | `5000` | Delay between scans and initial startup delay |

The worker uses the existing S3 DeleteObject permission for `products/*`. With bucket
versioning, DeleteObject creates a delete marker; older versions still require an
S3 lifecycle policy if they must be erased. Generated ads and their history are
preserved and remain accessible to their original owner, including product-filtered
history after deletion. Ad removal requires a separate explicit delete action;
see [saved ads](saved-ads.md). Brand logos and concept images are outside the product
cleanup queue.

**Operations**

Inspect pending work using:

```sql
SELECT id, product_id, attempts, next_attempt_at, lease_until, last_error_type
FROM product_image_deletions
ORDER BY created_at;
```

`stage=product_deletion_staged` means the transaction has staged deletion; HTTP 204
confirms commit. `stage=s3_delete_completed` records object cleanup, and
`stage=product_deletion_cleanup_deferred` identifies retries by cleanup/product ID.
Provider response bodies and exception messages are not written by the retry handler.
Monitor pending count, oldest work, repeated attempts, and missing worker capacity.
Do not delete pending rows as a way to clear an outage: those rows are the durable
record of objects still requiring removal.

**Validation**

The full offline Maven suite passed with **260 tests, zero failures, zero errors,
and zero skipped**. Evidence: `target/product-deletion-full-tests.log`.
Deletion regressions cover commit-before-cleanup ordering, commit-time rollback,
partial S3 failure/backoff, expired leases and absent-object replay, competing
workers, S3 I/O outside database transactions, ownership, external URLs, and
bucket/product target validation. Migration checks cover fresh and legacy-baselined
schemas through V15. Tests use H2 and mocked S3; live PostgreSQL/S3 were not exercised.
