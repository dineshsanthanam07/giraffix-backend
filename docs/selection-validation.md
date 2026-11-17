# Selection validation record

Verified locally on 14 November 2026 with Java 17 and Maven 3.9.9.

| Run | Result | Evidence |
| --- | --- | --- |
| Complete regression suite, including original 92 tests | 149 passed, 0 failures/errors/skips | `target/selection-full-tests.log` |
| Selection scenarios using isolated PostgreSQL 18.6 | 57 passed, 0 failures/errors/skips | `target/selection-postgres-tests.log` |
| Git whitespace check | Passed | `git diff --check` |

The PostgreSQL run repeats the 57 new tests; these are not 57 additional unique
tests beyond the full suite. It validates migrations through V7, preservation of
existing concept IDs/assets as drafts, the actual partial unique allocation index,
rating constraints, concurrent idempotency, reservations and cooldown projections.

Workflow coverage includes automatic/manual selection, independent verification,
invalid/unknown/duplicate results, evidence resolution, deadline/call/cost limits,
context edits and revocation, uncertainty handling, cooldown isolation/expiry,
durable response recovery, stale worker fencing, generation dispatch/recovery,
private creation delivery, ownership, authentication and CSRF.

Claude adapters were tested through a local HTTP server. S3, image generation and
workflow output review use mocks; the actual output-review adapter also has local
HTTP contract coverage. No live provider credits or production bucket writes were
used. No deployment, live image generation, production IAM validation, or human
creative-quality calibration is asserted.

The temporary PostgreSQL server used only workspace-local data and was stopped
after verification. See [runtime setup and limitations](concept-selection.md),
especially the required image-generation provider adapter and reconciliation path.
