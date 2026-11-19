**Giraffix project analysis — 19 November 2026**

The current workspace implements a substantial AI advertising backend. Its strongest parts are ownership enforcement, explicit concept moderation, durable workflow checkpoints, and tests for interrupted paid work. The core flow is implemented and the local suite passes. Broad production readiness remains limited by secret handling, aggregate spending controls, operator recovery, and storage lifecycle consistency.

This analysis covers the current working tree, including its uncommitted and untracked implementation files. It is a source review and isolated build verification, not certification of a deployed service. Application code and configuration were not changed.

**Scope and fresh verification**

- Application: `giraffix-backend`; the parent workspace is not a Git repository.
- 88 production Java files, 28 test Java files, and 14 Flyway migrations: 12 SQL and two Java migrations.
- Reviewed build/configuration, identity/security, brand/product CRUD, image handling/storage, concepts and moderation, selection, generation/review/publication, prompt administration, migrations, tests, and operating documentation.
- No applicable `AGENTS.md` was found.
- Fresh result: **255 tests, zero failures, zero errors, zero skipped; BUILD SUCCESS; 1 minute 17 seconds**.

Command used:

```powershell
mvn -o -s target/project-review-maven-settings.xml '-Dmaven.repo.local=D:\PROJECTS\GIRAFFIX\.m2\repository' test
```

Evidence: [test log](../target/analysis-2026-11-19-tests.log), plus XML reports in `target/surefire-reports`. This used installed Maven 3.9.9, JDK 17, and the existing offline dependency cache. It did not verify a fresh dependency download or the Maven wrapper distribution. Tests use H2, mocks, and local HTTP fixtures. No live Google login, production PostgreSQL migration, paid AI request, or production S3 operation was performed. No dependency vulnerability audit or load benchmark was run.

**Architecture and responsibilities**

| Area | Implementation | Role |
| --- | --- | --- |
| Runtime | Java 17 target, Spring Boot 4.1.2, Maven | One deployable backend |
| HTTP | Spring MVC, validation, springdoc 3.2.0 | JSON/multipart APIs and Swagger |
| Identity | Google OIDC, Spring Security, server sessions | Login, current user, onboarding, CSRF |
| Persistence | PostgreSQL; JPA for core entities, JDBC for workflows | Business data, durable jobs, prompt revisions |
| Schema | Flyway V1–V14; Hibernate validation | Explicit migrations instead of runtime schema editing |
| Storage | AWS SDK 2.42.38, S3 | Product photos, logos, references, generated ads |
| AI | Claude adapters and OpenAI image adapter | Extraction, matching, creative direction, rendering, review |
| Administration | Bundled HTML prompt editor and admin APIs | Versioned drafts, publication, rollback, concept moderation |

Feature packages are `user`, `brand`, `product`, `concept`, `selection`, `generation`, and `prompt`, supported by configuration and shared image utilities. CRUD generally follows controller → service → repository. Concept analysis/storage and generation use replaceable provider interfaces. Product analysis remains embedded in the larger `ProductService`, mixing HTTP-provider and CRUD responsibilities.

There is no customer-facing frontend here. There is an implemented admin HTML page at `/admin/prompts`.

**Main journeys and API behavior**

The customer journey is Google login → create a brand and optional logo → analyze one to five photos of one product → review/edit suggestions → save product/photos → submit `/api/v1/ads` with an idempotency key, product ID, aspect ratio, and optional quality → poll the returned location → retrieve the authorized creation image.

Analysis itself saves neither product nor photos. A client must retain the selected files and send them again when confirming. JSON-only product creation is supported, but selection requires an uploaded owned S3 image; a legacy external `imageUrl` is insufficient.

| API family | Behavior |
| --- | --- |
| Users/security | Current account/onboarding and CSRF token retrieval |
| Brands/products | Owner-scoped CRUD; JSON and multipart mutations; paginated lists |
| Product analysis | Combined image analysis returning six editable fields |
| Ads | Canonical end-to-end submission and status |
| Creations | Paginated product history and authenticated final image delivery |
| Concepts | Shared authenticated reference library and extraction |
| Concept administration | Allowlisted revision, approval, and revocation |
| Prompt administration | Allowlisted drafts, immutable versions, publication, audit history |
| Legacy selection | Disabled by default; available through explicit configuration |

Mutations require session authentication and CSRF. Ownership violations generally return 404. PATCH treats null as unchanged, so clients cannot explicitly clear nullable fields. Brand/product list responses are arrays with default size 20 and maximum 100; clients must implement pagination explicitly.

Uploaded concepts start as DRAFT even when the extraction is usable. An administrator must approve a revision before selection can use it. The local configuration has no administrator subject allowlist; moderation and prompt editing therefore need external configuration before use.

**Selection, generation, and recovery**

The default picker samples five approved concepts per round, accepts a STRONG score of at least 80, and permits three rounds. Java validates membership, revision identity, score bounds, classification, adaptation text, and exact below-threshold exclusions. It does not independently reconstruct the model's compatibility judgment. The alternative weighted policy adds dimension scoring and independent verification.

Submission records an owner-scoped idempotency key/body hash and snapshots product/brand facts, asset fingerprints, policy, and creative-director prompt version. A winner reserves concept occupancy and creates a generation job atomically. Consumed occupancy prevents reuse for the same owner/product/concept.

```text
QUEUED selection → SCREENING [→ VERIFYING for weighted policy]
  → selected concept and reserved allocation
  → PROMPTING → PROMPT_READY → GENERATING → REVIEWING → SUCCEEDED
```

Generation persists completed prompt text, rendered output metadata, and review results. Publication atomically creates the creation record, consumes the allocation, and succeeds the job. A transient publication failure retries database work using the saved review. Worker leases and fencing prevent a stale worker from publishing. Generation leases renew during long calls.

Selection and generation have separate bounded execution lanes, defaulting to one worker each per instance. Idle scans back off. Verified asset bytes are cached within one worker execution, capped at 32 MiB; the cache does not cross jobs or restarts.

This avoids repeating many completed paid stages. It cannot guarantee exactly-once execution at a remote provider: a process can die after the provider charges but before the result is saved. Unknown outcomes intentionally require reconciliation.

**Prioritized findings**

The following distinguish observed source behavior from scenarios not reproduced against production systems.

| Priority | Finding and evidence | Impact and action |
| --- | --- | --- |
| High | Literal OAuth, database, AI, and AWS credentials are present in local [application.properties](../src/main/resources/application.properties). Committed HEAD uses environment placeholders for the inspected credential properties. | Resource copying places local values in build output, and accidental commits/artifact sharing can disclose them. Restore external secret injection, inspect distributed artifacts, and rotate credentials whose confidentiality cannot be established. This review does not establish a historical committed-secret leak or credential validity. |
| High | Product DELETE performs S3 deletion before database deletion/commit: [ProductService](../src/main/java/com/giraffix/product/service/ProductService.java), line 500. | A later object failure or database rollback can leave a retained product with missing photos. Use a durable deletion state and retryable cleanup intent. Current retries help complete deletion but cannot restore removed photos. |
| High | Reconciliation states have no implemented operator resolution API/worker: [GenerationWorker](../src/main/java/com/giraffix/generation/GenerationWorker.java), lines 55, 86, and 146. | Unknown prompt/render/review outcomes can retain occupancy indefinitely. Add audited inspection, provider-result reconciliation, safe release, and explicit terminal resolution. Keep uncertain billed operations protected from automatic replay. |
| High | Generic generation failure handling also classifies known failures as uncertain: `GenerationWorker`, line 86; [OpenAiImageGenerator](../src/main/java/com/giraffix/generation/OpenAiImageGenerator.java). | A definitive provider HTTP rejection, invalid returned image, or local validation error can strand a reservation with `GENERATION_OUTCOME_UNKNOWN`. Separate known rejection, known billed-invalid result, and unknown remote outcome; record their provider/cost evidence. |
| High | No persisted aggregate account/global budget, queue admission limit, or per-account request quota was found. | New idempotency keys create new paid work. Per-job estimates and per-instance worker bounds do not cap total spending. Add account/global admission and budget reservations, including synchronous analysis/concept extraction. Gateway controls, if any, were not inspected. |
| Medium | Cleanup is best-effort and not durable: [ProductImageService](../src/main/java/com/giraffix/product/service/ProductImageService.java), line 114; [GeneratedAssetStorage](../src/main/java/com/giraffix/generation/GeneratedAssetStorage.java). | Upload rollback compensation now exists, including uncertain PUT cleanup, but process termination or cleanup outage can still orphan objects. Persist cleanup intents and add idempotent retries and object inventory reconciliation. |
| Medium | WebP without an installed decoder is accepted using only envelope/chunk markers: [ImageProcessor](../src/main/java/com/giraffix/shared/image/ImageProcessor.java), lines 38 and 77. | This path does not enforce the advertised dimensions/pixel limit or decode integrity. Native JPEG/PNG/GIF paths also return original bytes after header inspection. Add format-aware validation and malformed/oversized fixtures. |
| Medium | Product/concept extraction configure no explicit application connect/read timeout: [ProductService](../src/main/java/com/giraffix/product/service/ProductService.java), line 82; [ClaudeConceptAnalyzer](../src/main/java/com/giraffix/concept/adapter/ClaudeConceptAnalyzer.java), line 50. | A slow upstream can tie up synchronous request threads according to transport defaults. Configure explicit timeouts and an overall request deadline. Other workflow adapters already set transport limits. |
| Medium | Prompt writing uses selection-policy token rates despite its independently configurable model: [ClaudeAdPromptWriter](../src/main/java/com/giraffix/generation/ClaudeAdPromptWriter.java), line 76. | Cost accounting can diverge after model changes. Snapshot each stage's own model and rate policy. The configured costs remain estimates rather than provider billing controls. |
| Medium | Review budget feasibility is checked after rendering and after `review_started` is persisted: [ClaudeOutputReviewer](../src/main/java/com/giraffix/generation/ClaudeOutputReviewer.java), line 41; `GenerationWorker`, line 152. | A sufficiently large valid brief/image set can fail a deterministic local budget check after a paid render, then enter reconciliation. Preflight the downstream review envelope before rendering and distinguish local refusal from dispatched review uncertainty. No fresh failing large-brief case was executed. |
| Medium | Input photos/logos/concepts return permanent S3 URLs, while final creations use authenticated application delivery. | Private buckets need an input-preview mechanism; public buckets expose a different privacy contract. Implement authorized input reads or temporary signed reads according to the intended access model. Actual bucket policy was not inspected. |
| Medium | PostgreSQL-only V7 partial uniqueness and V14 notification trigger are skipped under H2. Notification tests mock PostgreSQL APIs. | The green suite does not establish real trigger execution, cross-replica invalidation, locking, or query plans. Add disposable PostgreSQL integration checks for migrations, concurrent mutations, and publication/reconnect behavior. |

**Product-policy decisions requiring attention**

Fallback removes the STRONG/threshold predicate and can select `NOT_SUITABLE`; this is intentional in the current implementation and explicitly covered in `SelectionWorkflowTests`. Decide whether generation should instead stop, require confirmation, or enforce a minimum acceptable fallback.

Below-threshold exclusions are keyed by product/concept and persist across product edits, concept revisions, and threshold changes. There is no reset endpoint. This can permanently suppress newly suitable pairs. Consider revision/context-scoped exclusions or an audited reset policy.

Selection's default 90-second deadline starts at submission, so waiting in the database queue consumes the execution budget. With bounded workers and no admission control, requests can expire before their first provider call. Define queue and execution deadlines separately, or reject requests when capacity cannot meet the requested service level.

Deleting a product preserves workflow/creation history without a product foreign key, while creation reads require the product to exist. Historical generated objects can remain billed in storage and become inaccessible through the API. Define deletion, archival access, and retention as one lifecycle policy.

**Security, database, and operational assessment**

Existing protections include verified Google email, authenticated APIs, CSRF, HTTP-only session cookies, SameSite Lax, owned-resource lookups, administrator subject allowlists, parameterized user values in SQL, S3-prefix validation, asset hashes, and private final-image delivery with `no-store`/`nosniff`. The prompt editor inserts dynamic text through `textContent` and uses CSRF-protected requests.

Swagger is public. Session cookies have no explicit Secure configuration in the local properties. No CORS policy or shared session store is present. Validate TLS/proxy handling and choose a same-origin or explicit cross-origin frontend/session design before scaling replicas. The prompt listener holds one database connection per replica; include that in connection-pool sizing.

MVC errors now use a structured bounded response and omit raw SQL, provider bodies, and rejected values. Security-filter responses retain a separate framework contract. All API routes now get request IDs; asynchronous workers get operation IDs. Logs default to INFO, extracted fields log lengths instead of contents, and SQL console output is disabled. These correct several earlier review findings.

Product descriptions, key features, and legacy URLs have no practical DTO length bound. Excessive data expands stored snapshots and downstream AI inputs. Add domain limits and review HTTP body limits. Null-as-unchanged PATCH semantics need a separate explicit clear-field mechanism if clearing is required.

Flyway automatic baselining remains enabled. Disable it after deliberate legacy adoption: baselining records a starting point without proving that all old constraints and indexes exist. Hibernate validation is not a complete integrity audit. Concept approval uniqueness relies on application locking without a database constraint enforcing one approved revision per concept. Consider database defense for future writers.

The schema retains historical `product_images.image_data` but the current application does not map/serve those bytes. Existing non-S3 rows need a deliberate migration if present. `outbox_events` remains a historical table; current dispatch polls durable jobs directly and no longer writes outbox rows.

No CI pipeline, container/deployment definition, production profile, durable cleanup worker, retention implementation, or backup/restore verification was found. Existing operational documentation is useful, but deployed behavior and recovery remain unverified. Local model identifiers differ from adapter defaults/documentation; mocked tests do not prove account access, accepted image sizes, real rendering quality, or current pricing.

**Performance and maintainability**

Pagination and batched product-image loading now address the previous unbounded/N+1 list concerns. Status queries use authorized joins, and generation eligibility/completed-attempt reuse have V12 indexes. Measure actual PostgreSQL plans before adding indexes speculatively.

Candidate sampling uses `ORDER BY RANDOM()`, which can become expensive as the library grows. Asset/base64 copies multiply per-worker memory beyond the 32 MiB cache cap. S3 calls during product upload/delete hold database transactions and locks; moving to durable storage intents would improve both consistency and lock duration.

Selection leases last 150 seconds without generation-style renewal. The default selection deadline is shorter, but unusually slow asset access or increased deadline/round configuration can outlast the lease. Fencing protects publication but cannot undo a remote paid call. Align lease renewal and provider/asset deadlines if those settings grow.

Several core workflow classes compress large SQL/state transitions into dense methods. Extract named transitions and failure classifications, consolidate typed validated configuration, separate product analysis from CRUD, and keep model/prompt/cost provenance consistent. Preserve the current modular provider interfaces and checkpoint tests while refactoring.

**Suggested implementation order**

1. Externalize local credentials and review affected build artifacts; configure administrator subjects and production secret injection.
2. Add reconciliation inspection/resolution and distinguish deterministic failures from unknown provider outcomes.
3. Add durable S3 cleanup/deletion intents, retries, and lifecycle rules for deleted products/creations.
4. Introduce account/global budgets, rate/admission limits, and queue-age metrics.
5. Add explicit extraction timeouts, full image validation, domain input limits, and downstream budget preflight.
6. Run PostgreSQL-backed CI for V1–V14, locking, notifications, and multi-instance recovery; package a reproducible deployment.
7. Resolve fallback/exclusion policies, input asset access, frontend integration, session scaling, and archival/retention behavior.

Earlier reports should not be used as a current defect checklist: independent worker lanes, pagination/batch loading, upload compensation, product mutation locks, legacy ratio normalization, rejected-output cleanup, structured errors, and broad request correlation are already implemented in this working tree.
