**Admin prompt editor**

After restarting the backend to apply Flyway V13 and V14, sign in and open `/admin/prompts` on the backend origin. This is a same-origin admin page served by Spring Boot; it needs no separate frontend build or external scripts.

Access uses the existing `giraffix.selection.admin-subjects` allowlist of Google OIDC subject IDs. An empty list grants no editing access. For example, supply `GIRAFFIX_SELECTION_ADMIN_SUBJECTS` through the server environment. Both the page and all editor APIs enforce this allowlist. Existing session authentication and CSRF protection apply to draft/publication requests.

The editor manages four reusable templates: product analysis, concept extraction, concept picking, and the creative director. It does not change model settings, output schemas, product facts, or individual generated image prompts. Existing bundled prompt text initializes revision 1 unchanged. Legacy weighted-fit selection and the output reviewer's inline instructions are outside this editor.

**Editing and publication**

1. Select a template and edit its instructions.
2. Save a draft. Draft saving does not change the active prompt or call an LLM.
3. Publish the saved version explicitly to use it for new work.
4. To roll back, load an earlier version from history and publish that version.

Revisions are immutable; saving creates a new version. Publications record the actor, previous/new versions, and time. Concurrent changes to the active version return 409 rather than silently overwriting an administrator's work. The editor preserves unsaved text on request failure and warns before discarding edits. History is paginated.

Prompts must be nonblank, contain at most 60,000 characters, and contain no NUL characters or unsupported double-brace placeholders. The concept-picker template must retain `{{MINIMUM_COMPATIBILITY_SCORE}}`; its configured value is substituted by the backend. Publishing is not an LLM quality test: response schemas and local validation still enforce the existing contracts, but an unsuitable instruction can cause generation failures or affect creative quality.

**Prompt database reads are driven by changes, not users or a timer**

Active templates are held in memory without a TTL. There is no five-second/minute version poll and no database prompt check on a normal generation request.

| Event | Cache/database behavior |
| --- | --- |
| Startup | Seed missing original revisions, load active templates; establish notification subscription and invalidate once to cover startup races |
| Normal generation with a cached version | Read prompt from memory; no prompt-content/version query |
| Save draft | Store an immutable revision; no active-cache invalidation and no LLM call |
| Publish on this instance | Commit the active pointer and audit record; update local memory directly from the committed result, with no cache re-read |
| Publish on another instance | PostgreSQL sends a commit notification; the recipient marks its cache stale, then performs one batched refresh on next use |
| Several publications before next use | Coalesce into one refresh of active templates |
| Listener reconnect | Invalidate after LISTEN is registered, repairing potentially missed publications with one subsequent refresh |
| Uncached historical version | One immutable-version query, then reuse a bounded LRU cache (256 revisions); active versions also remain available in the active map |

PostgreSQL's trigger sends a small schema/key/version notification after commit; prompt contents are not sent through notifications. The listener waits on the driver's socket using `getNotifications(timeout)`. The timeout is a local wait, not a SQL query or table poll. Self-notifications for the already-applied version are ignored. Notifications from other schemas are ignored.

This uses one listening connection per backend instance, taken from the configured datasource, plus a daemon listener thread. Reserve pool capacity for it in addition to request/worker traffic. It requires a direct PostgreSQL connection or session pooling; transaction-pooling connections cannot maintain LISTEN subscriptions. No Redis service is required.

`giraffix.prompts.cluster-notifications` defaults to `true`; non-PostgreSQL test databases skip the listener. Disable it only for a single-instance deployment where all publication goes through this application. With it disabled, out-of-process edits and other replicas cannot invalidate that instance's cache. During notification transport outages, an instance can temporarily serve its last known version until the connection recovers; notifications are not a globally synchronous cache-consistency protocol. Reconnection uses bounded backoff and forces a refresh.

References: [PostgreSQL LISTEN](https://www.postgresql.org/docs/current/sql-listen.html), [transactional NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html), [pgJDBC notification API](https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/PGConnection.html).

**Consistency with durable jobs**

Selection submissions pin the creative-director revision in internal database metadata. Picker revisions are pinned in the existing selection-policy rubric identity, so changing a picker prompt also changes the cache/attempt identity. Generation copies the pinned director revision into its job and uses it even if an administrator publishes a newer template while selection is still running. Previously queued requests and jobs default to original revision 1.

Product/concept extraction captures the current template for its synchronous request. Completed generated prompts and output reviews continue to be reused as before. No extra LLM calls are introduced by editing, publishing, viewing history, or invalidating caches. Prompt-version metadata is internal; the public ad request/status JSON and provider response schemas remain unchanged. Generated prompt provenance uses `creative-director-v2@N` for edited template versions.

**Editor APIs**

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/v1/admin/prompts` | List supported templates and active versions |
| GET | `/api/v1/admin/prompts/{key}` | Get active revision and content |
| GET | `/api/v1/admin/prompts/{key}/versions?page=0&size=20` | Revision metadata, bounded to 50 per page |
| GET | `/api/v1/admin/prompts/{key}/versions/{version}` | Get immutable revision content |
| POST | `/api/v1/admin/prompts/{key}/versions` | Save `{ "content": "...", "expectedActiveVersion": 1 }` as a draft |
| POST | `/api/v1/admin/prompts/{key}/publish` | Activate `{ "version": 2, "expectedActiveVersion": 1 }` |
| GET | `/api/v1/admin/prompts/{key}/publications` | Paginated publication/rollback audit |

Keys are `product-analysis`, `concept-analysis`, `concept-picker`, and `ad-creative-director`.

**Validation**

The full local suite passed with 248 tests and no failures/errors. A subsequent focused run passed all nine admin/cache/listener tests after the final active-version cache improvement. Tests cover admin/session/CSRF enforcement, draft isolation, publication/rollback audit, conflict handling, prompt-version pinning, all four provider integrations, 10,000 current-template lookups without DB reads, notification coalescing, and reconnect invalidation. JavaScript syntax was also checked. Provider HTTP is local/mocked; the PostgreSQL listener transport is mocked and the PostgreSQL-only V14 trigger was not exercised against a live database in this run. Evidence: `target/admin-prompts-event-cache-tests.log` and `target/admin-prompts-cache-final.log`.
