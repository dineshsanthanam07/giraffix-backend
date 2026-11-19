**Saved ads and deletion**

Generated ads are stored independently of their source products. A successful ad
has a `creations` row containing the original owner ID, product ID, brand ID,
generation job ID, concept, aspect ratio, quality, prompt/version, and S3 reference.
S3 stores the image at `creations/{ownerId}/{jobId}/output`.

Deleting a product removes its metadata and queues deletion of its uploaded product
photos. It does **not** delete saved generated ads, their prompts, or their S3 images.
The owner can still list/read/delete those saved ads. Work whose product disappears
before generation proceeds terminates as stale context and releases unused concept
occupancy instead of becoming stuck merely because the product is missing.

**Read APIs**

All routes require the existing login session. The user is taken from the session;
callers cannot request another account's ads by supplying a user ID.

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/v1/ads?page=0&size=20` | List the signed-in user's saved ads |
| GET | `/api/v1/ads?productId=PRODUCT_UUID` | Filter saved ads by original product |
| GET | `/api/v1/ads?brandId=BRAND_UUID` | Filter saved ads by original brand |
| GET | `/api/v1/ads?productId=PRODUCT_UUID&brandId=BRAND_UUID` | Apply both filters |
| GET | `/api/v1/ads/saved/{creationId}` | Retrieve one saved ad's metadata and prompt |
| GET | `/api/v1/ads/saved/{creationId}/image` | Retrieve authorized image bytes |
| DELETE | `/api/v1/ads/saved/{creationId}` | Explicitly delete that saved ad |

The existing creation routes remain available: `GET /api/v1/products/{productId}/creations`,
`GET /api/v1/creations/{id}/image`, and the new detail/delete aliases
`GET`/`DELETE /api/v1/creations/{id}`. Product history works for a deleted product
when the owner still has saved ads for it. Unknown/foreign product history returns
404; the user-wide filtered list simply returns an empty array for unmatched filters.

`GET /api/v1/ads/{id}` remains the **generation operation/status** endpoint and uses
the ID returned by `POST /api/v1/ads`. Saved-ad routes use the **creationId** returned
on successful generation or the `id` from the saved-ad list. These IDs are distinct.

The list is a JSON array, newest first with a stable ID tie-breaker. `page` defaults
to 0 and `size` to 20, with a maximum size of 100. Entries include:

```json
{
  "id": "CREATION_UUID",
  "jobId": "GENERATION_JOB_UUID",
  "conceptId": "CONCEPT_UUID",
  "imageUrl": "/api/v1/creations/CREATION_UUID/image",
  "createdAt": "2026-11-19T12:00:00Z",
  "aspectRatio": "4:5",
  "quality": "high",
  "ownerId": "USER_UUID",
  "productId": "ORIGINAL_PRODUCT_UUID",
  "brandId": "ORIGINAL_BRAND_UUID"
}
```

Single-ad detail returns `{ "ad": <list entry>, "imagePrompt": "...", "promptVersion": "..." }`.
Metadata/image reads use the saved owner, without requiring the source product or
brand to exist. Another account receives 404 for detail/image/delete. Image responses
retain `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

**Explicit saved-ad deletion**

DELETE requires the session and CSRF token. One transaction locks/authorizes the
creation, records its exact S3 cleanup target in `generated_ad_deletions`, removes
the saved-ad row, and changes the generation job to `DELETED`. HTTP 204 confirms
the database commit. The original status endpoint then returns `DELETED` without
an image URL. The deleted creation is absent from lists and its image/detail return
404, even while physical cleanup is pending. Repeating DELETE returns 404.

The product and its uploaded photos remain intact. Existing consumed concept
occupancy and generation/cost history remain intact; deleting an ad does not reset
concept-selection policy or replay paid work.

Cleanup runs after commit, on the storage cleanup scheduler. The worker uses
two-minute claim leases, idempotent DeleteObject calls, and database-clock retries
from 30 seconds to one hour. Process termination after remote success can safely
replay deletion after lease expiry. A database rollback preserves the ad and image;
an S3 outage preserves pending cleanup work rather than failing the committed DELETE.
The existing `giraffix.storage.cleanup.worker-enabled` and `poll-ms` settings govern
both product and ad cleanup. Bucket changes defer old targets instead of redirecting
them. Configure DeleteObject permission on `creations/*` as well as `products/*`.
S3 versioning still requires lifecycle policies to remove older object versions.

Inspect pending explicit-ad cleanup with:

```sql
SELECT id, owner_id, job_id, attempts, next_attempt_at, lease_until, last_error_type
FROM generated_ad_deletions
ORDER BY created_at;
```

**Migration**

Restart the backend to apply **Flyway V16**. It adds the saved brand ID, indexes,
and the explicit-ad cleanup queue. Existing ads whose products still exist receive
their brand ID automatically. For older ads whose products were already deleted,
the original brand cannot be reliably reconstructed: their brand ID remains null.
They remain accessible by owner, product ID, and creation ID, but do not match brand
filters until an authoritative backfill is supplied. New ads always save brand ID
at publication while the product is locked.

**Validation**

The full offline Maven suite passed with **270 tests, zero failures, zero errors,
and zero skipped**; evidence: `target/saved-ad-full-tests.log`. Regression checks
cover preserved ad reads after product/photo deletion, owner/product/brand filters,
explicit ad deletion, rollback, S3 failure/backoff, expired-lease replay, target
validation, session/CSRF enforcement, stale pending work, migration/backfill, and
the existing pipeline. Tests use H2, local provider fixtures, and mocked S3;
production PostgreSQL/S3 were not exercised.
