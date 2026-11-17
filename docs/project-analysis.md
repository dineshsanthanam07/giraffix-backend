Project analysis — 15 November 2026
==================================

Giraffix has a substantial, tested backend for an AI advertising studio. Product onboarding and analysis, concept extraction and review, and durable concept selection are implemented. Live image generation remains an integration gap. The current working tree needs security and reliability work before production use.

This assessment covers the local backend, its configuration, seven migrations, prompts and schemas, and tests. There is no frontend in this workspace. No application source was changed during the review. Provider credentials were not validated or used for live calls.

**Scope and stack.** The project contains 70 main Java files (3,955 lines), 18 test classes (2,049 lines including the shared fixture), and 17 database tables. The POM declares Java 17, Spring Boot 4.1.2, Spring MVC/Security/OAuth2, JPA, JDBC, Flyway, PostgreSQL, AWS SDK S3, Jackson 3, Springdoc, and Actuator. Those are repository declarations; this review does not assert current upstream compatibility or vulnerability status.

**How the application works.** The code is a modular monolith organized by feature. Ordinary CRUD uses controller → service → JPA repository. Selection and generation primarily use JDBC and explicit short transactions to coordinate durable state. Keeping these components in one application is reasonable at this stage; extracting services would add operational complexity without resolving the issues below.

| Module | Responsibility | Assessment |
| --- | --- | --- |
| `user` / `config` | Google OIDC login, account persistence, sessions, CSRF | Core protection exists; deployment configuration needs hardening |
| `brand` | Owned brand CRUD and onboarding | Straightforward implementation; listing is unbounded |
| `product` | Owned product CRUD, image preparation, analysis, S3 uploads | Functional; weakest storage consistency and concurrency boundaries |
| `concept` | Reference extraction, shared library, immutable revisions, approval | Good analyzer/storage interfaces and upload compensation |
| `selection` | Context snapshots, random discovery, scoring, verification, reservation | Strongest reliability design; tightly coupled SQL and dense code |
| `generation` | Provider dispatch, output storage/review, creation history | Durable scaffolding exists; provider and operator recovery are missing |

The normal journey is:

1. Sign in with Google; create a brand, which completes onboarding.
2. Upload product photos to `/api/v1/analyze`; Claude returns editable suggestions without persistence.
3. Confirm the edited details and photos through multipart product creation; save metadata in PostgreSQL and images in S3.
4. Upload advertising references to the concept library. Extraction produces draft revisions; a configured administrator approves eligible revisions.
5. Submit a selection request with intent, format, controls, and an idempotency key. Optionally name a particular concept.
6. Screen candidates, independently verify the proposed adaptation against images, then atomically reserve a concept and enqueue generation.
7. With an actual generation adapter installed, generate and review an output before committing a private creation and consuming the allocation.

`SELECTED` means a verified brief and reservation, not a generated advertisement. Without a provider, jobs become `WAITING_FOR_PROVIDER`.

**API inventory.** All `/api/v1/**` endpoints require an authenticated session. State-changing requests require CSRF protection.

| Routes | Main behavior |
| --- | --- |
| `GET /api/v1/users/me`, `GET /api/v1/security/csrf` | Current account and CSRF token |
| `GET/POST /api/v1/brands`, `GET/PATCH /api/v1/brands/{id}` | Owned brand management |
| `GET/POST /api/v1/brands/{id}/products` | Product listing and JSON/multipart creation |
| `GET/PATCH/DELETE /api/v1/products/{id}` | Owned product management |
| `POST /api/v1/products/{id}/images`, `POST /api/v1/analyze` | Photo attachment and analysis |
| `GET/POST /api/v1/concepts`, `GET /api/v1/concepts/{id}` | Shared reference library and extraction |
| Concept revision endpoints ending in `/revisions`, `/approve`, `/revoke` | Administrator review |
| `POST /api/v1/selection-requests`, `GET /api/v1/selection-requests/{id}` | Asynchronous selection and status |
| `GET /api/v1/products/{id}/creations`, `GET /api/v1/creations/{id}/image` | Owned creation history and authorized image delivery |

Product/brand ownership failures return 404. Concept reads are deliberately shared: the current API exposes original extracted records, including drafts and unusable references, to any authenticated user. Approval restricts selection eligibility, not library visibility. Decide explicitly whether this matches the intended product before onboarding multiple organizations.

**What is well designed.**

- Session authentication, CSRF, verified Google emails, and server-managed ownership are already implemented. Creation delivery reauthorizes access and uses `no-store`.
- Analysis produces suggestions before users commit a product. This keeps extraction separate from user confirmation.
- Concept analyzer and storage interfaces allow adapters to be replaced. Concept creation compensates for database failure after uploading to S3.
- Local validation checks provider output rather than trusting structured-output requests alone. Concept validation covers enums, bounds, null relationships, and transfer-form consistency.
- Selection persists immutable context and policy snapshots, ordered batches, attempts, usage, evaluations, and reservations. Retries and restart recovery are intentional.
- Idempotency is owner scoped. Worker leases and fencing prevent stale workers from publishing state. Unknown generation outcomes retain occupancy for reconciliation.
- Scoring uses explicit integer arithmetic: weights `30/25/20/15/10`, ratings `0–4`, minimum rating `3`, and threshold `80`. Violations and uncertainty prevent acceptance.
- Automatic selection samples at most ten concepts per round for three rounds by default. Verification is independent and cannot rewrite the screened adaptation.
- Allocation claims and PostgreSQL's partial unique index provide complementary protection against duplicate active allocations.

**Priority findings.** Severity reflects likely impact in this codebase. Findings identified by inspection are distinguished from the targeted reproductions recorded below.

| Priority | Finding | Evidence and practical effect |
| --- | --- | --- |
| Critical | Plaintext credentials in runtime configuration | [application.properties](../src/main/resources/application.properties), lines 6, 12, 39, 61–62, contains an OAuth client secret, database password, Anthropic key, and AWS credentials. Values are intentionally omitted here. Maven packages this resource into build output. If real, rotate/revoke them and move configuration to externally supplied secrets. Being untracked does not keep them out of built artifacts. |
| High | Much of the implementation is untracked | `git status` shows concept, selection, generation, migrations, tests, wrapper/configuration files, and other supporting code as untracked. Only 26 files are currently tracked. A normal commit of tracked edits or clone will not reproduce the reviewed working tree. Review and deliberately version required files after removing secrets. |
| High | Product image writes have no compensation | [ProductImageService.java](../src/main/java/com/giraffix/product/service/ProductImageService.java), lines 112–118, uploads before flushing the database, with no cleanup registration. A later upload failure or database rollback leaves earlier successful objects orphaned. Unlike concept uploads, product uploads also do not compensate for a PUT with an uncertain remote outcome. |
| High | Generation can publish against stale product facts | [GenerationWorker.java](../src/main/java/com/giraffix/generation/GenerationWorker.java), lines 46 and 85, checks the full context before dispatch, but only locks/rechecks ownership and approval at final creation commit. Product or brand facts can change during generation/review and the job can still succeed against the old brief. Reproduced with the existing mocked workflow fixture. If this is intentional snapshot behavior, document it; otherwise compare the context again at publication. |
| High | Generation deadline is not enforced throughout execution | [GenerationWorker.java](../src/main/java/com/giraffix/generation/GenerationWorker.java), lines 39, 56, 81–97. The worker checks deadline on entry and passes it to the generator, but does not recheck before review or final publication. The reviewer interface receives no deadline. A short remaining deadline can be exceeded while the 150-second lease is still valid. |
| High | Aggregate AI usage is unbounded in the application | Selection has per-request envelopes, but there is no implemented per-user/global quota or admission limit. Product and concept extraction also lack a comparable persisted budget. Authenticated users can repeat costly requests; idempotency does not constrain new keys. Add account-level quotas, concurrency limits, and queue admission appropriate to the intended deployment. An external gateway may provide controls, but none is evidenced here. |
| Medium | Concurrent image append requests race | [ProductImageService.java](../src/main/java/com/giraffix/product/service/ProductImageService.java), lines 59–62, checks counts and positions using an unlocked collection. [Product.java](../src/main/java/com/giraffix/product/entity/Product.java) assigns positions from collection size, and the repository has no locking method. Concurrent appends can both upload, then one fails on the unique product/position constraint, leaving an orphan. Serialize mutations per product and repeat limits under the lock. |
| Medium | Product deletion is not recoverably coordinated with S3 | [ProductService.java](../src/main/java/com/giraffix/product/service/ProductService.java), lines 507–510, deletes objects before deleting/flushing database rows. A later database failure leaves retained metadata pointing to removed images. Existing tests cover partial S3 deletion and retry, but do not make the two systems atomic. A durable deletion state plus retryable cleanup would make recovery explicit. |
| Medium | Extraction clients have no explicit timeout policy | [ProductService.java](../src/main/java/com/giraffix/product/service/ProductService.java), line 80, and [ClaudeConceptAnalyzer.java](../src/main/java/com/giraffix/concept/adapter/ClaudeConceptAnalyzer.java), line 48, use a default client builder. No bounded request factory or application timeout configuration is supplied for them. Selection/review explicitly configure timeouts. Configure consistent connect/read/total time limits rather than relying on client defaults. |
| Medium | WebP bypasses image-content and dimension validation | [ImageProcessor.java](../src/main/java/com/giraffix/shared/image/ImageProcessor.java), lines 38 and 77–85, accepts a WebP RIFF envelope when no decoder is installed. This branch never checks dimensions or decodes the payload. The documented 8000-pixel/32-megapixel limits therefore do not apply consistently; invalid header-only content can be accepted. Use a decoder or a validated parser plus content checks. |
| Medium | Queue processing can delay or starve later jobs | [SelectionDispatcher.java](../src/main/java/com/giraffix/selection/SelectionDispatcher.java), line 22, processes selection serially before generation. Selection deadlines include queue time. [GenerationWorker.java](../src/main/java/com/giraffix/generation/GenerationWorker.java), line 22, selects the oldest ten, including `WAITING_FOR_PROVIDER` jobs that immediately become eligible again. Those jobs can occupy every poll until their deadlines expire. Separate work classes and use fair eligibility/backoff and bounded concurrency. |
| Medium | Private input images lack an application delivery route | Product and concept responses return ordinary S3 object URLs. A private bucket prevents browser display unless a separate delivery arrangement exists; making the bucket public undermines intended privacy. Creations already have an authorized image endpoint. Add authorized delivery or short-lived signed URLs for input assets as required by the frontend. |
| Medium | Production runtime and recovery are incomplete | No tracked CI pipeline, container/deployment specification, shared session store, retention worker, or reconciliation API/UI was found. The local default points at PostgreSQL and enables automatic baselining, SQL output, and DEBUG extracted-field logging. Split local/production settings; provide operator recovery before enabling live generation. |

**Database and consistency details.** V1 establishes users, brands, and products. V2 adds database-backed product images; V3 adds S3 references while retaining old bytes. V4 adds concepts; V5 adds revisions and the selection/generation ledger; V6 adds rating/winner integrity; V7 adds the PostgreSQL-only partial allocation index.

The historical `product_images.image_data` bytes survive migration, but the current entity no longer maps them. Rows without a storage URL cannot participate in selection, and no current HTTP endpoint serves their database bytes. Preserving data is different from retaining usable image access; plan a legacy-image migration if such records exist.

Workflow tables deliberately retain product UUIDs without foreign keys to `products`. Deleting a product can leave ledger history whose public endpoints become inaccessible because authorization requires the product to exist. This may be a valid audit choice, but deletion/retention semantics should be explicit.

Product and brand entities have neither optimistic versions nor explicit mutation locks. Concurrent PATCH requests can overwrite changes. Row locking in the workflow cannot prevent an ordinary edit from occurring immediately after the workflow transaction commits. Define whether creations are permanently associated with an immutable product version or must match current editable facts.

The revision API serializes approval changes using the stable concept row. There is no database partial unique constraint for a single approved revision per concept; the service currently enforces that invariant. Consider a database constraint as defense against future writers and manual changes.

`schemaVersion` is always initialized to `1` on concepts even though the extraction prompt identifies itself as v4. This may represent a separate schema version, but the application does not persist an explicit extraction prompt/model version for these records. Persist those identities separately to support provenance and re-extraction.

**Performance and maintainability.**

- Product and brand lists are unpaginated. Mapping each product's lazy images can also cause an additional query per product. Add bounded pagination and an appropriate fetch strategy when list sizes grow.
- `ORDER BY RANDOM()` is reasonable for a small library but becomes expensive as approved revisions grow. Measure query plans and selection acceptance before introducing retrieval infrastructure.
- Selection fingerprints candidate reference images even though screening itself only sends the first product view and text cards. Verification then rereads assets. This adds S3 traffic and latency; retain immutable fingerprints or safely cache reads if measurements justify it.
- Long S3 operations occur inside product upload/delete database transactions. Concept creation already shows a cleaner short-transaction pattern. Combine short transactions with durable compensation rather than copying only part of that pattern.
- `SelectionStore` exposes JDBC and JSON helpers publicly, and worker methods contain substantial inline SQL. Selection/generation classes compress complex transitions into long statements, making lock ordering and state changes harder to review. Format and separate transition/query responsibilities before expanding provider logic.
- `ProductService` combines extraction, provider protocol, logging, validation, CRUD, and deletion across 529 lines. Extract a product analyzer adapter, matching the concept module's interface boundary.
- There is no centralized error contract, and PATCH uses null to mean unchanged, so nullable fields cannot be explicitly cleared. Decide whether that API behavior is sufficient before adding clients.
- Actuator is present, but no custom queue/budget/reconciliation metrics or operational dashboards are configured. Session state is process local unless deployment adds another mechanism; multiple instances need an explicit session strategy.
- HTTPS termination, secure-cookie policy, CORS if the frontend uses a separate origin, bucket IAM, and authenticated health probing need deployment verification. Their absence from local files is not proof of an externally insecure deployment.

**Verification performed.** The full existing regression suite ran successfully on Java 17 using Maven 3.9.9: **149 tests, 0 failures, 0 errors, 0 skipped**. Evidence: [project-analysis-tests.log](../target/project-analysis-tests.log).

The initial offline run could not resolve some already-cached plugin artifacts because of repository-ID metadata. Reusing the existing workspace Maven settings solved that environment issue. Successful command in this workspace:

```powershell
mvn -o -s target/project-review-maven-settings.xml "-Dmaven.repo.local=D:\PROJECTS\GIRAFFIX\.m2\repository" test
```

This settings file is an existing ignored local artifact, not a portable project setup. A fresh environment should use the documented Maven/wrapper workflow with a configured writable cache and available dependencies.

The suite covers CRUD, ownership, validation, schema migrations, analysis contracts, image storage, concept approval/revisions, scoring, selection budgets/deadlines, idempotency, concurrency, cooldowns, fencing, interrupted attempts, generation scaffolding, and private creation delivery. Provider HTTP contracts use a local test server; generation and S3 behavior are mocked where appropriate. Passing these tests is strong regression evidence but does not establish live AI quality or deployment readiness.

Additional review probes were written only under ignored `target/` and use the existing isolated H2 workflow fixtures and mocked providers. Their source is [ProjectAnalysisProbe.java](../target/ProjectAnalysisProbe.java), with output in [project-analysis-probes.log](../target/project-analysis-probes.log). They are diagnostic reproductions, not additions to the regression suite.

| Probe | Observed result |
| --- | --- |
| Change product facts inside the mocked generation call, then accept the output review | `SUCCEEDED`; current product context is not compared at publication |
| Give a job a deadline two seconds away, then let its mocked review take 2.2 seconds | `SUCCEEDED`; the job publishes after its actual deadline while its lease remains valid |
| Submit a 20-byte RIFF/WebP envelope with no encoded image payload | Accepted as `image/webp` on the decoder-free fallback path |

This review did not rerun against real PostgreSQL. The existing [selection validation record](selection-validation.md) reports a prior isolated PostgreSQL run; that is historical evidence, not a result produced by this review. No real Google login, live Anthropic model availability, production S3 access/IAM, image generation provider, load test, dependency vulnerability scan, or production deployment was validated.

**Recommended order of work.**

1. Remove runtime secrets from source and build artifacts; rotate real exposed credentials. Review the working tree and commit the required implementation without secrets or generated files.
2. Define creation semantics for product edits, enforce generation deadlines at review/publication, and add focused regressions for those boundaries.
3. Make product image upload/deletion recoverable, serialize product image mutations, and test second-upload failure, database commit failure, concurrent append, and append/delete races.
4. Add explicit extraction timeouts, application quotas/admission limits, strict WebP validation, and authorized input-image delivery.
5. Choose and implement the generation provider with idempotent operations and operator reconciliation. Separate queue classes and establish fair scheduling before increasing throughput.
6. Establish production profiles, CI with disposable PostgreSQL checks, deployment/session configuration, retention and observability. Perform controlled live smoke tests and human creative-quality calibration.

The existing modular monolith is a suitable foundation. The immediate work is completing external integrations and closing consistency, security, and operational gaps; introducing microservices or a vector database is not required to address the confirmed issues.
