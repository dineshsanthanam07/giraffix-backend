# Giraffix backend

The [single ad-generation flow](docs/ad-generation.md) accepts a saved product and
user-selected aspect ratio, matches a concept, writes the supplied v2 creative-director
prompt with Claude, renders with OpenAI `gpt-image-2` directly, reviews the output,
and saves the image in S3 with its metadata and prompt in PostgreSQL.
Start with `POST /api/v1/ads`; poll the returned Location for the final image URL.

For live stage updates, connect `EventSource` to
`GET /api/v1/ads/{operationId}/events` using the same login session. It emits
authorized `progress` events through matching, prompting, rendering, and review,
then returns the final image URL on success. See [live ad progress](docs/ad-progress.md)
for frontend code, reconnect/error handling, limits, and proxy configuration.

Saved ads survive product deletion and remain accessible to their original owner.
Use `GET /api/v1/ads` with optional `productId`/`brandId` filters, and
`GET /api/v1/ads/saved/{creationId}` for details. Explicitly delete an ad with
`DELETE /api/v1/ads/saved/{creationId}`; this queues only that generated image for
cleanup. Restart to apply Flyway V16. See [saved ads](docs/saved-ads.md) for IDs,
image reads, migration behavior, and deletion semantics.

The [durable pipeline](docs/pipeline.md) reuses completed provider stages, checkpoints
output reviews, bounds worker concurrency, and reduces database polling and repeated
asset downloads. Restart the backend to apply Flyway V12 after this update.

Admins can edit and publish versioned prompt templates at `/admin/prompts`; see
[admin prompt editor](docs/admin-prompts.md). Flyway V13/V14 add revision storage
and PostgreSQL publication notifications. Runtime prompts are cached without a
timer; publication invalidates replicas instead of querying the DB per generation.

Admins can view token, model, user, daily, and operation analytics at `/admin/usage`.
The read-only `/api/v1/admin/usage` APIs support user/product/brand/date filters.
Flyway V17 adds usage tracking and adopts historical ad records on startup.
This introduces no usage or spending limits. See [usage analytics](docs/usage-analytics.md)
for coverage, token completeness, and estimated-cost interpretation.

The ad request accepts product ID, ratio, and quality together:

```json
{
  "productId": "PRODUCT_UUID",
  "aspectRatio": "3:4",
  "quality": "medium"
}
```

Quality choices are `low`, `medium`, and `high` (default); Swagger lists both quality
and aspect-ratio choices. Restart the backend to apply Flyway V11 after this update.

The default ad-selection flow uses the [product concept picker](docs/concept-picker.md):
five random concepts per round, an 80-point threshold, up to three rounds, and a highest-score fallback.
Batch size, threshold, and round limit are configurable; below-threshold product–concept pairs are excluded from future picker requests.

Concept selection, approval, durable jobs and testing are documented in
[docs/concept-selection.md](docs/concept-selection.md).

Spring Boot backend for Google sign-in, brand onboarding, products, and Claude image analysis.
Code follows controller -> service -> repository, organized by feature. PostgreSQL stores
User -> Brand -> Product relationships. This repository has no frontend.

## Setup

- Install JDK 17 or newer; set JAVA_HOME. The build targets Java 17.
- Install PostgreSQL and create the database and role configured in
  src/main/resources/application.properties. OAuth and database credentials are unchanged.
- Set ANTHROPIC_API_KEY. Override GIRAFFIX_AI_MODEL if the configured model is unavailable
  to your account.
- Run `.\mvnw.cmd spring-boot:run` on Windows or `./mvnw spring-boot:run` elsewhere.
  An installed Maven 3.9+ can also run `mvn spring-boot:run`.

The server uses port 8080. Open `/oauth2/authorization/google` to log in.
The callback is `/login/oauth2/code/google`; successful login redirects to
`/api/v1/users/me`. Swagger UI: `/swagger-ui.html`.

If Maven uses an inaccessible local repository, pass
`"-Dmaven.repo.local=$env:USERPROFILE\.m2\repository"` in PowerShell or configure
a writable localRepository in Maven settings.

## Database migrations

Flyway owns schema changes in `src/main/resources/db/migration`; Hibernate validates
the schema instead of modifying it. An empty database receives V1 on startup.
For an existing database previously managed by Hibernate:

1. Back it up and compare its tables, types, constraints, and indexes with V1.
   Reconcile differences first; Hibernate validation does not check every constraint/index.
2. Start the application. The current configuration enables `baseline-on-migrate`
   with `baseline-version=1` for the existing Hibernate-created database. Flyway
   records that schema as version 1 without recreating its tables. Hibernate then
   validates the mapped columns; baselining does not repair a mismatched schema.
3. After the first successful startup, set `spring.flyway.baseline-on-migrate=false`
   in application.properties. The history table now exists and later migrations
   will apply normally. Do not use automatic baselining on unrelated databases.

Never edit an applied migration; add V2, V3, etc. for later changes.

## API

See the [API inventory and operating guide](docs/api-and-operations.md) for route
groups, pagination, legacy opt-in, query/storage changes and diagnostics. Brand and
product lists now default to 20 rows; request subsequent pages explicitly.

All `/api/v1/**` routes require a session. Send the session cookie on requests.
After login, GET `/api/v1/security/csrf` and send its returned header name and token
on POST, PATCH, DELETE, and logout requests.

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/v1/users/me` | User and onboarding state |
| GET, POST | `/api/v1/brands` | List/create owned brands |
| GET, PATCH | `/api/v1/brands/{id}` | Read/update owned brand |
| GET, POST | `/api/v1/brands/{id}/products` | List/create products |
| GET, PATCH, DELETE | `/api/v1/products/{id}` | Read/update/delete product |
| POST | `/api/v1/products/{id}/images` | Append image files to a saved product |
| POST | `/api/v1/analyze` | Analyze multipart files `images` together as one product |
| POST | `/api/v1/ads` | Start concept matching through final ad image generation |
| GET | `/api/v1/ads/{id}` | Poll progress and retrieve the final authenticated image URL |
| POST | `/logout` | End session |

Creating a brand marks onboarding complete. Brand creation requires name; product
creation requires name and category. PATCH permits omitted fields; null leaves the
stored value unchanged. Blank names/categories, invalid colors, and overlong fields
are rejected. Ownership violations return 404.

### Brand logo upload

Create a brand with its logo using `POST /api/v1/brands` with multipart/form-data:

- `brand`: the BrandRequest JSON, with part Content-Type `application/json`.
- `logo`: one nonempty image file, at most 5 MB.

```sh
curl -X POST http://localhost:8080/api/v1/brands \
  -b cookies.txt -H "X-XSRF-TOKEN: YOUR_CSRF_TOKEN" \
  -F 'brand={"name":"Example","industry":"Retail","primaryColor":"#123456"};type=application/json' \
  -F "logo=@logo.png"
```

Replace a logo with `PATCH /api/v1/brands/{brandId}`, using the same multipart parts.
Send `{}` in `brand` to replace only the logo, or include fields to update alongside it.
The existing JSON create/PATCH endpoints remain available: JSON creation has no logo,
and text-only PATCH preserves the stored logo. Existing brands need no backfill.

Create, update, individual GET, and list responses include `logoUrl` (null when absent).
Image bytes are stored in S3 under `brands/logos/`; PostgreSQL stores the URL, bucket,
object key, media type, and size on the brand row. Flyway V9 adds these nullable columns
on the next backend startup. Images use the existing content detection and normalization:
JPEG/PNG/GIF/WebP are kept, supported BMP/TIFF formats convert to PNG, and SVG/HEIC/AVIF
need conversion or additional decoder support. PNG is suitable for transparent logos.

Ownership is checked before replacement upload and again under a database update lock.
A failed upload does not change the brand. If saving/committing fails, the new object
is deleted on a best-effort basis. After a successful replacement commits, the old
object is deleted. Cleanup failures log `brand_logo_cleanup_failed` for manual recovery;
there is no durable background cleanup worker. S3 and PostgreSQL cannot commit atomically,
so process interruption can still leave an orphaned object.

Extend S3 IAM access to allow `s3:PutObject` and `s3:DeleteObject` on
`arn:aws:s3:::YOUR_BUCKET/brands/logos/*`. Configure read access separately: `logoUrl`
is a permanent object URL, and uploading does not make a private bucket public.
The existing session and CSRF requirements apply. In Swagger, choose multipart/form-data
for brand creation/updating and attach the logo file.

Upload 1–5 photos of ONE product using repeated multipart `images` fields. The legacy
`image` field is also accepted; do not send both names in the same request. All images
are validated before one Claude call containing every image and the shared prompt.
The response is one product object with a combined description, not one response per
photo. The prompt combines matching front/back/label views and omits conflicting details.

For example, after login (using your session cookie and fresh CSRF token):

```sh
curl -X POST http://localhost:8080/api/v1/analyze \
  -b cookies.txt -H "X-XSRF-TOKEN: YOUR_CSRF_TOKEN" \
  -F "images=@front.jpg" -F "images=@back.jpg" -F "images=@label.png"
```

In Swagger, use `images` -> Add item to select several files. Restart the backend and
refresh Swagger after this change. Existing single-file clients using `image` still work.
Swagger documents only `images`; the legacy alias is handled separately. An
`array<string>` label represents binary file items in OpenAPI, not text input values.

Analysis accepts JPEG, PNG, GIF and WebP directly. BMP, TIFF, WBMP and other formats
with installed ImageIO readers are converted to PNG (first page only). Detection uses
file contents, not the extension or browser MIME type. Limits are 5 MB per image and
20 MB combined, checked before and after conversion; multipart requests allow 21 MB
including overhead. Count and combined size are configurable through `giraffix.ai.max-images`
and `giraffix.ai.max-total-image-size-bytes`. Decoded images are limited to 8000 pixels
per side and 32 megapixels.
HEIC/HEIF, AVIF, SVG and camera RAW need additional decoder support or prior conversion;
the application does not claim universal codec support. See
[Claude's supported formats](https://platform.claude.com/docs/en/build-with-claude/vision).
Analysis sends the prepared image to Claude and returns editable
name, category, description, keyFeatures, targetAudience, and imageUrl without
saving a product. All six keys are required, their values are strings or null, and extra
keys are rejected. The schema is sent using
[Claude's structured JSON output format](https://platform.claude.com/docs/en/build-with-claude/structured-outputs).
The field names match the product create/update DTO. Review suggestions and fill in
any missing required name/category before saving. The prompt returns null for imageUrl
because analysis does not persist images. Retain the selected files in the client and
reattach them when saving the reviewed product. IDs, ownership, and timestamps are backend-managed.

## Save product images

`POST /api/v1/brands/{brandId}/products` accepts either the existing JSON request or a
multipart request containing:

- `product`: the reviewed ProductRequest JSON, with part Content-Type `application/json`.
- `images`: one to five file parts, using the same files selected for analysis.

For example, save the reviewed JSON to product.json, then run with a logged-in session
cookie and the current CSRF token:

```sh
curl -X POST http://localhost:8080/api/v1/brands/BRAND_ID/products \
  -b cookies.txt -H "X-XSRF-TOKEN: YOUR_CSRF_TOKEN" \
  -F "product=@product.json;type=application/json" \
  -F "images=@front.jpg" -F "images=@back.jpg"
```

The flow is: select images -> analyze -> review/edit the generated details -> confirm.
Keep the selected files in the frontend while the user reviews the suggestions. Analysis
does not write a product, a database image row, or an S3 object. On confirmation, send
the edited JSON and those same files to the multipart product-create endpoint above.

Confirmation uploads each prepared image to S3, then commits the edited product details
and each S3 URL in PostgreSQL. New `product_images` rows contain `storage_url` and the
existing filename/type/position/size/time metadata. Image bytes are not mapped or
saved by the application; only the returned S3 URL is persisted. Native
JPEG/PNG/GIF/WebP bytes are uploaded unchanged; other supported formats are normalized
to PNG as in analysis (first page only for multipage formats).

Product responses now include an ordered `images` list containing metadata and a
stored S3 `url`. S3 read access is governed by your bucket policy; uploading does not
make the bucket public. Expiring presigned URLs are not stored. The existing
product-level `imageUrl` property remains available for legacy external URLs.

To add images later, POST multipart `images` files to `/api/v1/products/{id}/images`.
The stored collection is limited to five images and 20 MB total (5 MB per file).
Text PATCH requests preserve image URLs. Deleting a product commits the product/image-row
removal and durable S3 cleanup records together. HTTP 204 confirms this database commit;
the background worker removes S3 photos afterward. Another user's product returns 404.
A failed database commit leaves the product and photos intact. S3 outages do not fail
an already-committed DELETE: cleanup retries automatically with backoff, including after
a backend restart. A missing S3 bucket configuration returns 503 before deletion for
products with stored images. Legacy external `imageUrl` values are never deleted.
Flyway V15 creates `product_image_deletions` on the next startup. Cleanup polls every
five seconds using its own scheduler, claims objects with a two-minute lease, and retries
failures from 30 seconds up to one hour apart. Successful cleanup removes its queue row.
See [product deletion](docs/product-deletion.md) for configuration and diagnostics.
With S3 versioning enabled, deletion creates delete markers; older versions remain
subject to the bucket's lifecycle policy.

Restart the backend to apply Flyway V3. It adds only `storage_url` and allows NULL
`image_data` so S3-only rows can be inserted. Existing database columns/data are left
untouched; there is no automatic conversion or legacy-download fallback. No additional
schema migration is needed for this simplification.

If an upload fails, the database save rolls back and best-effort compensation removes
that operation's uploaded objects. Upload compensation remains separate from the durable
product-deletion queue; process interruption during upload can still leave an orphan.

## S3 setup

Configure S3 directly in `src/main/resources/application.properties`:

```properties
giraffix.storage.s3.bucket=replic-media-prod
giraffix.storage.s3.region=us-east-1
giraffix.storage.s3.access-key-id=
giraffix.storage.s3.secret-access-key=
giraffix.storage.s3.session-token=
```

Fill in both credential values and the actual bucket region, then restart the backend.
The session token is needed only for temporary credentials. Incomplete credentials
are rejected at startup. When both keys and the token are blank, the SDK default
credential chain remains available. Keep the populated local file out of source control.
Grant the backend `s3:PutObject` for
`arn:aws:s3:::YOUR_BUCKET/products/*` and `s3:DeleteObject` on the same prefix
(plus KMS permissions if needed). Configure read
access separately for your image consumers. No bucket is created or made public by
the service. An empty bucket property makes image-saving requests return 503 while analysis
remains available. OAuth and database credentials are unchanged.

The SDK's S3 utility constructs the permanent object URL after upload; S3's PUT response
does not itself provide a public download URL. See [AWS S3 Java examples](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_s3_code_examples.html).

## Image analysis logs

Analysis logs appear in the console and `logs/giraffix.log` (rolling files: 10 MB,
7 days, 100 MB total). Each API request receives an `X-Request-ID` response header;
search for the same `analysisId` or `[requestId:...]` in the logs to follow it end to end.
Request IDs also cover product confirmation, image attachments and product deletion.

Stages include HTTP receipt/completion (including authentication/CSRF failures),
upload validation, prompt/schema loading, model API call start, actual upstream HTTP
status, provider request ID, model message ID, token usage, parsing, validation, and
total duration. `stage=all_fields_null source=model_response` confirms that a valid
model response contained no extracted details.

Storage stages include `product_confirmation_received`, `s3_upload_started`,
`s3_upload_completed` (with provider request ID), and `product_metadata_flushed`.
The final HTTP 201 response confirms a successful product creation. `s3_upload_failed`
includes the provider HTTP status. `analysis_suggestions_returned persistence=none`
confirms that analysis did not save anything.

Diagnostics default to INFO. Set `GIRAFFIX_LOG_LEVEL=DEBUG` to enable resource,
field-size and request diagnostics; extracted field values are not logged.
Successful GET/poll completion logs are DEBUG. API keys, cookies, CSRF tokens, image bytes/base64, request
bodies, and raw provider error bodies are not logged.

## Concept reference library

`POST /api/v1/concepts` accepts one multipart file named `image` (up to 5 MB).
It validates/prepares the image, calls the configured Claude model once, validates
the concept result, uploads the prepared image to S3, then saves the record in
the new `concepts` table. Restart the backend to apply Flyway V4.

The result follows `schemas/concept-extraction.json`: `usable`, `unusable_reason`,
and the nullable `concept` object with the supplied names, mechanism, idea,
preserve/adapt lists, product forms, transfer examples, and scores. The API adds
`id`, `image_url`, and `created_at` as persistence metadata. Unusable references
are also saved with their image and rejection reason. The full validated result
is stored in `analysis_json` (TEXT containing JSON); S3 bucket/key/URL, image
metadata, usable flag, schema version, creator identity subject, and timestamp
are stored in separate columns. Image bytes are never saved in PostgreSQL.

Use `GET /api/v1/concepts/{id}` to retrieve a record, or
`GET /api/v1/concepts?page=0&size=20` to browse newest first (size 1-100).
In Swagger, select the concept POST endpoint and attach the file in `image`.
The existing login and CSRF requirements apply. Admin-role enforcement is
intentionally deferred; currently any authenticated user can create/read concepts.

Concept extraction uses `giraffix.concept.model=claude-sonnet-5-5`, independently of
the product analysis model, with the existing `giraffix.ai` connection credentials.
Its separate `giraffix.concept.max-tokens` and `max-image-size-bytes` properties
are in application.properties. The concept S3 adapter uses the configured bucket
and credentials, under `concept/{id}/`. Extend the backend IAM permissions to
include `s3:PutObject` and `s3:DeleteObject` for `concept/*`, as well as the existing
product prefix. Configure image read access separately; stored URLs do not make
private bucket objects public.

Model failures or invalid output return 502 before upload or database persistence.
S3 failures return 502 without a database row. The provider schema omits unsupported
numeric/array bounds; the backend validates the complete local schema, counts,
score ranges, usable/null relationships, disjoint forms, and distinct transfer forms
before saving. Product independence and exclusion of the source form are model
instructions because the result does not include a separate source-form field.

Database save/commit failure triggers best-effort deletion of the new S3 object
and returns 500. If cleanup also fails, `concept_image_cleanup_failed` logs the
bucket/key for manual recovery; there is no background retry worker. A failed PUT
also attempts cleanup because a timed-out upload may have succeeded remotely.

The `concept` module owns its controller, service, repository, entity, prompt, and
schema. `ConceptAnalyzer` and `ConceptImageStorage` are replaceable interfaces with
Claude/S3 adapters. Its table has no foreign keys or JPA relationships to users,
brands, or products. Only the creator's identity subject crosses that boundary;
image normalization is a shared utility. External model/storage calls run outside
the short database transaction. These boundaries keep later service extraction
contained without introducing network calls between local modules now.

## Tests

Run `.\mvnw.cmd test` or `./mvnw test`. Tests use an isolated H2 database in PostgreSQL
compatibility mode, a dummy API key, and a mocked S3 client for storage tests. No real
OAuth login, AI request, AWS credentials or S3 bucket is needed for tests.
Tests cover migrations plus Hibernate validation, onboarding, product lifecycle,
ownership isolation, HTTP create/PATCH validation, and mocked image-analysis schema
requests/responses (including nullable fields and invalid payloads). Verify migrations against a
disposable PostgreSQL database before deployment; H2 is a compatibility check.
Storage tests cover multipart validation, S3 uploads, persisted URLs, user edits,
appending/conversion, ownership, image-count limits, and database rollback on upload failure.

The AI service uses Jackson 3, matching Spring Boot 4.
See [Spring Boot JSON](https://docs.spring.io/spring-boot/reference/features/json.html)
and [database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
