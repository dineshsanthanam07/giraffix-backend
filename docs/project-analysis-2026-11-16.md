Project analysis — 16 November 2026
=================================

Giraffix is a substantial backend for an AI advertising studio. Google sign-in, brand/product management, product image analysis, concept extraction and approval, and durable concept picking are implemented. End-to-end advertisement image generation is incomplete because there is no live `ImageGenerator` adapter. The application needs security, storage consistency, and operational recovery work before production use.

This review covers the current local working tree: 72 main Java files, 20 test Java files including the shared fixture, eight Flyway migration versions, 18 database tables, Maven configuration, HTTP endpoints, prompts, schemas, and documentation. There is no frontend in this workspace. Application code and runtime configuration were not changed. This report and the ignored test log are the review outputs.

The earlier [project analysis](project-analysis.md) describes an older state. In particular, the current default picker, V8 migration, and 182-test result supersede its legacy-selection description, seven-migration count, and 149-test result.

**Architecture and feature status**

The POM declares Java 17, Spring Boot 4.1.2, Spring MVC, Security/OAuth2, JPA, Flyway, PostgreSQL, AWS SDK S3, Jackson 3, Springdoc, and Actuator. These are repository declarations; dependency vulnerability status and upstream model availability were not assessed.

The application is a modular monolith. CRUD follows controller → service → JPA repository. Selection/generation use JDBC and explicit transactions because they coordinate durable jobs, ownership, leases, costs, and reservations. This is an appropriate structure for the present scope; the immediate defects can be addressed within it.

| Area | Implemented behavior | Remaining boundary |
| --- | --- | --- |
| Accounts | Google OIDC, verified-email check, account persistence, session login/logout | Live login and deployment session strategy unverified |
| Brands | Owner-scoped create/read/update/list; creating a brand completes onboarding | No pagination or edit conflict detection |
| Products | Owner-scoped CRUD, validation, JSON or multipart creation | Storage transactions and concurrent mutations need work |
| Product analysis | Analyze 1–5 views together; return editable details without saving | Explicit extraction timeouts and aggregate usage controls absent |
| Image storage | S3 uploads, metadata in PostgreSQL, constrained deletion keys | Product uploads lack compensation; input-image delivery depends on bucket setup |
| Concept library | Extract/validate a concept, store image and analysis, paginated browsing | Creation/read shared by all authenticated users; provenance could improve |
| Concept review | Draft revisions, admin-subject allowlist, approval/revocation | Admin subjects must be configured; none are supplied in the local properties |
| Concept selection | Async requests, idempotency, snapshots, budgets, scoring, exclusions, reservation | Serial scheduling, admission limits, and retention need attention |
| Image generation | Durable jobs, provider port, private asset storage, output review, creation history | Default provider is unavailable; reconciliation tooling absent |

The main user journey is: sign in → create brand → analyze product photos → review/edit suggestions → save product/photos → request concept selection → poll status → generate/review an image once a provider is supplied → retrieve a private creation.

The independent library journey is: upload a reference → extract its creative idea → save a draft → administrator reviews/approves a revision → selection can sample that revision. An extracted concept marked `usable` is not automatically approved.

**Current selection behavior**

The default policy is `concept-picker-v1`, implemented in [SelectionWorker](../src/main/java/com/giraffix/selection/SelectionWorker.java), [ClaudeConceptPicker](../src/main/java/com/giraffix/selection/ClaudeConceptPicker.java), and [ConceptPickerRules](../src/main/java/com/giraffix/selection/ConceptPickerRules.java).

1. A session-authenticated, CSRF-protected POST requires an owner-scoped `Idempotency-Key` and product ID. Intent and format have defaults. Reusing the key with different input returns 409.
2. The backend captures saved product/brand facts and fingerprints owned S3 product photos. Name/category and an uploaded photo are prerequisites.
3. It randomly samples up to five approved concepts per round, excluding occupied concepts, concepts seen in this request, and saved product/concept exclusions.
4. Claude receives all saved product views, product/brand data, and concept cards. Java validates exact candidate membership, revision identity, score bounds, classifications, adaptation text, and the exclusion list.
5. A `STRONG` result scoring at least 80 can win immediately. Otherwise, there are up to three rounds. If no qualifying result exists, the highest score among evaluated, available candidates can win through fallback.
6. Below-threshold pairs are permanently excluded for that product ID. The generation brief, allocation, job, and outbox record are committed together.

Two intentional product decisions deserve visibility: fallback can select `NOT_SUITABLE`, and exclusions survive product edits, concept revisions, and threshold changes. These are explicitly documented in [concept-picker.md](concept-picker.md), rather than implementation defects. The API exposes `fallbackUsed`; clients should communicate that distinction. There is no exclusion reset API.

Picker mode skips the legacy independent five-dimension verification stage. The old weighted scoring/verification path remains available through `picker-enabled=false`, and queued requests retain their original policy snapshots. Output review still applies after image generation. `SELECTED` means a concept and generation job exist; it does not mean an image exists.

**Prioritized findings**

These findings are based on current source inspection. The regression run below is fresh; diagnostic reproductions recorded in the older analysis were not rerun or counted as new evidence.

| Priority | Finding and evidence | Effect and recommended action |
| --- | --- | --- |
| Critical | Plaintext credential values in [application.properties](../src/main/resources/application.properties): OAuth client secret, database password, AI API key, AWS access credentials | If valid, revoke/rotate them and supply secrets externally. The resource is copied into build output even while untracked. Do not commit or distribute populated configuration or artifacts. Values are deliberately omitted from this report. |
| High | Only 26 files are tracked; `git status` lists most concept, selection, generation, storage, tests, migrations, Maven wrapper, and configuration as untracked | A clone cannot reproduce the reviewed application. Deliberately version the required files after removing secrets and excluding local artifacts. No staging/commit was performed in this review. |
| High | Generation is an integration placeholder: [GenerationConfiguration](../src/main/java/com/giraffix/generation/GenerationConfiguration.java), line 12, returns `available=false` | Normal selections create jobs that wait for a provider and eventually expire. Implement an adapter with operation identity, bounded cost/time, and recovery semantics before claiming end-to-end ad generation. |
| High | Product uploads precede database flush with no compensation: [ProductImageService](../src/main/java/com/giraffix/product/service/ProductImageService.java), lines 112–118 | A second upload failure, rollback, or commit failure can leave orphaned S3 objects. Persist recoverable upload intent/cleanup work and handle uncertain PUT outcomes. The concept module already performs best-effort compensation, though it also lacks durable cleanup retries. |
| High | Generation checks current facts before dispatch but only ownership/locks at final publication: [GenerationWorker](../src/main/java/com/giraffix/generation/GenerationWorker.java), lines 46 and 85 | A product/brand edit during generation or review can still yield `SUCCEEDED` against the older brief. Define immutable-version semantics or compare the captured context again under publication locks. |
| High | Generation deadline checked at entry, but not before review or publication: [GenerationWorker](../src/main/java/com/giraffix/generation/GenerationWorker.java), lines 39 and 73–97 | Work may publish after the job deadline while its lease is valid. Propagate remaining time to review and enforce deadline at each costly/publication boundary. The reviewer interface currently receives no deadline. |
| High | No application-level per-account/global quota, concurrency cap, or queue admission control found | Per-selection cost envelopes do not prevent repeated requests with new keys. Product/concept extraction have no comparable persisted budget. Add aggregate controls for the intended deployment; an external gateway may already supply some, but none is evidenced here. |
| Medium | Image append reads count/bytes without a product mutation lock: [ProductImageService](../src/main/java/com/giraffix/product/service/ProductImageService.java), lines 59–62; positions use collection size in `Product.addImage` | Concurrent appends can upload at the same position, then one fails on the unique constraint and leaves an object behind. Serialize append/delete per product and recheck limits within that boundary. |
| Medium | Product deletion removes S3 objects before database deletion/commit: [ProductService](../src/main/java/com/giraffix/product/service/ProductService.java), lines 507–510 | A failed database commit can retain metadata pointing at missing images. A partial S3 failure also leaves a partially deleted product until retry. Use durable deletion state and retryable cleanup. |
| Medium | Product and concept extraction use a default RestClient builder without explicit application timeout settings: `ProductService`, line 80; `ClaudeConceptAnalyzer`, line 48 | Slow upstream calls can tie up request threads. Configure explicit connect/read limits and an overall time policy. Picker/review clients already configure bounded HTTP timeouts. |
| Medium | Decoder-free WebP fallback only validates a RIFF envelope: [ImageProcessor](../src/main/java/com/giraffix/shared/image/ImageProcessor.java), lines 38 and 77–85 | It does not validate dimensions or encoded payload, so documented image limits are inconsistent. Use a decoder or validated parser/content checks. Native JPEG/PNG/GIF paths inspect dimensions but also return bytes without full decoding; metadata acceptance does not establish payload integrity. |
| Medium | Dispatcher processes selection serially before generation; generation polls oldest ten including immediately eligible waiting jobs: [SelectionDispatcher](../src/main/java/com/giraffix/selection/SelectionDispatcher.java), line 22; `GenerationWorker`, line 22 | Selection queue time consumes its 90-second deadline. Slow selection delays generation; older waiting jobs can monopolize polling. Add fair eligibility/backoff and bounded concurrency with separate work classes. |
| Medium | Recovery stops at `RECONCILIATION_REQUIRED`, with no operator API or automated reconciliation worker found | Unknown external outcomes correctly preserve occupancy, but can leave a product/concept reserved indefinitely. Add inspect/reconcile/release operations that preserve provider billing and allocation guarantees. |
| Medium | Product/concept responses expose permanent S3 URLs, with no authenticated input-image delivery endpoint | Private buckets need a separate image delivery arrangement for browser previews. Creations already have authenticated delivery. Define the input asset privacy contract and implement authorized reads or short-lived URLs as needed. |
| Medium | Production configuration and delivery automation are incomplete in the workspace | No CI/deployment/container specification, shared session store, retention worker, or queue metrics found. Local settings enable automatic Flyway baselining, SQL output, and extracted-field DEBUG logging. Establish production profiles and operational checks before deployment. |

**Security and API assessment**

The existing protections are useful: `/api/v1/**` requires authentication; session cookies are HTTP-only with SameSite Lax; CSRF is enabled for mutations; ownership is enforced for brands, products, selections, and creations; ownership violations generally return 404. S3 product deletion validates the configured bucket URL and product key prefix before removing anything. Creation images use authorized reads, `no-store`, and `nosniff`.

Concept extraction/browsing is a shared authenticated library. Revision administration uses a configured identity-subject allowlist. This distinction should remain explicit if concepts are intended to become tenant-private. Public Swagger documentation is configured intentionally; exposure policy should be decided for deployment.

HTTPS termination, secure-cookie enforcement, proxy configuration, cross-origin frontend access, bucket IAM, backup access, and session behavior across replicas cannot be established from the local files. Their absence here does not prove an insecure external deployment.

README setup says to set `ANTHROPIC_API_KEY`, but the current property contains a literal value and has no `${ANTHROPIC_API_KEY}` placeholder. That named environment variable does not express the configured property path on its own. Align the documented secret injection mechanism with the actual binding and validate required configuration at startup. S3 properties redact credentials in `toString`; `ClaudeProperties` has the generated record representation, so it should not be logged wholesale.

The API lacks a centralized error contract. PATCH treats null as unchanged, so nullable fields cannot be explicitly cleared. Description/key-feature fields and legacy URLs have no DTO length bounds; upstream token preflight limits selection dispatch, but do not bound CRUD storage. Define practical sizes before exposing broad usage.

**Database and concurrency assessment**

| Migration | Purpose |
| --- | --- |
| V1 | Users, brands, products, ownership links and indexes |
| V2 | Product image metadata and historical database image bytes |
| V3 | S3 storage URL; historical image bytes become nullable |
| V4 | Concept records and extraction metadata |
| V5 | Revisions, selection requests/batches/attempts/evaluations/cache, allocations/claims, generation jobs, outbox, creations |
| V6 | Rating fields, winner relationship, validation constraints |
| V7 | PostgreSQL-only partial unique index for active allocations |
| V8 | Picker scores/classifications, fallback flag, permanent product/concept exclusions |

Flyway owns schema changes and Hibernate validates mapped columns. Automatic baseline-on-migrate is still enabled for legacy adoption; disable it after deliberate adoption of the intended database. Baseline and Hibernate validation do not repair all missing constraints or indexes.

The workflow has thoughtful safeguards: owner-scoped idempotency, persisted policy/context snapshots, bounded attempts and token accounting, conservative charges for unknown outcomes, leases with fencing, stable concept-row locks around approval/reservation, unique occupancy claims, and an additional PostgreSQL allocation constraint. Unknown generation dispatch is not blindly replayed.

Product and brand entities lack optimistic versions. Concurrent edits can overwrite one another. Image collection writes also need a common mutation boundary with deletion. Database transactions remain open during product S3 upload/delete, increasing connection occupancy during slow storage operations.

The database preserves historical `product_images.image_data`, but the current entity does not map or serve those bytes. Rows without S3 URLs cannot supply eligible selection images. If legacy rows exist, a deliberate migration/delivery path is required.

Workflow tables deliberately retain product IDs without product foreign keys. Deletion can retain jobs/history and S3 creations whose public reads require a product that no longer exists. Define retention, cleanup, and audit access together. Outbox records commit atomically with jobs, but dispatch polls the job tables directly; there is no separate external outbox delivery system to assume operationally.

One-approved-revision-per-concept is enforced through service locking rather than a database partial unique constraint. Add database defense if there will be other writers. Concept `schemaVersion=1` and prompt v4 may represent different version domains; store explicit prompt/model provenance rather than inferring either from the other.

**Performance and maintainability**

- Brand/product lists are unpaginated. Product response mapping traverses lazy image collections and may produce an additional query per product. Introduce bounded lists and a suitable fetch strategy as volume grows.
- `ORDER BY RANDOM()` scans/sorts candidate pools. It is acceptable for a small library, but should be measured before growing the reference collection substantially.
- Snapshotting reads and fingerprints S3 assets. The picker rereads product images; selected concept references are fingerprinted for generation. Review reads the product, reference, and generated images again. Measure S3 latency and use immutable asset identity/caching where justified.
- Large configurable picker batches remain subject to token/cost limits; accepting a batch-size setting up to 100 does not guarantee that such a request fits the envelope.
- `ProductService` combines provider protocol, extraction validation, logging, CRUD, and deletion. Extract an analyzer boundary consistent with the concept module.
- Complex selection/generation transitions mix SQL, locking, and provider coordination, often in compressed statements. Introduce named transition/query methods and clearer formatting before extending the workflow.
- Durable ledgers, attempts, cache rows, exclusions, outbox records, and objects lack an implemented retention policy. Queue age, failure/reconciliation counts, estimated/actual usage, and cleanup backlog need operational metrics.
- Log correlation exists for analysis/storage HTTP routes and selection jobs. The README's claim that each HTTP request gets `X-Request-ID` is broader than the filter: it only covers selected POST/DELETE routes. Align that claim or expand coverage.
- README describes a 21 MB multipart request limit and 5 MB application file limit; the transport per-file setting is currently 50 MB. The 21 MB request cap still limits the request, but align the settings/documentation to avoid confusion.

**Verification and limits**

The complete existing Maven regression suite passed on Java 17.0.12 and Maven 3.9.9: **182 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS; approximately 64 seconds**.

Evidence: [analysis-2026-11-16-tests.log](../target/analysis-2026-11-16-tests.log). Command used with the existing local dependency cache/settings:

```powershell
mvn -o -s target/project-review-maven-settings.xml "-Dmaven.repo.local=D:\PROJECTS\GIRAFFIX\.m2\repository" test
```

The settings file is an ignored workspace artifact that matches cached Maven repository metadata. It is not a portable build prerequisite. The wrapper itself was not exercised; the installed Maven was used. Successful cached tests do not establish that a fresh checkout can resolve/build all declared dependencies.

The suite checks ownership, validation, analysis schemas, image processing/storage behavior, migrations, concept revisions/approval, legacy scoring, picker defaults/thresholds/fallback/exclusions, idempotency, cost/deadline behavior, reservation concurrency, stale context/fencing, interrupted attempts, generation scaffolding, and private creation delivery. Provider HTTP tests use a local server; S3 and generation are mocked where appropriate. This run used isolated test database settings, not the application PostgreSQL database.

No live OAuth, paid AI calls, production S3 operations, model availability check, image generation adapter, load test, vulnerability scan, production startup/deployment, or fresh PostgreSQL run was performed. The PostgreSQL test results in existing selection documentation are historical evidence, not a result of this review. Passing tests do not resolve the source-level gaps above; several require focused regression coverage when fixed.

**Recommended work order**

1. Rotate real exposed credentials; externalize secrets; review required untracked files and make the application reproducible without shipping secrets or generated output.
2. Define creation behavior across product edits; enforce generation deadlines through review/publication; add focused regression coverage for these boundaries.
3. Make product upload/deletion recoverable and serialize image mutations. Cover partial uploads, commit failure, concurrent append, and append/delete races.
4. Add explicit extraction timeouts, aggregate usage/admission controls, strict image validation, and a defined private input-image delivery path.
5. Implement the generation adapter and operator reconciliation together. Introduce fair queue scheduling before increasing throughput.
6. Establish production configuration, CI with disposable PostgreSQL, deployment/session handling, backup/restore checks, retention, and metrics. Then validate live integrations and creative output quality in a controlled environment.
