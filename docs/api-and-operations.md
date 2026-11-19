**API organization and operating guide**

Swagger groups the API by identity, security, brands, products, ads, creations, the concept library, concept administration, and prompt administration. Routes retain their existing resource names and response fields. JSON and multipart variants of the same resource mutation remain supported; they serve different upload requirements.

**Default API inventory**

| Group | Methods and route | Purpose |
| --- | --- | --- |
| Identity | GET `/api/v1/users/me` | Current account and onboarding state |
| Security | GET `/api/v1/security/csrf` | Token/header for authenticated mutations |
| Brands | GET, POST `/api/v1/brands`; GET, PATCH `/api/v1/brands/{id}` | Owned brands, including multipart logo creation/replacement |
| Products | GET, POST `/api/v1/brands/{id}/products` | Bounded product lists and JSON/multipart creation |
| Products | GET, PATCH, DELETE `/api/v1/products/{id}` | Owned product lifecycle |
| Products | POST `/api/v1/products/{id}/images` | Append validated photos |
| Products | POST `/api/v1/analyze` | Analyze photos without saving a product |
| Ads | POST `/api/v1/ads`; GET `/api/v1/ads/{id}` | Canonical end-to-end pipeline and polling by operation ID |
| Ad progress | GET `/api/v1/ads/{id}/events` | [Session-authenticated SSE](ad-progress.md) with current stage and final result |
| Saved ads | GET `/api/v1/ads`; GET/DELETE `/api/v1/ads/saved/{id}`; GET saved-ad image | Owner-scoped product/brand filters and explicit ad removal; see [saved ads](saved-ads.md) |
| Creations | GET `/api/v1/products/{id}/creations`; GET/DELETE `/api/v1/creations/{id}`; GET creation image | Saved-ad aliases and authorized history, including deleted products |
| Concepts | GET, POST `/api/v1/concepts`; GET `/api/v1/concepts/{id}` | Shared reference library and extraction |
| Admin concepts | GET, POST `/api/v1/concepts/{id}/revisions`; POST revision approve/revoke routes | Allowlisted moderation |
| Admin prompts | `/api/v1/admin/prompts` and revision/publication routes | [Versioned prompt editor](admin-prompts.md) |
| Admin usage | GET `/api/v1/admin/usage` plus users/models/daily/operations routes | [Read-only usage analytics](usage-analytics.md), with no quota enforcement |

`/admin/prompts` is the admin HTML page, hidden from API operations in Swagger. `/logout` and Google OAuth routes are framework session routes. All existing session/CSRF and ownership rules remain in effect.

**Retired default surface**

Standalone `/api/v1/selection-requests` POST/GET routes duplicate the concept-selection portion already performed by `/ads`, so they are disabled by default. Existing clients that need manual/standalone selection can explicitly enable `giraffix.api.legacy-selection-enabled=true` (environment: `GIRAFFIX_API_LEGACY_SELECTION_ENABLED=true`). They then appear under the Legacy selection tag. Internal selection services and durable recovery remain available to the ad pipeline regardless of this flag.

Product analysis's legacy multipart `image` alias remains accepted for compatibility; it is not another API endpoint. Concept review, creation reads and prompt audit routes remain because they have distinct authorization and operational purposes. The code does not delete routes merely because they are used less frequently.

**Bounded reads and fewer writes**

Brand and product collection reads accept `page` (default 0) and `size` (default 20, maximum 100). Their response remains a JSON array. Clients that previously assumed an unlimited list must request subsequent pages. Stable creation-time/ID ordering prevents ambiguous ties. Invalid pagination returns 400. Concept and creation histories retain their existing bounds.

Product image collections use bounded batch loading instead of one image query per product in a list. Individual product reads and mutations authorize by Google subject in the product query instead of first loading a user just to retrieve its database ID. Product mutations/image append share a product write-lock path, preventing competing appends from using the same position or stale image-count limit.

Multipart product creation builds and persists one managed product, then flushes product/image metadata once. It avoids the old create-response conversion, initial flush and repeated ownership/product fetch. Ad submission no longer fetches an intermediate selection-status response that it immediately discards. Ad/selection polling retain their one authorized status query. Private creation delivery now authorizes and retrieves metadata in one query, replacing three; creation history uses two queries rather than three, including an explicit check to distinguish an empty owned collection from unauthorized access.

The workflow already polls durable jobs directly and has no outbox consumer. New jobs therefore no longer insert/update `outbox_events`, eliminating two writes per generation. The historical table/rows remain intact for compatibility and audit; no historical data was dropped. Prompt cache behavior remains [publication-driven](admin-prompts.md), with no periodic prompt-version queries.

**Storage behavior**

Product uploads register one transaction-completion callback. A rollback, including a commit-time failure, triggers best-effort removal of that operation's uploaded objects. An uncertain PUT attempts deletion of its known generated key even if no URL was returned. Cleanup validates the configured bucket/product prefix and logs failures without provider response bodies.

Generation with a wrong aspect ratio stops before upload/review. A stored output rejected by review, expired, or made stale is removed only after the terminal database state commits. Cleanup accepts only the exact configured `creations/{owner}/{job}/output` object. Successful creations and outputs with uncertain provider/review outcomes are preserved. Product DELETE now commits logical deletion plus durable photo-cleanup work together; S3 failures retry in the background. See [product deletion](product-deletion.md).

Upload/rejected-generation cleanup remains best-effort compensation. Product-deletion cleanup uses the durable V15 queue and retries after outages or process termination. Historical creation history, prompt revisions and published assets are not automatically erased.

**Errors and logging**

Every `/api/v1/**`, `/admin/prompts`, and `/logout` request receives `X-Request-ID`, including requests rejected by security. A supplied ID is accepted only if it contains 1–64 letters/digits/periods/underscores/colons/hyphens; other values are replaced. MVC errors use a bounded structured response:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Check the request fields",
  "requestId": "support-trace-123",
  "fields": {"name": "Product name cannot be blank"}
}
```

Validation messages identify fields without echoing rejected values. Unexpected errors and upstream 5xx responses do not expose raw exception messages, SQL, credentials or provider bodies. Framework security errors retain their existing response contract/status, with request correlation supplied by the filter.

| Level | Events |
| --- | --- |
| INFO | Mutating HTTP receipt/completion, queued selections, claimed jobs, prompt/render stages, terminal states, storage operations |
| WARN | Handled 4xx errors, rejected requests, interrupted provider attempts, notification reconnects, deferred publication |
| ERROR | 5xx failures, unexpected exception type/location, reconciliation failures, failed/rejected cleanup |
| DEBUG | Successful GET/HEAD requests (including polling), idempotent reuse, stack frames and resource/field-size diagnostics |

Use `requestId` for HTTP correlation and `operationId`, `selectionId`, `jobId`, product ID and provider request IDs for asynchronous stages. The legacy `analysisId` field remains an alias for the HTTP request ID in analysis logs. Worker MDC is cleared when the execution ends. Routine successful polling is not written at INFO, reducing log/storage volume.

Default diagnostics are INFO. Set `GIRAFFIX_LOG_LEVEL=DEBUG` for the configured Giraffix diagnostics group when investigating a problem; return to INFO afterward. SQL console output is disabled. Extracted product fields log character counts instead of their text, including at DEBUG. Logs omit request/response bodies, prompts, image bytes/base64, cookies, authorization/CSRF tokens, and raw provider errors. Existing rotation remains 10 MB per file, seven days, and 100 MB total.

Useful searches in PowerShell:

```powershell
rg 'support-trace-123' logs
rg 'stage=api_error|stage=generation_reconciliation_required' logs
rg 'stage=product_image_cleanup_failed|stage=creation_cleanup_failed' logs
```

Validation: the full local Maven suite passed with **255 tests, zero failures, zero errors and zero skipped**. Evidence: `target/api-cleanup-tests-final.log`. Checks cover default/legacy API exposure, Swagger grouping, pagination, request-ID/error contracts, ownership, rollback/commit-time upload cleanup, rejected-output cleanup and the existing ad/prompt pipeline. Provider/storage tests use local servers or mocks; production PostgreSQL/S3 behavior was not exercised by this run.
