# Concept selection

New requests now default to the [Concept Picker v1 workflow](concept-picker.md), introduced with V8.
The screening/independent-verification policy documented below describes the legacy mode
(`giraffix.selection.picker-enabled=false`) and requests already saved with that policy.

Bounded random selection with independent image-aware verification. No vector
database or Knowledge Base is required. Existing product extraction remains v7;
concept extraction remains v4. Existing JSON-only product creation still works.

## Migration and approval

V5 copies existing concepts into draft revision 1, preserving IDs, analysis and
reference assets. Usability is not administrative approval. New concept uploads
register a draft revision in the same database transaction. V6 adds rating/winner
integrity. V7 adds PostgreSQL's partial unique active-allocation index. Applied
migrations V1-V4 are unchanged.

Set `giraffix.selection.admin-subjects` to comma-separated trusted Google subject
IDs. Empty means nobody can approve. Administrators use:

- `GET /api/v1/concepts/{conceptId}/revisions`
- `POST /api/v1/concepts/{conceptId}/revisions` with complete v4 analysis JSON
- `POST /api/v1/concepts/{conceptId}/revisions/{revisionId}/approve`
- `POST /api/v1/concepts/{conceptId}/revisions/{revisionId}/revoke`

Revision content is immutable through the API. Edits create drafts; approving one
revision revokes previous approvals for that identity. Unusable references cannot
be approved. Approved concepts are currently a shared authenticated library;
private/team concept libraries are not introduced here.

## API and selection

After login and CSRF setup, POST `/api/v1/selection-requests` with an
`Idempotency-Key` header and JSON:

```json
{
  "productId": "PRODUCT_UUID",
  "intent": "Present the product as effortless and refreshing",
  "format": "square social advertisement",
  "controls": "Keep product identity unchanged; no additional headline",
  "conceptId": null
}
```

HTTP 202 includes the status URL in Location. Poll
`GET /api/v1/selection-requests/{id}`. Same owner/key/body returns the same request;
different input for an existing key returns 409. Unknown fields, including score,
owner, approval or storage overrides, return 400. Existing session/CSRF protection
and ownership isolation apply.

Selection requires an owned uploaded image, nonblank name and category. Legacy
external imageUrl values do not count. Product image URLs must match the configured
bucket and product prefix. Snapshots include content hashes, facts, brand details,
intent, format, controls, policy/rubric/model versions and immutable asset identities.

Automatic selection draws up to ten approved unused concepts per round, without
replacement, for up to three rounds. Categories/forms do not restrict discovery.
Setting conceptId evaluates only that concept; failure does not silently substitute
another. History and contextual cooldowns apply to both paths.

One screening call rates all supplied cards on five dimensions. Java validates
IDs, revisions, response shape, rating ranges, bounded reasons, and resolvable JSON
pointer evidence. Every dimension needs evidence. Scores use integer arithmetic:

```text
weights = [30, 25, 20, 15, 10]
score = sum(weight * rating) / 4
accept = every rating >= 3 AND score >= 80
         AND no hard violation AND no uncertainty
```

Screening passes are provisional. Independent verification uses actual product and
reference images and the exact adaptation without screening scores. Ties favor
least-recently-used mechanisms and stable concept IDs. Reservations recheck context,
ownership and approval under locks. Product edits never reset consumed history:
the backend's stable product UUID is the lineage identity.

Thirty random draws are not a discovery guarantee. At 1% suitable concepts, the
approximate chance of finding one is only 26% before verification failures. Measure
acceptance by round before adding retrieval or expanding budgets.

## Durable work, budgets and memory

The dispatcher polls every second and processes work serially. Additional backend
instances may claim distinct work. Disable with
`giraffix.selection.worker-enabled=false`. Leases/fencing stop stale workers from
publishing state. They cannot undo an already accepted provider call.

Requests persist context/policy snapshots, ordered batches, attempts, usage,
adaptations, evaluations and allocations. Completed persisted responses can rebuild
projections without another provider call. An interrupted attempt with an unknown
result becomes PROVIDER_FAILED instead of being blindly dispatched again.

Properties below use `giraffix.selection`:

| Property | Default |
| --- | --- |
| batch-size / max-rounds | 5 / 3 (current application configuration) |
| max-verification-calls | 3, including verification retries |
| max-retries | 1 additional provider attempt across the request |
| deadline-seconds | 90 from enqueue; selection only |
| minimum-rating / threshold | 3 / 80 |
| weights | 30,25,20,15,10 |
| low-score-threshold / cooldown-days | 40 / 7 |
| model | claude-sonnet-5-5 |
| max-input-tokens / max-output-tokens | 120000 / 6000 |
| max-cost-micros | 2500000 ($2.50 ceiling, not expected spend) |
| max-attempt-cost-micros | 350000 ($0.35 reserved per attempt) |
| input-micros-per-million / output-micros-per-million | 2000000 / 10000000 |

These are configurable pricing assumptions, not a billing guarantee. Confirm model
availability and rates in the target account. Actual usage replaces reserves;
unknown costs retain reserves. Conservative input bounds can refuse a call before
dispatch. A valid low score is not retried for a better answer.

Grounded concept-level scores below 40 receive reversible seven-day cooldowns keyed
by owner, exact context, revision and full evaluator/policy identity. Execution-only
failures, uncertainties and provider errors do not suppress concepts. Every valid
evaluation, including non-winners, is retained. Positive-cache candidate reuse is
disabled in this baseline. Changed policies do not reuse stale negative exclusions;
raw ratings remain available for offline recalculation.

Outcomes distinguish SELECTED, NO_MATCH_WITHIN_BUDGET, POOL_EXHAUSTED,
TEMPORARILY_SUPPRESSED, SELECTION_BUSY, PROVIDER_FAILED and STALE_CONTEXT. Missing
owned assets/invalid input are rejected before enqueue. SELECTED is a verified brief
and reservation; it does not mean an advertisement has been generated.

## Generation and private outputs

Reservation, immutable brief, generation job and outbox event commit together.
The brief snapshots selection policy and separate generation limits. Properties:
`giraffix.generation.max-cost-micros=2000000`,
`giraffix.generation.review-reserve-micros=300000`, and
`giraffix.generation.deadline-seconds=600`.

The [single ad-generation API](ad-generation.md) now uses Claude to write the supplied
creative-director v2 prompt, followed by direct OpenAI `gpt-image-2` rendering. Set
`OPENAI_API_KEY` and provide `aspectRatio` through `/api/v1/ads`. The adapter receives
the saved prompt, actual product photos, optional separate brand logo, job ID,
remaining budget and deadline. The image provider remains replaceable through
`ImageGenerator`. Older selection requests without a ratio are preserved as legacy
requests and do not silently adopt a ratio.

With a provider, ownership/context/approval are checked before dispatch. Prepared
output bytes go to a deterministic private S3 key. Claude checks the actual output
against product views, reference and saved brief. Creation and CONSUMED allocation
commit atomically. Rejected output releases occupancy but preserves job/evaluation
history. Referenced assets on rejected/uncertain jobs are retained for recovery;
there is no garbage collector or retention worker.

Use `GET /api/v1/products/{id}/creations?page=0&size=20` for owned history. Entries
provide authorized `/api/v1/creations/{id}/image` links; delivery reauthorizes access,
checks hashes and uses no-store responses. Keep the bucket private. IAM needs
GetObject for references/products/creations and PutObject for creations, alongside
existing upload permissions.

Unknown generation/review outcomes become RECONCILIATION_REQUIRED and retain the
reservation. The provider is not retried automatically. Operators must establish
the actual outcome before release; never reset unknown jobs to QUEUED blindly.
An operator reconciliation API/UI remains unimplemented. Leases last 150 seconds and
renew every 30 seconds while a worker is alive, allowing long image calls without
making crashed work immediately replayable. The direct adapter bounds each image
HTTP read by the remaining job deadline and a maximum 240-second timeout.

## Tests

Run `mvn test` or the Maven wrapper. New tests cover scoring, HTTP contracts,
authentication/CSRF, approval migration, cache isolation/expiry, ownership, budgets,
deadlines, restart recovery, fencing, concurrency, private assets and mocked generation.
Provider adapter tests use a local HTTP server without credits or private data.

Run database scenarios against a disposable PostgreSQL instance:

```powershell
mvn "-Dselection.test.jdbc=jdbc:postgresql://127.0.0.1:55439/selection_checks" `
  "-Dtest=SelectionWorkflowTests,SelectionMigrationTests,SelectionHttpTests,SelectionProviderTests,SelectionAssetTests,ScorePolicyTests" test
```

The local harness uses role selection_test with a blank password. Workflow tests
clear fixture tables between cases; never point them at an application database.
The PostgreSQL run verifies real locking, the partial unique index, concurrent
reservations/idempotency/cache projections and migration preservation. H2 is
supplementary evidence. Fixtures do not establish live AI quality, generation-provider
availability, S3 IAM correctness or deployment readiness; those require live smoke
tests and human calibration.
