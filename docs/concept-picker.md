# Product concept picker

The default first step of ad generation uses the supplied Concept Picker v1 prompt. It evaluates approved concepts against the owned product's actual saved photos, name, category, description, personality/key features, audience, and brand information. The backend chooses the concept; the model returns evaluations, not a winner or an image-generation prompt.

## Configuration

In `src/main/resources/application.properties`:

```properties
giraffix.selection.picker-enabled=true
giraffix.selection.batch-size=5
giraffix.selection.threshold=80
giraffix.selection.max-rounds=3
```

Batch size supports 1–100 and threshold supports 0–100. Larger batches remain subject to the existing token, cost, and deadline limits. Properties are snapshotted on enqueue, so changing configuration does not alter in-progress requests. The same threshold is substituted into the prompt and used by Java validation, exclusions, and qualification.

## Selection behavior

1. Randomly sample up to five approved concepts. Omit the product's saved exclusion pairs, concepts already sampled in this request, and concepts already reserved/consumed for this product and owner.
2. Send every saved product photo, structured product and brand details, and the batch's concept cards in one Claude call using `prompts/concept-picker.txt` and `schemas/concept-picker.json`.
3. Validate exactly one result per supplied ID, integer scores from 0 to 100, supported classifications, nonblank adaptation directions, and the complete exclusion list. Reject missing, duplicate, foreign, malformed, or contradictory results before saving exclusions or evaluations.
4. Persist all evaluations. Save each below-threshold `(product_id, concept_id)` pair in `product_concept_exclusions`.
5. If any result is `STRONG` and scores at least the threshold, choose the highest-scoring qualifying result and stop. Ties use stable concept IDs.
6. Otherwise draw a fresh batch, for at most three rounds total, not three additional retries. A valid low score does not retry the same batch.
7. If none qualifies, select the highest-scoring evaluated concept across all rounds. This fallback includes `CONDITIONAL` and `NOT_SUITABLE`, following the request to take the maximum among all concepts. It is marked by `fallbackUsed=true`.

If the eligible pool runs out early, use all available results and apply the same fallback. If no candidates were evaluated, no winner or job is invented. Provider errors, token/cost limits, stale context, and deadlines retain their explicit failure outcomes; they do not manufacture a fallback from invalid or incomplete provider results.

The winner must still be approved and available when reserved. If another request reserved it, try the next eligible ranked result. Ownership and current context are checked under locks. Allocation, saved brief, generation job, and outbox event commit together. Picker mode does not run the former five-dimension verification call, which would otherwise veto the requested score-based fallback. Generated outputs still undergo the existing output review.

## Conflicting prompt rules

The supplied prompt says both "include every below-threshold score" and "include only NOT_SUITABLE classifications" in `notSuitableConceptIds`. This implementation consistently follows the requested **score-based** exclusion rule:

- Score below the threshold: include the ID, regardless of classification.
- Score equal to or above the threshold: omit the ID, regardless of classification.
- Automatic qualification still requires both `STRONG` and a sufficient score.

The resource prompt replaces the conflicting output rules with this single rule. The backend verifies that the returned list exactly matches the below-threshold set. Scores do not rewrite classifications. A high-scoring `CONDITIONAL` result is not excluded and does not qualify automatically; it can win through fallback.

Exclusions are permanent for that product ID, including across edits, concept revisions, and threshold changes. They do not affect another product. A below-threshold fallback winner remains in the exclusion table; its allocation also prevents reuse. No reset/expiry behavior is introduced. The table records score, classification, evaluated revision, request, threshold, and time for audit. Deleting a product cascades its exclusions.

## API

Use the existing authenticated, CSRF-protected asynchronous API. The product needs owned S3-uploaded photos and confirmed name/category.

```http
POST /api/v1/selection-requests
Idempotency-Key: unique-client-operation
Content-Type: application/json

{
  "productId": "PRODUCT_UUID"
}
```

HTTP 202 supplies a status URL in `Location`. Poll `GET /api/v1/selection-requests/{id}`. Repeating the same owner's key/body returns the existing request; changing its body returns 409.

Only `productId` is required in the body. Product details, saved photos, and brand information are loaded automatically. Omitted or null `intent` defaults to "Create a distinctive advertisement for this product"; omitted or null `format` defaults to "square social advertisement". Existing clients may still supply `intent`, `format`, `controls`, and `conceptId` as optional overrides. Explicit blank intent/format strings are rejected. Defaults are applied before idempotency hashing, so omitted fields and the same explicitly supplied defaults identify the same operation. Authentication, CSRF, and the `Idempotency-Key` header remain required.

Relevant fields after selection:

```json
{
  "status": "SELECTED",
  "rounds": 3,
  "verificationCalls": 0,
  "selected": {
    "conceptId": "CONCEPT_UUID",
    "compatibilityScore": 76,
    "classification": "CONDITIONAL",
    "adaptation": "The product-specific adaptation direction returned by the picker."
  },
  "fallbackUsed": true,
  "generationJobId": "JOB_UUID"
}
```

This is an excerpt; existing status/evaluation metadata remain. The provider's `adaptationDirection` maps to the existing internal/API `adaptation` field and is saved in the generation brief. A supplied `conceptId` evaluates only that approved concept, applying fallback if needed; it does not switch to random alternatives or bypass saved exclusions.

`SELECTED` means a concept and job exist. Use the [single ad-generation flow](ad-generation.md)
with a mandatory `aspectRatio` for automatic prompt writing and direct OpenAI image generation.
The OpenAI adapter requires `OPENAI_API_KEY`; it does not use OpenRouter. The legacy
selection endpoint also accepts `aspectRatio`, but older requests without a ratio do
not acquire one implicitly and cannot use this new rendering adapter.

## Logging

Picker diagnostics are written at INFO level to the console and `logs/giraffix.log`. Filter by `requestId=<selection request UUID>` or `stage=concept_picker_`:

- `concept_picker_batch_started`: round, sampled concept IDs, and threshold.
- `concept_picker_batch_completed`: every evaluated concept's ID, score, and classification, plus `notSuitableConceptIds` (the score-based exclusion list) and `classifiedNotSuitableConceptIds` (only the `NOT_SUITABLE` classification).
- `concept_picker_selected`: chosen concept ID, score, classification, and fallback flag.
- `concept_picker_finished`: final request status and number of rounds, including an empty pool.

Batch results are logged only after validation and database commit. Raw provider responses, adaptation text, product/brand descriptions, prompts, credentials, and image bytes are not included in these diagnostics. Older results remain in the evaluation ledger; new logging does not retroactively produce log entries for previous requests.

## Persistence and compatibility

Flyway V8 adds the exclusion table, dedicated score/classification columns on the evaluation ledger, and the fallback flag. V1–V7 are unchanged. Legacy rating columns remain null for picker evaluations rather than fabricating five-dimension ratings. Existing evaluation foreign keys link reservations and creations to the actual picker evaluation.

Previously queued requests retain their saved legacy policy and finish through the former workflow. Setting `giraffix.selection.picker-enabled=false` enqueues legacy-policy requests with the former scoring, independent verification, and contextual cooldown behavior. Persistent picker exclusions apply to picker sampling. Ownership, CSRF, budgets, idempotency, attempt recovery, and fencing remain in place.

Tests cover default/custom settings, first/second-round acceptance, three-round fallback, fallback classification, disjoint batches, partial pools, per-product exclusions, edits, malformed output, provider payloads, exact thresholds, concurrent reservations/exclusions, stale context, cached-response recovery, and legacy behavior. Provider HTTP tests use a local server; workflow tests mock provider/S3 calls, using no live credits.

Verified on 15 November 2026 with Java 17 and Maven 3.9.9:

| Run | Result | Local evidence |
| --- | --- | --- |
| Complete regression suite | 177 passed, 0 failures/errors/skips | `target/concept-picker-full-tests.log` |
| Selection/picker scenarios on isolated PostgreSQL 18.6 | 72 passed, 0 failures/errors/skips | `target/concept-picker-postgres-tests.log` |

The PostgreSQL checks repeat a subset of the regression suite; they are not 72 additional unique tests. That run used a fresh workspace-local cluster on port 55440 and the disposable `selection_checks` database. The temporary server was stopped after verification. No application database or production bucket was changed.
