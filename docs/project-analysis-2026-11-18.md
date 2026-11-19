**Giraffix project analysis — 18 November 2026**

The workspace contains a substantial Spring Boot backend for turning saved product photos and an approved advertising concept into a reviewed advertisement. The main workflow is implemented, including a real image-provider adapter. It is suitable for further integration and controlled validation, but storage consistency, recovery operations, admission control, and production delivery need work before broad production use.

This assessment concerns the local source and isolated tests. It does not certify a deployed service or live provider compatibility. Application code and the user's existing configuration edits were left unchanged.

**Scope and evidence**

The application is in `giraffix-backend`; the parent workspace is not itself a Git repository. The repository has 137 tracked files, 81 production Java files, 22 test Java files, and 11 Flyway migrations. No applicable AGENTS.md was found. Reviewed areas include build/configuration, authentication, CRUD, uploads, concept extraction/review, both selection policies, generation/review, asset delivery, migrations, tests, and existing documentation.

The existing Maven suite passed freshly: **220 tests, zero failures, zero errors, zero skipped; BUILD SUCCESS; 1 minute 41 seconds**. Evidence: [test log](../target/analysis-2026-11-18-tests.log), with individual reports under `target/surefire-reports`.

```powershell
mvn -o -s target/project-review-maven-settings.xml '-Dmaven.repo.local=D:\PROJECTS\GIRAFFIX\.m2\repository' test
```

This used installed Maven and the existing local cache/settings. It establishes a successful local test build, not a fresh online dependency resolution or Maven-wrapper verification. Tests use H2 and local HTTP servers/mocks; no live Google login, application PostgreSQL connection, paid AI request, or production S3 mutation was performed. No vulnerability scan, load test, deployed-environment audit, or visual assessment of live generated ads was performed.

**Architecture and dependencies**

| Area | Implementation | Assessment |
| --- | --- | --- |
| Runtime/build | Java 17 target, Spring Boot 4.1.2, Maven | One deployable application; no frontend in this workspace |
| HTTP | Spring MVC, Bean Validation, springdoc 3.2.0 | JSON and multipart APIs with Swagger documentation |
| Identity | Google OIDC, Spring Security, HTTP sessions | Verified email required when persisting a Google account |
| Persistence | PostgreSQL, JPA for core resources, JDBC for workflow state | Flyway owns migrations; Hibernate validates mappings |
| Assets | AWS SDK 2.42.38, S3 | Product photos, logos, concept references, generated output |
| AI analysis | Claude HTTP adapters and versioned prompt/schema resources | Local response validation supplements provider structured output |
| Selection | Durable database requests, batches, attempts, evaluations, allocations | Two policies; default is product concept picker |
| Generation | Claude creative prompt, OpenAI image edits adapter, Claude output review | Durable phases with leases, fencing, usage accounting and private delivery |

Code is organized by feature: `user`, `brand`, `product`, `concept`, `selection`, `generation`, plus configuration and shared image processing. Core CRUD follows controller/service/repository. Concept analysis and generation expose provider interfaces, which improves testing. Product analysis remains embedded in the much larger `ProductService`.

**User and library workflows**

The main journey is Google sign-in → create a brand/optional logo → analyze one to five photos of one product → review/edit suggestions → save product and photos → POST `/api/v1/ads` with an idempotency key, product ID, aspect ratio and optional quality → poll status → retrieve the authenticated final image.

Analysis returns suggestions without persisting a product or photos. The client must retain and resubmit files when confirming the product. JSON-only product creation remains possible, but selection requires an uploaded owned S3 photo; a legacy external image URL alone is insufficient.

The library journey is upload a reference image → extract a concept → save its initial DRAFT revision → configured administrator approves it → selection can use that revision. A usable extraction is not automatically approved. Concepts are a shared authenticated library; brand/product ownership does not imply tenant-private concepts. The local configuration does not supply an administrator allowlist, so approval requires external configuration.

| API family | Main capabilities |
| --- | --- |
| `/api/v1/users/me`, `/api/v1/security/csrf` | Current account/onboarding; mutation token |
| `/api/v1/brands` | Owned brand list/create/read/update; JSON or multipart logo mutations |
| `/api/v1/brands/{id}/products`, `/api/v1/products/{id}` | Owned product CRUD and image append |
| `/api/v1/analyze` | Combined product analysis from multipart images |
| `/api/v1/concepts`, concept revisions | Paginated library, extraction, administrative revision/approval/revocation |
| `/api/v1/selection-requests` | Selection submission and polling; accepts legacy minimal requests |
| `/api/v1/ads` | Integrated ad submission and polling |
| Product creations; `/api/v1/creations/{id}/image` | Paginated history and authorized image bytes |

**Selection and generation behavior**

Default picker policy samples up to five approved concepts per round, requires STRONG classification and score at least 80 for an immediate winner, and allows three rounds. It excludes occupied concepts, those already seen in the request, and permanent product/concept exclusions. If a strong winner is unavailable after rounds/library exhaustion, it can choose the highest-scoring available evaluation through fallback.

Java validates candidate membership, revision identity, score bounds, classification, adaptation text, and exact below-threshold exclusion membership. It does not independently recompute the prompt's six-factor score: those factor scores are not returned. Model scores are judgments, not calibrated probabilities.

Two documented policy choices deserve product attention: fallback can choose NOT_SUITABLE, and below-threshold exclusions survive product edits and concept revisions. There is no exclusion reset API. Consumed concepts remain occupied for that owner/product. The older weighted-fit policy remains available through configuration and adds independent verification with five dimensions and evidence pointers.

Selection snapshots input context and policy, enforces owner-scoped idempotency, persists attempts and conservative cost reservations, then atomically writes the winning allocation, generation job, and outbox event. The dispatcher polls database tables directly; the presence of an outbox table does not imply an external message broker or separate delivery service.

For an ad request, generation writes a versioned creative prompt, renders from actual product photos and optional logo, validates image format/size and actual aspect ratio, saves output to S3, reviews fidelity, then publishes a creation. Successful output is available through an authenticated endpoint with `no-store` and `nosniff`. Product/brand context, concept approval, job fence and deadline are rechecked at publication. A separate renewal thread maintains generation leases during long calls.

Prompt failure with a known outcome has a distinct PROMPT_FAILED state and releases occupancy. Uncertain dispatch/storage/review outcomes become RECONCILIATION_REQUIRED and preserve occupancy, avoiding blind duplicate generation. This conservative choice needs operational tooling, described below.

**Prioritized findings**

These are source-supported risks; failure scenarios not exercised by the existing suite are not presented as fresh runtime reproductions.

| Priority | Finding and source | Consequence and next action |
| --- | --- | --- |
| High | Local `application.properties` contains literal OAuth/database/AI/AWS credentials. Committed HEAD uses environment placeholders instead. | Local resources are copied into build output, so populated files/artifacts can disclose credentials. Restore external injection and audit artifact handling. Rotate any credentials that have been shared or otherwise exposed. This review does not establish a committed-secret leak. |
| High | Minimal selection requests omit aspect ratio, but still create a generation job: [SelectionController](../src/main/java/com/giraffix/selection/SelectionController.java), [SelectionWorker](../src/main/java/com/giraffix/selection/SelectionWorker.java). [OpenAiImageGenerator](../src/main/java/com/giraffix/generation/OpenAiImageGenerator.java), line 48, requires both ratio and completed prompt. | With the real provider configured, this accepted legacy request skips prompt creation, enters GENERATING, fails locally and is classified as an uncertain outcome, retaining its reservation. Define selection-only behavior or normalize a default ratio/prompt before creating the job. Add a real-adapter regression test for the documented minimal request. |
| High | Product uploads precede database flush without compensation: [ProductImageService](../src/main/java/com/giraffix/product/service/ProductImageService.java), line 112. | A later PUT, flush or commit failure can orphan earlier objects. Add durable upload/cleanup intent and retryable compensation. Include uncertain PUT outcomes, not just explicit upload errors. |
| High | Image append reads current count/bytes/positions without a common mutation lock: `ProductImageService`, lines 59–62; `Product.addImage`. | Concurrent appends can select identical positions, upload objects, then fail the unique constraint and leave orphans. Coordinate append/delete through a per-product database boundary and recheck limits there. |
| High | Product deletion removes S3 data before database commit: [ProductService](../src/main/java/com/giraffix/product/service/ProductService.java), line 507. | Partial S3 deletion or database failure can leave a retained product with missing photos. Implement deletion state plus durable cleanup rather than treating S3 and PostgreSQL as one transaction. |
| High | No per-account/global request quota, queue admission limit, or aggregate paid-usage cap found. | New idempotency keys create new cost opportunities; per-job estimates do not control total spending. Add persisted aggregate budgets, rate/concurrency limits and queue capacity before wider use. External gateway controls were not inspected. |
| High | RECONCILIATION_REQUIRED has no implemented operator resolution endpoint/worker. The generic generation catch also covers deterministic failures after response receipt. | Occupancy can remain indefinitely after provider rejection, invalid output, stale publication context or unknown remote outcomes. Classify known failures separately; add audited inspection, reconciliation and safe release actions. Never blindly replay uncertain billed operations. |
| Medium | One scheduled loop runs selection requests serially before generation: [SelectionDispatcher](../src/main/java/com/giraffix/selection/SelectionDispatcher.java), line 20. | Slow selection delays all generation. Queue time consumes the 90-second selection deadline and the generation deadline. Introduce fair, bounded concurrency and separate work classes. |
| Medium | Product and concept extraction use builders without explicit application connect/read deadlines: `ProductService`, line 80; `ClaudeConceptAnalyzer`, line 48; `HttpClientConfig`. | Slow providers can occupy HTTP request threads. Set explicit transport timeouts and an overall request policy. Other adapters already specify limits. |
| Medium | WebP fallback checks RIFF/chunk envelope without decoding dimensions: [ImageProcessor](../src/main/java/com/giraffix/shared/image/ImageProcessor.java), lines 38 and 77. Native JPEG/PNG/GIF paths inspect headers and return original bytes without full decoding. | WebP bypasses the advertised dimension checks; malformed payloads can reach storage/provider calls. Use a decoder/validated parser and verify payload integrity for accepted formats. |
| Medium | Prompt writing uses selection-policy token rates while its model is independently configurable: [ClaudeAdPromptWriter](../src/main/java/com/giraffix/generation/ClaudeAdPromptWriter.java), line 70. OpenAI rates are independently configurable estimates. | The ledger is an estimate whose accuracy depends on matching each model's rates. Snapshot prompt/render/review model identities and their own rates. Current estimated budgets are not provider-side hard spend caps. |
| Medium | Product/concept/logo responses use permanent S3 URLs; only final creations have authorized delivery here. | Private buckets require a separate input-preview mechanism; public buckets imply a different privacy contract. Implement authorized input reads or short-lived URLs according to the intended product design. |
| Medium | Product editing has neither optimistic version checks nor an explicit update lock. Brand update does use a pessimistic lock. | Concurrent product edits can overwrite newer values. Add version/conflict handling and a consistent mutation strategy. |
| Medium | No CI, deployment/container setup, production profile, shared session persistence or cleanup/retention worker found. | Production behavior and recoverability are not reproducibly established. Add deployment checks, disposable PostgreSQL CI, queue metrics, retention and backup/restore procedures. |

**Security, privacy and API quality**

Useful existing protections include authenticated API access, CSRF on mutations, HTTP-only session cookies, SameSite Lax, Google verified-email checks, resource ownership checks, admin-subject checks for concept review, parameterized SQL for user values, constrained S3 deletion paths, asset fingerprints, and authenticated final-image delivery. Ownership failures generally return 404.

Swagger is public by configuration. Session cookies have no explicit secure setting in this local file; HTTPS/proxy behavior must be verified in the deployment. No CORS policy or frontend exists here, so cross-origin browser integration needs an explicit design. An external reverse proxy or identity/session service may supply behavior not represented by this repository.

Product descriptions, key features and legacy URLs lack practical DTO size bounds. PATCH uses null as unchanged, so clients cannot explicitly clear nullable fields. Error handling uses ResponseStatusException and local responses rather than one consistent application error contract. Apply bounded input lengths and a predictable client-facing error format.

Local configuration enables SQL output and DEBUG logging of extracted product fields. Logs can therefore contain commercial product data. Request correlation covers selected analysis/upload/delete routes, not every HTTP request or the main ad endpoints. `ClaudeProperties` is a record with a generated representation containing the key; avoid logging it wholesale and consider redaction.

Model names differ between local configuration, adapter defaults and some documentation. The test suite overrides model identifiers and simulates provider responses, so it cannot establish account model access, accepted canvas sizes, current request contracts, or real pricing. Validate those in a separately authorized live integration check; no external model-availability claim is made here.

**Database and lifecycle assessment**

V1–V4 establish users/brands/products, image metadata and the concept library. V5 introduces revision history, selection attempts/batches/evaluations/cache, allocations/claims, generation jobs, outbox and creations. V6 adds integrity checks; Java V7 adds a PostgreSQL partial unique index for active allocations; V8 adds picker scores and exclusions; V9 adds logos; V10 adds prompt metadata; V11 adds quality and failure fields.

The claims primary key, unique allocation/job relationships, stable concept locks, owner-scoped idempotency, persisted snapshots, leases and fences are strong safeguards. H2 tests do not execute the PostgreSQL-only V7 index creation; run the concurrency/migration suite against disposable PostgreSQL in CI.

Automatic Flyway baselining remains enabled for legacy adoption. Disable it after deliberate adoption of the intended schema; a baseline records a starting version without proving that all prior constraints/indexes exist. Hibernate validation does not cover every integrity rule.

Historical `product_images.image_data` is retained by migrations but is not mapped/served by the current application. Existing rows without S3 URLs need a deliberate migration path if present. Several workflow records intentionally retain product IDs without product foreign keys. Product deletion can preserve generation history and creation objects while public reads require that the product still exists. Define archival access and cleanup together.

One approved revision per concept is enforced by application locking, without a matching database partial unique constraint. Consider database defense if scripts or future services will write revisions. Brand-logo replacement and concept creation already have best-effort rollback cleanup; process interruption and cleanup failure still need durable retry handling.

**Performance and maintainability**

Brand/product lists are unpaginated, and mapping products traverses lazy images, creating a potential N+1 query pattern. Add bounded pagination and explicit fetch plans. Concept/history lists already use pagination. Candidate sampling uses `ORDER BY RANDOM()`; measure this as the library grows.

Snapshots fingerprint S3 assets, and provider stages reread images for selection, prompting, rendering and review. This improves identity checking but adds latency and memory/base64 amplification. Budget those reads separately from HTTP-provider timeouts. Picker/reviewer HTTP limits are fixed rather than derived from remaining job time; publication checks prevent late success, but they do not prevent every late paid call or long asset read.

Product upload/delete hold database transactions while calling S3. Move external I/O outside long locks where possible while preserving durable intent and ownership checks. Generation polling lacks a dedicated status/lease queue index in the current migrations; measure its query plan as history grows. Waiting-for-provider jobs have no explicit next-attempt backoff.

Extract product analysis from `ProductService`, as already done for concepts. Replace compressed SQL/state transitions with named operations and clearer formatting. Persist render-model provenance, improve failure-stage messages, and measure queue age, rejection rate, estimated/actual usage, reconciliation backlog and cleanup failures. Attempts, snapshots, exclusions, outbox rows and generated objects need retention rules.

**Changes since earlier review documents**

The older findings that generation is an unavailable placeholder and that most application files are untracked no longer describe this checkout. The real OpenAI adapter is wired, the workflow source is tracked, and the suite now contains 220 tests. Committed configuration uses environment placeholders; local edits replace them with literal values. Generation now rechecks context/deadline before review/publication and renews leases. Brand updates now use a write lock, and logo replacement has commit-aware compensation. These improvements do not remove the current storage/reconciliation/admission gaps above.

**Recommended implementation order**

1. Restore external secret injection, inspect artifact handling, and rotate any credentials with known exposure. Preserve the user's local configuration intentionally when applying a later fix.
2. Resolve minimal selection-request generation semantics and add the real-adapter regression. Separate deterministic failures from unknown outcomes; provide audited reconciliation/release operations.
3. Make product upload/deletion durable and recoverable, and serialize image mutations. Test partial uploads, database commit failure, concurrent append, and append/delete races.
4. Add aggregate cost/admission limits, explicit extraction timeouts, strict image validation, and a defined private input-image delivery path.
5. Introduce fair bounded scheduling, independent model/rate provenance, and queue/usage/cleanup observability.
6. Add production configuration, CI against PostgreSQL, reproducible deployment, session handling, retention and recovery procedures. Then validate live integrations and creative quality on controlled examples.
