**Giraffix project review — 16 November 2026**

Giraffix is a substantial backend for an AI advertising studio. Product onboarding, image analysis, reference concept extraction, administrative approval, and durable concept selection are implemented. Advertisement image generation has orchestration and review code, but its default provider is a placeholder. The project needs credential handling, storage consistency, generation lifecycle, and operational work before production use.

This review covers the local working tree in `giraffix-backend`: 72 main Java files, 20 test Java files including a fixture, eight Flyway migration versions, 18 application tables, HTTP routes, prompts, schemas, configuration, build files, and existing documentation. There is no frontend in this workspace. `.m2` is a local dependency cache. Findings below distinguish implemented behavior, source-level risks, and independently verified results. Earlier analysis documents were consulted, and their main findings checked against current source.

**Fresh verification**

The existing Maven suite passed: **182 tests, 0 failures, 0 errors, 0 skipped**, in 64 seconds. Java was 17.0.12; installed Maven was 3.9.9. The run completed at 22:30:24 IST on 16 November 2026.

```powershell
mvn -o -s target/project-review-maven-settings.xml "-Dmaven.repo.local=D:\PROJECTS\GIRAFFIX\.m2\repository" test
```

The command uses the existing local dependency cache and review settings. Evidence is in [the fresh test log](../target/project-analysis-current-tests.log) and `target/surefire-reports/`. Tests use H2, mocks, and local provider HTTP stubs. No live OAuth login, paid model call, S3 operation, or production database migration was exercised. Earlier documentation records PostgreSQL checks; those were not rerun here. No coverage percentage or vulnerability audit was produced. The passing suite does not establish production integration or cover every risk below.

**Architecture and responsibilities**

The POM declares Java 17, Spring Boot 4.1.2, Spring MVC, Spring Security/OAuth2, JPA, Flyway, PostgreSQL, AWS SDK 2.42.38, Springdoc 3.2.0, and Actuator. Jackson 3 is used for JSON. These are project declarations, not assertions about current vendor support or model availability.

The application is a modular monolith. Account, brand, product, and concept CRUD use controllers, services, and JPA repositories. Selection and generation use JDBC with explicit transactions to coordinate job state, locks, attempt accounting, leases, and allocations. PostgreSQL stores ownership, metadata, analysis results, and workflow history; S3 stores current uploaded images and generated outputs. Claude supplies extraction, compatibility evaluations, and output review. Google supplies identity.

| Module | Responsibility | Status / limitation |
| --- | --- | --- |
| `user` | Google identity persistence, current account, onboarding flag | Implemented; real login unverified |
| `brand` | Owner-scoped create, read, update, list | Implemented; lists unbounded |
| `product` | Product CRUD, multi-image analysis, S3 attachments | Implemented; storage compensation and concurrency gaps |
| `concept` | Reference extraction, shared library, revisions and approval | Implemented; administrator subject allowlist must be configured |
| `selection` | Async requests, sampling, scoring, exclusions, reservation | Implemented; capacity and recovery operations need work |
| `generation` | Jobs, provider port, asset persistence, review, creation delivery | Infrastructure implemented; no live image generator |
| `shared.image` | Content detection, limits, conversion | Implemented; decoder-free WebP validation is incomplete |
| `config` | Security, provider/storage clients, correlation logging | Implemented; production settings need separation |

The current scale does not require splitting this into microservices. Clearer interfaces and state-transition methods would improve the existing design first.

**End-to-end behavior**

1. A user signs in with Google. The account is stored by Google subject; a verified email is required by the persistence service.
2. Creating a brand marks onboarding complete. Brands and products are scoped to their owning account.
3. Product analysis accepts 1–5 views of one product, makes one Claude call, and returns six editable fields. It creates no product or stored image. `keyFeatures` is intended to describe visual personality; `targetAudience` is an estimated intended audience.
4. Confirmation saves edited product details and uploads photos. Image metadata and permanent S3 URLs are persisted. Existing JSON-only product creation remains available, but selection requires an owned uploaded image.
5. Reference upload independently extracts a reusable advertising idea, stores its image/analysis, and creates a draft revision. An administrator must approve a usable revision before selection can sample it.
6. A selection request captures saved product/brand facts, image fingerprints, intent/format, and policy. It evaluates concepts and reserves one, committing an allocation, brief, generation job, and outbox event together.
7. Generation waits for an available provider, checks context and approval before dispatch, stores output, reviews fidelity, then publishes a private creation when accepted. The supplied implementation currently stops at provider availability.

Clients must retain selected product files while the user reviews analysis suggestions, then reattach them for confirmation. `SELECTED` means a concept and generation job exist; it does not mean an advertisement image exists.

**Current concept picker**

The default policy is `concept-picker-v1`: up to five randomly sampled approved concepts per round, threshold 80, and up to three rounds total. It excludes concepts already seen in the request, occupied for the product, or saved in that product's exclusion table. Claude receives all saved product views, structured facts, brand details, and concept cards. Java validates exact candidate membership, scores, classifications, adaptation text, and exclusions.

A `STRONG` result at or above the threshold can win immediately. Otherwise the highest available score across completed rounds can win through fallback. Fallback intentionally includes `CONDITIONAL` and `NOT_SUITABLE`; the API exposes `fallbackUsed`. Below-threshold product/concept pairs remain excluded across product edits, concept revisions, and threshold changes. There is no reset endpoint. These are documented product choices, not accidental score-policy defects.

Picker mode skips the legacy independent five-dimension verification call. Setting `picker-enabled=false` enables that legacy policy for new requests. Existing requests retain their policy snapshots. Generated outputs still undergo output review.

**Prioritized findings**

The failure sequences below follow the current source. They were not newly reproduced through fault injection during this review.

| Priority | Finding / evidence | Consequence and action |
| --- | --- | --- |
| Critical if valid | Populated OAuth secret, database password, AI key, and AWS credentials in `application.properties`, lines 6, 12, 39, 61–62 | Credentials enter copied resources/build output even while the source is untracked. Rotate valid credentials and move values to external configuration. No secret values are included here. |
| High | Only 26 files are tracked; most newer modules, migrations, tests, wrapper files, and configuration are untracked | A clean clone cannot reproduce this local application. Remove secrets, provide safe configuration templates, then deliberately version required source/build files. |
| High | `GenerationConfiguration.java:12` supplies `available=false` | Successful selection produces jobs waiting for a provider. Implement a real adapter with operation identity, bounded cost/time, and recovery semantics before end-to-end release. |
| High | `ProductImageService.java:112` uploads each object before final database flush and has no cleanup path | A later upload or database failure leaves orphaned S3 objects. Record upload/cleanup intent durably and handle uncertain PUT outcomes. Concept upload already has best-effort compensation, but no durable retry. |
| High | `GenerationWorker.java:85` locks current ownership at publication but does not repeat `contexts.current(...)` | A product or brand edit during generation/review can still publish an output based on the earlier facts. Define immutable-version semantics or revalidate under the final locks. |
| High | Generation checks the deadline at entry, then can review and publish without another deadline check (`GenerationWorker.java:39`, `:81`, `:97`) | A slow provider/review can publish after the deadline while the lease is still valid. Pass remaining time into review and enforce it at dispatch and publication boundaries. |
| High | No application account/global usage quota or queue admission limit found | Per-selection budgets do not bound repeated requests with fresh keys; synchronous extraction has no comparable aggregate ledger. Add aggregate usage and concurrency controls. External controls were not evidenced. |
| Medium | Append reads collection size/bytes without a mutation lock (`ProductImageService.java:59`) | Concurrent appends can choose the same position, upload objects, then fail on the unique position constraint. Serialize image mutations and recheck limits within that boundary. |
| Medium | Product deletion removes S3 objects before database commit (`ProductService.java:507`) | A partial storage failure or failed commit can leave product metadata referencing missing objects. Introduce durable deletion state and retryable cleanup. |
| Medium | Product/concept extraction clients have no explicit application connect/read timeout policy | Slow provider calls can occupy servlet threads. Set bounded timeouts and an overall request policy. Picker/reviewer clients already specify connect/read timeouts. |
| Medium | `ImageProcessor.java:38` accepts decoder-free WebP after RIFF/chunk-envelope checks | Dimensions and actual pixel payload are not validated on that path. Use a decoder or validated parser. Native JPEG/PNG/GIF also return original bytes after metadata inspection, without full decode. |
| Medium | `SelectionDispatcher.java:22` runs selection serially before polling generation | Queue time consumes the selection deadline; slow calls delay later requests and generation. Add bounded concurrency, workload separation, and queue admission/backpressure. |
| Medium | Generation polls the oldest ten jobs; unavailable-provider jobs clear their leases immediately | Older waiting jobs can repeatedly occupy the polling window. Persist next-attempt eligibility/backoff and ensure fair polling. |
| Medium | `RECONCILIATION_REQUIRED` retains occupancy, with no operator API or recovery worker found | Unknown billed outcomes correctly avoid blind replay, but reservations can remain stuck indefinitely. Add inspect/reconcile/release operations with provider accounting checks. |
| Medium | Input images expose permanent S3 URLs; no authorized product/concept image-read endpoint exists | A private bucket needs an additional delivery arrangement for frontend previews. Define access policy and provide authorized reads or expiring URLs. Creation delivery already enforces ownership. |

**Security and API contract**

Useful protections are present: authenticated `/api/v1/**`, CSRF for mutations, HTTP-only session cookies with SameSite Lax, owner-scoped brand/product access, owner-scoped selection polling, and private creation delivery with `no-store` and `nosniff`. Storage deletion validates the configured bucket URL and product key prefix before removing objects. Queries generally bind user-supplied values; the UUID inserted into the internal manual-selection SQL fragment is typed as a UUID.

Concept creation and browsing are shared across authenticated users. Revision operations use `giraffix.selection.admin-subjects`; the local properties do not set it, so approval is unavailable unless supplied externally. This is a setup prerequisite for populating the eligible concept pool. Public Swagger documentation is explicitly permitted.

No centralized exception response contract was found. PATCH null means unchanged, so nullable fields cannot be explicitly cleared. Product/brand free-text fields and legacy image URLs lack practical DTO length bounds in several places. Those fields can expand storage and selection snapshots; selection token preflight does not bound CRUD ingestion.

README says to set `ANTHROPIC_API_KEY` and optionally `GIRAFFIX_AI_MODEL`, but the properties contain literal values without those placeholders. Align the documented variable names with actual property binding. `ClaudeProperties` also retains the generated record `toString`, which includes its API key if logged; S3 properties explicitly redact theirs.

TLS termination, secure-cookie behavior behind a proxy, bucket IAM/read policy, backups, frontend CORS needs, and shared sessions across replicas were not verified. No explicit frontend CORS configuration or distributed session store was found. Their absence locally does not establish the external deployment's configuration.

**Persistence, transactions, and lifecycle**

| Migration | Purpose |
| --- | --- |
| V1 | Users, brands, products, ownership indexes |
| V2 | Product image metadata and historical image bytes |
| V3 | S3 URL support; image bytes become nullable |
| V4 | Concept extraction records and asset metadata |
| V5 | Revisions, selection requests/batches/attempts/evaluations, cache, allocations/claims, generation jobs, outbox, creations |
| V6 | Scoring integrity and winner relationship |
| V7 | PostgreSQL partial unique index for active allocations |
| V8 | Picker scores/classifications, fallback flag, permanent exclusions |

Flyway owns changes and Hibernate validates mapped columns. Automatic baselining remains enabled for legacy adoption. Disable it after deliberate adoption of the intended database; validation does not prove every constraint/index exists. V7 deliberately skips its PostgreSQL-only index under H2, so H2 alone cannot validate that protection.

The durable workflow has substantial safeguards: owner-scoped idempotency, immutable request/policy snapshots, persisted provider attempts, conservative accounting for unknown costs, leases with fencing, concept approval locks, occupancy claims, and atomic reservation/job creation. These should be preserved during fixes.

Products and brands lack optimistic versions, allowing stale concurrent updates to overwrite fields. Product storage calls occur inside database transactions, increasing connection occupancy during S3 latency. Append, delete, and workflow locks need a consistent mutation strategy.

Legacy `product_images.image_data` remains in the schema but is not mapped or served by current code. Rows lacking a storage URL need a migration/delivery plan if they exist. Workflow/history tables retain product IDs without product foreign keys; deleting a product can retain jobs and creation assets whose read endpoints require the deleted product. Define retention and audit access together. Outbox rows commit with jobs, but the dispatcher polls job tables directly; there is no separate external event delivery worker.

One approved revision per concept is protected by service locking rather than a database unique constraint. If other writers are introduced, add database defense. Extraction records store a schema version but not full prompt/model provenance; prompt v4 and schema version 1 are separate concepts and should be recorded explicitly.

**Performance, maintainability, and documentation**

- Brand/product lists are unpaginated. Product response mapping traverses lazy image collections and can produce one extra query per product. Add bounded pagination and a measured fetch strategy.
- Random selection uses `ORDER BY RANDOM()`, which can become expensive for a large reference pool. Measure before replacing it.
- Snapshot capture, picker evaluation, and review reread S3 assets. Fingerprints enforce consistency, but latency and repeated transfer should be measured.
- Workflow ledgers, exclusions, cache, outbox, generated assets, and failed outputs have no implemented retention worker. Add retention rules alongside recovery tooling.
- `ProductService` mixes CRUD, provider protocol, response validation, and logging. Extract a product analyzer interface consistent with the concept module.
- Selection/generation state changes are compressed into large methods and inline SQL. Named transitions and query methods would make invariants easier to review.
- No CI, container/deployment specification, production profile, queue metrics, or reconciliation dashboard was found. Actuator is included, but does not replace workflow-specific metrics and alerts.
- Local configuration enables SQL output and extracted-field DEBUG logging. Establish production defaults for diagnostic data and Flyway baseline behavior.
- The correlation filter covers selected POST/DELETE routes, while README claims every request receives an ID. Expand coverage or narrow the claim.
- The transport limit is 50 MB per file and 21 MB per request, while the application rejects images over 5 MB. Align documented and transport limits.
- The product schema still describes generic visible features and evidence-only audience extraction while prompt v7 asks for personality and demographic estimation. Align those descriptions; local validation enforces shape/types, not those semantic distinctions.

**Delivery order and acceptance checks**

1. Externalize and rotate valid credentials; supply safe setup templates; deliberately version all required files. Acceptance: a clean checkout compiles/tests without secrets or local artifacts.
2. Implement the generation adapter and reconcile workflow, then enforce context/deadline publication semantics. Acceptance: a reviewed image reaches private creation history; uncertain operations recover without duplicate billing or permanent unexplained reservations.
3. Add durable storage compensation/deletion and serialize image mutations. Acceptance: partial uploads, failed commits, concurrent appends/deletes, and process restarts converge to consistent metadata/assets.
4. Add aggregate usage limits, explicit extraction timeouts, fair polling, and queue admission. Acceptance: bursts remain bounded and queued work has predictable expiry/retry behavior.
5. Validate migrations and concurrency against disposable PostgreSQL; verify real OAuth/provider/S3 integration in an isolated environment. Cover generation edits/deadlines and malformed WebP payloads explicitly.
6. Establish deployment profiles, input image delivery, sessions, CI, retention, backup restoration, and operational metrics appropriate to the intended deployment.

This review added this document and generated an ignored test log. Application source, credentials, runtime configuration, and database contents were not intentionally modified; tests generated normal local build outputs and test logs. No files were staged or committed.
