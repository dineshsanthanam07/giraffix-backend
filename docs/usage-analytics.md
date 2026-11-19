**Admin usage analytics**

This feature is read-only analytics. It introduces **no user quota, spending cap,
rate limit, or usage-based admission check**. No limit-management API exists.
Existing pipeline policies and upload/security limits retain their previous behavior.

Restart the backend to apply **Flyway V17**, then sign in as an allowlisted
administrator and open **`/admin/usage`**. It uses the same
`giraffix.selection.admin-subjects` configuration as prompt administration. Ordinary
users cannot view the dashboard or its APIs. Credentials and the administrator
allowlist are not changed by this feature.

**Dashboard**

The page shows request counts, successful and failed requests, recorded input/output
tokens, estimated recorded costs, pending-work estimates, and uncertain usage.
Tables break activity down by user, model/stage, day, and individual operation.
Select an operation's **View calls** button for stage/provider token details.
User, product, brand, activity, and UTC date filters apply across the reports.
User/model/operation/call tables are paginated. Apply filters to refresh the data;
this dashboard does not make AI calls.

The three tracked activity kinds are:

- `AD`: concept selection through prompt writing, image rendering, and output review.
- `PRODUCT_ANALYSIS`: the authenticated multipart product-analysis endpoint.
- `CONCEPT_ANALYSIS`: the authenticated concept-extraction endpoint.

Product analysis occurs before a product is saved, so its product/brand IDs are null.
Concept extraction is independent of brands/products, and those IDs are also null.
Both are attributed to the signed-in user. Ad operations retain owner/product/brand
IDs after product deletion or explicit saved-ad deletion. Deletion does not erase
historical usage or refund recorded costs.

**Read APIs**

Every route below requires the administrator allowlist and a login session. These
are GET requests; no new mutation or CSRF flow is introduced.

| Route | Result |
| --- | --- |
| `/api/v1/admin/usage` | Overall/filtered summary, with cost unit and basis |
| `/api/v1/admin/usage/users` | Per-user identity and usage totals |
| `/api/v1/admin/usage/models` | Recorded calls/attempts, tokens, and estimates by model and stage |
| `/api/v1/admin/usage/daily` | Daily UTC totals |
| `/api/v1/admin/usage/operations` | Individual tracked operations |
| `/api/v1/admin/usage/operations/{id}` | One operation, including status/outcome and token completeness |
| `/api/v1/admin/usage/operations/{id}/calls` | Recorded stage/provider details for that operation |

Summary, user, model, daily, and operation-list endpoints support:

```text
?from=2026-11-01&to=2026-12-01
&userId=USER_UUID&productId=PRODUCT_UUID&brandId=BRAND_UUID&kind=AD
```

All filters are optional and combine together. `from` is inclusive and `to`
exclusive, interpreted in UTC. The default is the current UTC calendar month.
An individual report can span up to 366 days; request additional date windows for
older data. This is a report-query bound, not a limit on a user's activity.
Paginated endpoints accept `page=0&size=20`, with sizes 1–100.
The operation ID is the existing selection/operation ID for ads and a generated
tracking UUID for synchronous analysis.
Date reports group activity by the operation's creation date; stages completed
later remain attributed to that original date. Request counts describe tracked
operations, excluding status polling, progress streams, and idempotent ad replays.

Summary example:

```json
{
  "unit": "USD_MICROS",
  "costBasis": "ESTIMATED",
  "usage": {
    "requests": 1,
    "completedRequests": 1,
    "failedRequests": 0,
    "activeRequests": 0,
    "uncertainUsageOperations": 0,
    "recordedInputTokens": 150,
    "recordedOutputTokens": 15,
    "incompleteTokenOperations": 0,
    "accountedCostMicros": 300,
    "pendingEstimateMicros": 0,
    "totalEstimatedExposureMicros": 300
  }
}
```

**Interpretation and accuracy**

- Token totals sum recorded provider input/output values. Missing counts stay null
  in call details; summary totals include only available counts. The dashboard
  flags operations with incomplete token data. Zero recorded tokens does not prove
  a provider consumed zero tokens.
- Request outcome and usage certainty are separate. A successful analysis with
  missing provider usage remains a successful request, while usage is uncertain.
  Operation `outcome` describes the request result, `status` preserves workflow
  status, and `state` describes tracking/accounting state.
- Active counts represent the latest persisted active/unfinished tracking state,
  not a process-liveness check. Crashes can leave unfinished synchronous operations.
- One dollar is 1,000,000 microdollars. All costs are **estimates**, not provider
  invoices. Ad accounting reuses persisted pipeline cost/usage records. Synchronous
  extraction uses the configured input/output rates below. Cached-token discounts,
  account-specific pricing, taxes, storage, and network charges are not reconciled.
- `accountedCostMicros` is the recorded cost estimate, including conservative
  accounting where the existing pipeline or synchronous call has uncertain usage.
  `pendingEstimateMicros` is the additional planned estimate for unfinished work.
  Their sum is `totalEstimatedExposureMicros`. None of these numbers gates requests.
- A pre-provider validation failure records a failed operation with no model cost.
  An unknown dispatched outcome retains a conservative estimate. Known usage is
  retained even when response validation or later persistence fails.
- Recorded model-call counts include selection attempts. They are diagnostic
  counts, not proof of the number of invoice line items. Historical model-stage
  breakdowns can be incomplete even when an operation's total cost was persisted.

**Historical coverage and recovery**

At application startup, historical ad operations are adopted in bounded batches.
Original request timestamps and available user/product/brand attribution are kept.
A startup-only database mutex coordinates adoption across replicas. It does not
serialize or restrict ordinary user activity.

Historical selection tokens already persisted in `selection_attempts` are included.
Historical prompt/render/review token counts were not previously stored and cannot
be reconstructed reliably; their coverage is marked incomplete. Historical product
and concept analysis calls were not attributed in a durable usage ledger and are
tracked from this update onward. No synthetic token counts are backfilled.

New generation stage metrics commit with the existing paid-result checkpoints.
Reusing a completed prompt/review or replaying an ad idempotency key does not create
another usage operation or duplicate recorded stage usage. A crash between a remote
response and its checkpoint can still leave incomplete provider evidence; this
feature does not claim exactly-once provider execution or resolve reconciliation.

**Estimate settings**

| Property | Default | Purpose |
| --- | --- | --- |
| `giraffix.usage.analysis-estimate-micros` | `350000` | Conservative estimate for uncertain synchronous calls |
| `giraffix.usage.analysis-input-micros-per-million` | `2000000` | Configured synchronous input-token estimate rate |
| `giraffix.usage.analysis-output-micros-per-million` | `10000000` | Configured synchronous output-token estimate rate |

These values affect reporting, not permissions, request counts, or admission.
The synchronous rate pair is shared by product/concept analysis; set it deliberately
for the intended estimate policy. Existing ad-stage rate policies continue to apply.
Rates are not fetched from provider pricing pages, and reported estimates are not
recalculated retroactively when configuration changes.

**Data handling**

Tracking stores identifiers, stage/model/provider metadata, counts, estimates, and
timestamps. It adds no image bytes, raw provider bodies, or prompt contents to the
usage tables. User names/emails are visible only through authorized admin reports.
The dashboard renders dynamic values with text DOM APIs and uses no external scripts.

**Validation**

The full offline Maven suite passed with **301 tests, zero failures, zero errors,
and zero skipped**. Evidence: `target/usage-analytics-full-tests.log`. Checks cover
real local-provider token extraction for product/concept/ad endpoints, stage and
user totals, cost aggregation without duplicated joins, historical adoption,
idempotent replay, preserved usage after deletion, unknown/missing data,
independent success outcomes, admin/session protection, dashboard access,
pagination/date validation, and absence of quota/limit tables or mutation APIs.
The dashboard JavaScript passed `node --check`. Tests use H2, local provider
fixtures, and mocked S3. Live provider invoices, production PostgreSQL, and an
interactive browser session were not exercised.
