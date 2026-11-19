**Advertising pipeline**

The pipeline reuses the existing product context, prompts, provider response schemas and public API JSON. It introduces no additional LLM stage or creative-brief JSON. V12 adds internal review and scheduling checkpoints. Restart the backend to apply the migration through Flyway before using this code.

```text
Capture owned product/brand/photo snapshot
  -> select approved concept (persisted batch/attempt/evaluation)
  -> prepare image prompt (PROMPTING -> PROMPT_READY)
  -> render and validate canvas (GENERATING -> REVIEWING)
  -> review output (persist review_json once)
  -> publish creation atomically (SUCCEEDED)
```

**What is reused**

| Boundary | Durable input/output | Recovery |
| --- | --- | --- |
| Submission | Owner-scoped idempotency key, body hash, context/policy snapshot | Repeated identical submissions return the same operation without new analysis or provider work |
| Selection | Sampled batch, exact input hash, completed provider response and evaluations | Rebuild projections from matching completed attempts; do not request a better score through repeated evaluation |
| Prompt writing | Prompt text, model/version, provider ID, cost | A saved prompt is reused without rewriting or charging it again |
| Rendering | Output asset reference/hash, provider ID, cost | REVIEWING resumes from the stored output; it never renders again |
| Review | `generation_jobs.review_json`, existing review result format and adjusted spend | Completed review resumes publication without another LLM call or another usage charge |
| Publication | Creation, allocation consumption, final job status in one transaction | A transient database failure defers publication for five seconds and retries only database work |

STARTED/unknown selection transport outcomes are not automatically redispatched. An interrupted prompt, render or review with no saved result retains conservative accounting and is not blindly repeated. Generation stays in RECONCILIATION_REQUIRED when an external outcome is uncertain. There is no claim of exactly-once execution at an external provider: a process can die between a paid response and its checkpoint. That uncertainty still requires operator reconciliation.

Known invalid ratios and exhausted deadlines stop before downstream work. A generated image with the wrong actual aspect ratio is rejected before S3 upload or output review. Product/brand facts and concept approval are checked at dispatch/review/publication boundaries; those reads protect against publishing stale work. Publication checks the current database deadline, including deadline changes during a review.

**Database efficiency**

- Selection status uses one authorized joined query, replacing five or six queries depending on whether a winner exists. Ad status uses one authorized joined query, replacing nine or ten. Both preserve product/account ownership and 404 behavior without invoking providers.
- Concept batch sampling fetches revisions and asset metadata together: one query instead of one query plus a query per candidate. Completed-attempt lookups fetch at most one result and have a matching index.
- Snapshot image references come from the captured facts rather than a duplicate metadata query. Current-context checks avoid duplicate ownership counts while retaining ownership predicates and mutation locks.
- Lease checks read only the fence, lease, state and deadline fields they need, rather than loading/deserializing full context and policy JSON.
- Prompt preparation keeps its newly saved result in memory for the next stage rather than rereading the full job immediately.
- LLM and S3 work executes outside database transactions. Database transactions cover claims, checkpoints and atomic publication only.
- V12 indexes generation eligibility and selection-attempt reuse. No extra stage-event table or write per token/poll is introduced.

**LLM and asset efficiency**

The successful path still performs the existing selection call(s), one prompt-writing call, one rendering call and one review call. Saved product analysis is reused. Token limits, models, prompt text, input context and provider response schemas remain as configured; this change does not claim lower tokens per successful call.

The savings come from not repeating completed paid stages, rejecting unusable work before downstream calls, and preserving the existing bounded concept rounds and early strong-match exit. Reuse stays within an operation: independent ad requests can intentionally produce different creative output, and no shared prompt/result cache guesses whether two requests are equivalent.

Verified asset bytes are cached for one worker execution, bounded to 32 MiB per active job. Only assets with matching bucket/key/media type/content hash can hit the cache. Fingerprinting seeds the verified entry. Unhashed reads remain fresh, failed reads are not cached, and the cache is discarded at the end of the execution. Retries/restarts revalidate S3 data. For a normal one-photo execution with a separate reference, this avoids repeated product/reference downloads between prompting, rendering and review. Large executions exceeding the cap remain correct but can reread uncached assets.

**Scheduling and capacity**

Selection and generation have independent bounded lanes. The default is one active selection and one active generation per application instance, allowing a long image call without blocking the selection queue. Only free worker capacity is requested from the database; there is no unbounded in-memory job backlog. Database leases/fences remain authoritative across replicas.

| Property | Default | Meaning |
| --- | --- | --- |
| `giraffix.selection.worker-enabled` | `true` | Enable both pipeline lanes |
| `giraffix.selection.poll-ms` | `1000` | Scheduler tick interval |
| `giraffix.pipeline.selection-concurrency` | `1` | Per-instance selection slots, 1–16 |
| `giraffix.pipeline.generation-concurrency` | `1` | Per-instance generation slots, 1–16 |
| `giraffix.pipeline.max-idle-poll-ms` | `10000` | Empty-lane exponential scan backoff cap, 1000–60000 ms |

Idle scans back off from one second to the configured cap; a completely idle service can therefore take up to that cap plus a scheduler tick to discover new work. Lower the cap if cold-queue latency matters more than idle DB traffic. Busy lanes do not poll when all their slots are occupied. Unavailable image-provider jobs are deferred for up to 60 seconds (bounded by the job deadline), preventing them from monopolizing the oldest-job scan.

Concurrency is a local capacity bound, not a global/account spending quota. Increasing replicas multiplies capacity. Select explicit aggregate limits before opening the system to unrestricted usage; per-job token/cost envelopes are estimates and cannot impose a provider-side billing cap.

**Verification**

The full local Maven suite passed on 18 November 2026: **235 tests, zero failures, zero errors, zero skipped; BUILD SUCCESS**. Evidence: [final test log](../target/pipeline-tests-final.log).

Regression coverage includes completed-prompt reuse, persisted-review recovery, one-time cost accounting after a transient publication failure, interrupted-review non-replay, provider-wait backoff, deadline expiry during review, wrong-ratio rejection before upload/review, external calls outside transactions, asset-cache hash/isolation behavior, bounded lane admission, independent lanes, idle backoff, ownership and unchanged API/provider contracts. Provider HTTP tests use local servers and S3 is mocked. Production PostgreSQL query plans, live model behavior and deployment throughput have not been benchmarked by this change.
