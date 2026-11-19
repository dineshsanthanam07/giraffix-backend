**Current project review — after durable product deletion, 19 November 2026**

The backend has an implemented ad-generation workflow and substantial defensive engineering. Its main remaining weaknesses are generation failure classification/recovery, late budget validation, incomplete upload validation, spending admission, and lifecycle/operational gaps. The product-photo deletion consistency issue from the earlier review is fixed by V15 and is not listed as an open defect here.

This review covers the current working tree, including uncommitted and untracked files: 90 production Java files, 28 test Java files, and 15 migrations. Areas examined include build/configuration, authentication/ownership, brand/product CRUD, image processing/storage, concepts/moderation, both selection policies, generation/review/publication, prompt administration, deletion scheduling, schema integrity, tests, and operations.

**Evidence and limits**

The latest full Maven run after the deletion change passed **260 tests, zero failures, errors, or skipped tests**, in 1 minute 21 seconds. Evidence: [full-suite log](../target/product-deletion-full-tests.log). Application source has not changed since that run. This review did not repeat the unchanged suite; it added isolated diagnostic probes under `target/review-probes` using the compiled code and existing test fixtures.

Five diagnostic results were reproduced:

```text
malformed_20_byte_webp_accepted=image/webp
truncated_png_without_pixel_data_accepted=image/png
allowed_large_product_review_rejected_before_HTTP=Output review exceeds reserved budget
deleted_product_generation_state=RECONCILIATION_REQUIRED retained_claims=1
definite_provider_400_generation_state=RECONCILIATION_REQUIRED retained_claims=1
```

Evidence: [probe source](../target/review-probes/ProjectReviewProbes.java) and [results](../target/review-probes/results.log). The probes used in-memory H2, mocks, dummy provider credentials, and a local non-listening endpoint for a branch that failed before HTTP. No paid AI or production S3 call was made. `target` artifacts are local evidence, not tracked deliverables.

There was no production PostgreSQL exercise, real OAuth login, dependency vulnerability scan, deployment audit, load test, or live model/price validation. A passing suite establishes the covered behaviors, not absence of defects.

**Architecture and implemented strengths**

Java 17/Spring Boot 4.1.2 hosts one feature-organized backend. PostgreSQL stores core resources through JPA and workflow state through JDBC. Flyway owns migrations; Hibernate validates entity mappings. Google OIDC supplies session authentication. Claude handles extraction, selection, creative direction, and review; the OpenAI adapter renders images. S3 stores photos, logos, references, and generated assets. A bundled admin HTML page manages versioned prompts; no customer-facing frontend exists in this workspace.

The customer flow is login → brand/logo → product-photo analysis → review/edit/save → ad submission with idempotency key → concept selection → prompt → render → review → publish. Concept extraction starts as a draft and requires allowlisted approval before use. Concepts are shared among authenticated users rather than tenant-private.

Existing strengths include CSRF and ownership enforcement, verified Google email, product mutation locks, stable pagination, batched image loading, structured bounded errors, request/operation correlation, exact candidate validation, persisted snapshots and asset hashes, independent bounded worker lanes, generation lease renewal/fencing, persisted prompt/output/review checkpoints, and authorized final image delivery. The new deletion queue atomically commits product removal and cleanup intent, uses database time, and retries storage cleanup after crashes/outages.

**Confirmed bugs**

1. **High: deleting a product strands its queued generation job.**

   After selection creates a generation job, removing the product causes `SelectionContextService.current()` to throw 404 from `lock()`. `GenerationWorker.run()` catches it as a generic runtime failure, marks the job `RECONCILIATION_REQUIRED`, and retains the allocation claim. The probe reproduced this before any rendering occurred. Source: [GenerationWorker](../src/main/java/com/giraffix/generation/GenerationWorker.java), lines 64 and 86; [SelectionContextService](../src/main/java/com/giraffix/selection/SelectionContextService.java), `lock/current`.

   Fix: treat a deleted input as a known stale/cancelled context, release unconsumed occupancy, and define cancellation of active stages. Product-photo cleanup alone does not resolve workflow state.

2. **High: a definite rendering rejection is reported as an unknown outcome.**

   A simulated provider HTTP 400 produced `RECONCILIATION_REQUIRED` with one retained claim. The broad generation catch does not distinguish a definite rejection from a timed-out request with unknown remote execution. A local invalid-result failure can enter the same bucket. Source: `GenerationWorker`, line 86; [OpenAiImageGenerator](../src/main/java/com/giraffix/generation/OpenAiImageGenerator.java), response/error handling.

   Fix: typed outcomes for known rejection, known billed-invalid output, stale context, pre-dispatch refusal, and unknown dispatch. Preserve conservative handling where execution truly is uncertain.

3. **High: downstream review feasibility is validated too late.**

   A five-photo product with a logo and a 22,000-character description exceeds the default review envelope. That description is permitted by the DTO. The reviewer rejects locally before its HTTP call, but the normal pipeline has already rendered/stored the output and set `review_started=TRUE`. The generic catch then sends it to reconciliation. The probe reproduced the local refusal; pipeline sequencing is confirmed in source. Source: [ClaudeOutputReviewer](../src/main/java/com/giraffix/generation/ClaudeOutputReviewer.java), line 41; `GenerationWorker`, line 152; [ProductRequest](../src/main/java/com/giraffix/product/dto/ProductRequest.java).

   Fix: preflight the complete downstream envelope before rendering; validate product/brand text limits; persist review dispatch separately from review preparation. A local budget refusal must not imply a remotely dispatched review.

4. **Medium: malformed images pass upload validation.**

   A 20-byte WebP envelope without usable image content and a PNG truncated after its header were both accepted. The no-decoder WebP path checks markers/length only and does not enforce dimensions. Native formats return original bytes after header/dimension inspection, without decoding their pixel payload. Source: [ImageProcessor](../src/main/java/com/giraffix/shared/image/ImageProcessor.java), lines 38, 55, and 77.

   Fix: validate supported image payloads with suitable decoders/parsers under byte/dimension/pixel limits. Add malformed/truncated/oversized regression fixtures. These probes establish invalid acceptance, not a demonstrated remote exploit.

5. **Medium: creative-prompt cost uses the wrong model's rate policy.**

   Prompt generation has its own configurable model, but cost/envelope calculations use `brief.selectionPolicy().inputRate()/outputRate()`. Changing only the prompt model leaves its cost policy unchanged. Source: [ClaudeAdPromptWriter](../src/main/java/com/giraffix/generation/ClaudeAdPromptWriter.java), line 76.

   Fix: persist a separate prompt-model rate snapshot. Render/review/model identities and pricing versions should be traceable together. Actual account pricing was not inspected.

6. **Medium: an idempotent ad replay depends on current provider availability.**

   `AdController.create()` checks `generator.available()` before looking up an existing idempotency key. If configuration disables generation after a successful submission, replaying the same request returns 503 instead of its existing operation. Source: [AdController](../src/main/java/com/giraffix/generation/AdController.java), lines 52–54. This is source-confirmed and was not a separate runtime probe.

   Fix: authorize and resolve an existing operation first; require provider availability only when creating new work.

**Design weaknesses and product-policy flaws**

7. **High: reconciliation has no operator completion path.** Jobs intentionally stop on uncertain paid outcomes, but there is no implemented inspect/reconcile/release API or worker. Claims can remain reserved indefinitely. Add audited actions that attach known provider results, finalize failures, release safely, and record decisions. Do not blindly replay unknown billed operations.

8. **High: no aggregate spending/admission boundary.** Per-job reservations and bounded workers do not cap account/global spending or the durable queue. New keys create new paid opportunities; synchronous extraction also incurs cost outside the generation queue. Add persisted account/global budgets, queue capacity, rate limits, and per-account concurrency. External gateway/provider controls were not audited.

9. **Medium: fallback can select a concept labelled `NOT_SUITABLE`.** `reservePickerWinner(..., true)` removes the STRONG/threshold predicate. Existing tests explicitly preserve this behavior. It is a product-policy flaw to resolve, not an accidental regression: define a minimum fallback, require confirmation, or return no acceptable match. Source: [SelectionWorker](../src/main/java/com/giraffix/selection/SelectionWorker.java), line 181.

10. **Medium: exclusions outlive the facts that justified them.** Product/concept exclusions are keyed by product and concept, ignoring revised product facts, new concept revisions, prompt changes, and threshold changes. There is no reset API. Use revision/context/policy-scoped exclusions, expiry, or an audited reconsideration mechanism. Source: `SelectionWorker`, lines 311 and 350; V8.

11. **Medium: queue wait consumes selection execution time.** The default 90-second deadline starts on submission. A request behind slow work can expire without a first provider call. Separate queue-age and execution budgets or reject work that capacity cannot serve. Source: [SelectionService](../src/main/java/com/giraffix/selection/SelectionService.java), line 44.

12. **Medium: uploaded-asset access and creation access use different models.** Final creations have authenticated delivery. Product/logo/concept responses expose permanent S3 URLs. Private buckets need a preview endpoint or signed reads; public buckets imply a different confidentiality contract. No deployed bucket policy was inspected.

13. **Medium: deleting a product leaves historical assets inaccessible.** Creation data/objects are retained, while creation reads join the live product. This retains storage/data without an API access path after deletion. Choose archival access or deletion/retention of creations and snapshots. Product deletion currently means photo cleanup, not complete data erasure. Source: [CreationController](../src/main/java/com/giraffix/generation/CreationController.java), image query; V5.

14. **Medium: cleanup durability is limited to product deletion.** Product upload rollback, replaced logos, failed concept saves, and rejected generated output still use best-effort compensation. A crash or failed cleanup can leave objects behind. Extend durable intents or inventory reconciliation to those lifecycles. The V15 worker is not an orphan scanner.

15. **Medium: some database locks span external uploads.** Product multipart save/append runs S3 operations inside JPA transactions; append holds mutation locks while uploading. Slow storage can exhaust database capacity and delay edits/deletion. Stage assets outside locks with durable upload intent and a short final ownership/version check. Source: [ProductImageService](../src/main/java/com/giraffix/product/service/ProductImageService.java), transactional methods/save.

16. **Medium: some synchronous provider calls lack explicit application timeouts.** Product/concept extraction builds clients without explicit connect/read deadlines here. Transport/framework defaults may apply, but the repository does not define its service-level policy. Configure explicit timeouts, concurrency bounds, and a total deadline. Source: [ProductService](../src/main/java/com/giraffix/product/service/ProductService.java), lines 82–92; [ClaudeConceptAnalyzer](../src/main/java/com/giraffix/concept/adapter/ClaudeConceptAnalyzer.java), lines 50–52.

17. **Medium: generation provenance can be lost on a storage failure.** The provider result arrives before S3 upload, but `provider_id` is saved only after storage succeeds (except the wrong-ratio path). An S3 failure can therefore lose the returned provider ID needed for reconciliation. The client job ID can help correlate but does not replace a durable provider-result checkpoint. Source: `GenerationWorker`, lines 126–135.

18. **Low/medium: ordinary concept reads show the original extraction.** Approved revised analysis lives in `concept_revisions`, while `ConceptService.get/list` maps `concepts.analysis_json`. A customer can see different concept details from those selection actually uses. Expose approved revision/status/version explicitly or clearly label the original extraction. Do not silently change compatibility contracts without client review.

**Security, schema, and operations**

- **Known deferred high-risk configuration item:** local credentials remain in application properties; inspected committed HEAD used placeholders. They are copied as resources into local build output. Values are intentionally omitted here. This review did not change credential handling or verify key validity/history.
- Product/brand long-text fields need practical domain limits; legacy URLs also need intended format/length rules. This protects snapshots, memory, and AI envelopes.
- PATCH cannot distinguish null-as-clear from null-as-unchanged. Add explicit clearing semantics if the product needs them.
- Local configuration has no admin allowlist. Approval/prompt editing require external configuration; this is fail-closed behavior, not an authorization bypass.
- Local cookie configuration does not explicitly set Secure. Verify effective TLS/proxy/cookie handling in deployment. Swagger is intentionally public; decide whether that matches deployment policy.
- There is no shared session persistence or explicit cross-origin client policy. Multiple replicas need sticky sessions or shared sessions, and a deliberate browser integration design.
- Flyway automatic baselining remains enabled. Disable it after deliberate legacy adoption; it cannot prove old constraints/indexes match the baseline.
- One approved concept revision is enforced by application locking rather than a matching database unique constraint. Consider database defense for scripts/future writers.
- The deletion worker's SQL, PostgreSQL partial index, and LISTEN/NOTIFY trigger need real PostgreSQL validation. H2 skips PostgreSQL-only code and cannot establish equivalent locking/query behavior.
- No CI workflow, deployment/container definition, production profile, restore exercise, durable retention policy, or live throughput benchmark was found. No vulnerability claim is made without a dependency scan.
- Cleanup retry state is observable through SQL/logs but lacks an admin backlog view/alert. Changing the configured S3 bucket deliberately defers old cleanup; resolve old work before retiring that configuration.

**Performance and maintainability improvements**

- Preserve the modular provider interfaces and durable checkpoints; separate product extraction from CRUD.
- Replace dense state-transition SQL/methods with named operations, typed statuses/outcomes, and validated configuration records.
- Measure `ORDER BY RANDOM()` candidate sampling as the library grows; avoid speculative optimizations without PostgreSQL plans.
- Account for full request/base64/response copies when sizing worker memory. The 32 MiB asset cache cap does not cap total heap per execution.
- Bound provider response bodies before buffering/decoding where the transport permits. The output base64 length check currently occurs after the entire response body is read.
- Selection leases have no renewal and workflow leases mostly use application clocks; align leases/deadlines with actual worst-case asset/provider time and replica clock behavior.
- Add a consistent cancellation lifecycle, stage-specific failure messages, retention, queue-age metrics, retry/reconciliation backlog metrics, and estimated-versus-provider usage reconciliation.
- Audit prompt/model provenance across product/concept analysis as well as generation. Concept revision history is currently admin-only and unpaginated.
- Preserve existing pagination, image batching, authorization joins, and indexes that already address earlier performance concerns.

**Recommended order**

1. Fix known stale/deleted-context termination and typed generation outcomes; add regression cases from the probes.
2. Move review feasibility checks ahead of rendering and enforce product/brand input budgets.
3. Complete operator reconciliation and aggregate spending/admission controls.
4. Repair image validation and add explicit extraction transport policies.
5. Resolve fallback/exclusion behavior and asset access/retention semantics.
6. Extend durable cleanup to remaining asset lifecycles; expose backlog metrics and alerts.
7. Add PostgreSQL-backed CI, reproducible deployment, recovery testing, and frontend/session integration checks.

The existing 260 passing tests remain valuable. The new probes demonstrate why failure-oriented coverage must grow alongside the normal ad path. No application code or configuration was changed during this review.
