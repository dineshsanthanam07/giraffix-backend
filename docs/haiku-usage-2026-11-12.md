# Haiku usage audit: 12 November 2026

Reviewed on 16 November 2026. This is a read-only audit of retained application and verification logs, not an account billing reconciliation. No provider requests or application database changes were made.

## Confirmed application records

The retained logs record **two successful `POST /api/v1/analyze` requests** on 12 November, using `claude-haiku-5-5` against `https://api.anthropic.com/v1/messages`.

| Time (IST, UTC+05:30) | Time (UTC) | Images | Recorded input tokens | Recorded output tokens |
| --- | --- | --- | --- | --- |
| 2026-11-12 21:35:15.833 | 2026-11-12 16:05:15.833 | 3 | 6,869 | 309 |
| 2026-11-12 21:46:27.593 | 2026-11-12 16:16:27.593 | 3 | 6,869 | 292 |
| Total | | 6 image submissions | **13,738** | **601** |

Each request submitted 585,103 prepared image bytes in total and completed with HTTP 200 and provider stop reason `end_turn`. Matching image counts and byte totals do not prove identical images because content hashes were not logged.

The two responses have different application analysis IDs and provider message IDs, and each has its own incoming HTTP request. The logs therefore show two separate application invocations. There are exactly two logged Haiku API-call starts on that local date.

Evidence in `logs/giraffix.log.2026-11-12.0.gz`, using line numbers after decompression:

| Response line | Analysis ID | Provider message ID |
| --- | --- | --- |
| 2054 | `bce90bb8-eccb-4c4d-bb4c-4d77c35fb2c6` | `msg_011CgyKTcS6ozW9gKkv7Na2E` |
| 2617 | `c0885cb1-3df9-4fdf-a6e8-9b95d8224fd0` | `msg_011CgyLK8KZJMFDDWNg3bQLs` |

The corresponding call-start records are at lines 2052 and 2615.

One concept-extraction call is also logged on 12 November at 22:30:23 IST, using **Opus**, not Haiku. It does not explain Haiku-specific usage.

## Comparison with the reported 31,000

If the dashboard figure is exactly 31,000 input tokens for the same date, model, and scope, **17,262 input tokens are not explained by the input-token fields in these retained application records**. The dashboard figure has not been independently accessed or verified.

For reference, the 11 November archive records another three Haiku responses totaling 8,783 input tokens and 603 output tokens. Across the five identified Haiku responses on 11–12 November, the recorded totals are **22,521 input tokens** and **1,204 output tokens**. These are separate dates in both IST and UTC, so moving the five recorded calls between those time zones does not account for the 31,000 figure.

The archives also contain mocked test activity. Those entries must not be counted as real Claude requests merely because they contain an analysis stage. Product-analysis tests use `MockRestServiceServer` and a test endpoint. This audit identified live-looking responses through the Haiku model, Anthropic endpoint, provider message IDs, correlated HTTP requests, and nonzero usage. No additional named Haiku response records were found in top-level `target/*.log` verification output.

The application logs only `usage.input_tokens` and `usage.output_tokens` for product analysis. It does not preserve the complete provider usage object or separately record cache-related token counters. These logs are therefore not a complete account billing ledger. Possible reasons for a discrepancy include a different date/filter scope, usage from another client/workspace/key or the Console playground, missing historical log coverage, or different token-category aggregation. None of those explanations has been established for this account.

## How to finish reconciliation

Obtain the account's usage export or an authorized usage report for 12 November, grouping/filtering by model, API key, workspace, and hourly buckets. Compare the request counts, noncached input, cache-related input categories, and the exact UTC range with these records. For a UTC day use `[2026-11-12T00:00:00Z, 2026-11-13T00:00:00Z)`; for the IST calendar day use `[2026-11-11T18:30:00Z, 2026-11-12T18:30:00Z)`.

Anthropic's [Usage and Cost Analytics API](https://platform.claude.com/docs/en/manage-claude/usage-cost-analytics-api) supports model/API-key/workspace grouping and time buckets. Claude Console organizations require an Admin API credential for that API. This session has no connected billing account or Admin API credential, and the configured inference key was not used to attempt billing access.

Images contribute input tokens in addition to prompt text; see Anthropic's [vision documentation](https://platform.claude.com/docs/en/build-with-claude/vision). The 6,869-token values are provider-reported totals, not estimates derived from image byte sizes.

Machine-readable evidence: [haiku-usage-audit.json](../target/haiku-usage-audit.json). Reproducible local extraction script: [audit_haiku_usage.py](../target/audit_haiku_usage.py). Both are ignored local artifacts and may be removed by a clean build.
