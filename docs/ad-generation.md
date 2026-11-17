# Single ad-generation flow

One client action runs concept matching, creative direction, image generation,
output review, and persistence. The application uses the saved product photos,
saved reviewed product description and other details, its brand details, optional
brand logo, selected concept/adaptation, and mandatory user-selected aspect ratio.
It does not re-analyze the product or ask the user to choose a concept mid-flow.

Claude performs concept matching and writes one image-generation prompt using the
complete supplied [Creative Director v2 prompt](../src/main/resources/prompts/ad-creative-director-v2.txt).
OpenAI `gpt-image-2` then renders the advertisement directly through `/v1/images/edits`.
There is no OpenRouter integration in the application.

## Request and final output

The existing Google session and CSRF requirements apply. First save the product and
at least one product photo. Its brand must belong to the signed-in user. Concepts
must have approved revisions; configure the concept-review administrator allowlist
and approve library entries before requesting ads.

```http
POST /api/v1/ads
Idempotency-Key: ad-operation-001
Content-Type: application/json
X-XSRF-TOKEN: YOUR_CSRF_TOKEN

{
  "productId": "PRODUCT_UUID",
  "aspectRatio": "4:5",
  "quality": "medium"
}
```

`intent` and `controls` are optional bounded strings. Brand, description, photos,
and logo are loaded from the owned saved product; clients cannot substitute another
account's brand/logo. Supported ratios are `1:1`, `4:5`, `3:4`, `16:9`, and `9:16`.
Missing, blank, `auto`, and unsupported ratios return 400. The same owner's operation
key with the same input returns the existing flow; different input with that key
returns 409. Missing OpenAI configuration returns 503 before matching is queued.

`quality` is a choice alongside `productId` and `aspectRatio`, with exactly `low`,
`medium`, and `high` accepted. It defaults to `high` when omitted or null. Swagger
lists these choices in the request schema. Empty/unknown values return 400 before
provider calls. The selected quality is snapshotted, sent directly to OpenAI, and
returned in status and creation history. Changing quality while reusing an operation
key returns 409; omitted quality and explicit `high` represent the same operation.

The POST returns **202 Accepted**, a flow status object, and
`Location: /api/v1/ads/{id}`. Poll that URL with the same session. Polling is part of
the same flow; no separate generation request is necessary. Rendering can take
minutes, so the API does not keep a browser request open until completion.

Example completed response:

```json
{
  "id": "SELECTION_REQUEST_UUID",
  "status": "SUCCEEDED",
  "selectionStatus": "SELECTED",
  "generationJobId": "JOB_UUID",
  "creationId": "CREATION_UUID",
  "imageUrl": "/api/v1/creations/CREATION_UUID/image",
  "aspectRatio": "4:5",
  "quality": "medium",
  "fallbackUsed": false,
  "error": null
}
```

GET `imageUrl` with the session cookie to receive the actual image bytes. It is an
authorized image endpoint, not a publicly readable S3 object. Before success,
`creationId` and `imageUrl` are null. Product creation history also includes each
creation's `aspectRatio` through `/api/v1/products/{productId}/creations`.

Selection uses the existing picker rules: random approved batches, threshold,
bounded rounds, durable exclusions, and marked highest-score fallback. If matching
fails or the pool is empty, the flow reports that outcome without generating an
unrelated ad. Fallback can include a concept classified NOT_SUITABLE under the
existing product policy; inspect `fallbackUsed`.

## Actual visual references

- Prompt writing receives every saved product photo, a separately labelled concept
  reference, product/brand facts, selected concept and adaptation, ratio, and the
  optional separately labelled official brand logo.
- Rendering receives the completed prompt and every actual product photo as base64
  image references. The optional official logo is the final, separately identified
  image reference. Private bucket URLs need not be made public for either provider.
- The concept reference's source product is not supplied to the renderer as another
  product reference. Its mechanism and adaptation are expressed by the written prompt.
- Output review sees the actual product views, optional logo, concept reference,
  generated image, and saved prompt. It rejects uncertain product/concept/logo fidelity.

The system explicitly instructs both models to preserve product packaging, proportions,
colours and labels; use the official logo faithfully when supplied; and avoid inventing
a logo when absent. Generative fidelity and text/logo correctness are model judgments,
not a pixel-identical compositing guarantee.

Aspect ratio is enforced twice: the request uses explicit GPT Image 2 dimensions,
then the backend decodes the output and checks its actual ratio. It never crops or
stretches a wrong-ratio result to disguise a mismatch.

| User ratio | Requested pixels |
| --- | --- |
| 1:1 | 1024 × 1024 |
| 4:5 | 1024 × 1280 |
| 3:4 | 960 × 1280 |
| 16:9 | 1536 × 864 |
| 9:16 | 864 × 1536 |

## Configuration and storage

Set `OPENAI_API_KEY` in the backend process environment and restart. Do not put the
key in request JSON or frontend code. The current properties bind it to:

```properties
giraffix.generation.openai.api-key=${OPENAI_API_KEY:}
giraffix.generation.openai.base-url=https://api.openai.com/v1
giraffix.generation.openai.model=gpt-image-2
giraffix.generation.prompt-model=claude-sonnet-5-5
giraffix.generation.prompt-reserve-micros=400000
giraffix.generation.prompt-max-output-tokens=8192
```

Claude prompt writing uses the existing `giraffix.ai` credentials/base URL. Model
availability and account access must be checked in the deployment environment.
GPT Image 2 is the explicitly requested model; OpenAI currently marks it deprecated,
so the model property is configurable. Switching model families also requires checking
their accepted dimensions, reference-image parameters, and price settings.

Flyway **V10** adds the persisted prompt/provenance/cost fields to generation jobs
and prompt/version/ratio to creations. Existing migrations are unchanged. The image
bytes are stored privately in S3 at `creations/{ownerId}/{jobId}/output`. PostgreSQL
stores the S3 bucket/key/media type/hash in `asset_json`, plus creation ownership,
concept/evaluation links, image prompt, prompt version, review, and aspect ratio.
It does not duplicate the image bytes in PostgreSQL.

Flyway **V11** adds creation quality and safe generation-failure diagnostics. Restart
the backend to apply it. Earlier jobs with no quality continue to render at `high`.
The original request hashes are preserved for default high quality and legacy requests.

Grant S3 GetObject for the relevant `products/*`, `concept/*`, `brands/logos/*`, and
`creations/*` objects, and PutObject for `creations/*`, in addition to existing upload
permissions. Output is PNG and currently limited to 5 MB by shared asset validation.

## Job lifecycle and recovery

Generation stages are `QUEUED`, `PROMPTING`, `PROMPT_READY`, `GENERATING`, `REVIEWING`,
then `SUCCEEDED`. An unconfigured provider on an already queued legacy job produces
`WAITING_FOR_PROVIDER`. The new POST fails fast when the OpenAI key is absent.
Failures retain explicit selection outcomes, `FAILED`, `STALE_CONTEXT`,
`OUTPUT_REJECTED`, or `RECONCILIATION_REQUIRED` as applicable.

A completed prompt-provider response that is truncated, incomplete, or invalid now
produces `PROMPT_FAILED` with an `error` object containing a safe `code`, `stage`,
and `message`. Because no image request was dispatched, the concept reservation is
released. Prompt stop reason, provider ID, and known usage are recorded without raw
prompts or image bytes. The default prompt output allowance is now 8192 tokens and
its read timeout is bounded at 90 seconds and the remaining job deadline. It can be
changed with `giraffix.generation.prompt-max-output-tokens`, subject to the snapshotted
prompt cost reserve. Unknown transport outcomes still require reconciliation and
are not automatically retried.

The completed prompt is committed before rendering starts. Recovery of PROMPT_READY
can reuse it without another prompt-writing call. Unknown prompt/render/review
outcomes are not blindly retried; they require reconciliation and preserve the
reservation. Inspect provider operation IDs and recorded assets before any manual
release/reset. No operator reconciliation endpoint is implemented.

Policy/context, photo/logo hashes, and generation limits are captured for the job.
Product/brand changes are checked again before rendering, before review, and under
publication locks. Deadlines are checked before expensive stage transitions and
publication. Leases are renewed while a live worker performs long external calls.
Fencing prevents an expired worker from publishing after another worker takes over.

The default total generation budget is 2,000,000 micro-USD, including a 400,000
prompt reserve and 300,000 review reserve. Token-derived costs are estimates using
configured rates. The direct image adapter's default rates in micro-USD per million
tokens are text input 5,000,000, image input 8,000,000, and output 30,000,000; override
`giraffix.generation.openai.text-input-micros-per-million`,
`giraffix.generation.openai.image-input-micros-per-million`, and
`giraffix.generation.openai.output-micros-per-million` if the model/pricing changes.
Missing image usage retains the conservative reservation. There is no provider-side
per-request dollar cap: an over-budget response is detected after the billed call,
so account spending controls remain separate from this application ledger.

S3 and PostgreSQL do not share a transaction. Objects from rejected, stale, or
uncertain jobs are retained with job metadata for investigation. A crash after an
upload can leave an object requiring manual reconciliation; no retention/cleanup
worker is introduced by this feature.

## Validation and references

Tests exercise the real prompt-writing and direct image HTTP adapters against a
local server, the database workflow, mocked S3 storage/readback, final private image
delivery, optional logos, ratios, idempotency, ownership/CSRF, interrupted stages,
stale context, and invalid provider outputs. No live provider credits are consumed.
Verified on 17 November 2026 with Java 17 and Maven 3.9.9: **220 tests passed,
0 failures, 0 errors, 0 skipped**. The complete run took approximately 60 seconds.
The full regression log is [ad-quality-full-tests.log](../target/ad-quality-full-tests.log).
Live OpenAI/Claude calls and production PostgreSQL/S3 access were not exercised.

Provider contracts were checked against the official
[image-edit API](https://developers.openai.com/api/reference/resources/images/methods/edit),
[image-generation guide](https://developers.openai.com/api/docs/guides/image-generation),
[GPT Image 2 model](https://developers.openai.com/api/docs/models/gpt-image-2), and
[pricing](https://developers.openai.com/api/docs/pricing).
