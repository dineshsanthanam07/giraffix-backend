**Live ad progress**

`GET /api/v1/ads/{operationId}/events` opens a session-authenticated Server-Sent
Events (SSE) connection. Use the operation ID returned by `POST /api/v1/ads`, not
the product ID or saved creation ID. The existing GET status endpoint remains
available for ordinary reads and fallback polling.

SSE is a persistent HTTP response suited to this one-way progress feed. Browsers
can use native `EventSource`; there is no Socket.IO dependency or WebSocket
handshake. No extra AI calls or database migrations are introduced.

**Frontend example**

After submitting an ad, connect from the same origin with the existing login
session. The browser sends its session cookie automatically. This GET requires no
CSRF token; submitting/deleting ads still requires the existing token.

```javascript
function watchAd(operationId, onProgress, onReady, onStreamError) {
  const events = new EventSource(`/api/v1/ads/${operationId}/events`);

  events.addEventListener('progress', event => {
    const update = JSON.parse(event.data);
    onProgress(update.message, update.stage);

    if (update.terminal) events.close();
    if (update.stage === 'SUCCEEDED') {
      onReady(update.ad.imageUrl, update.ad.creationId);
    }
  });

  events.addEventListener('stream-error', event => {
    const issue = JSON.parse(event.data);
    if (!issue.retryable) events.close();
    onStreamError(issue.code, issue.retryable);
  });

  events.onerror = () => {
    // A transport interruption does not mean the ad failed.
    // Native EventSource reconnects while its state is CONNECTING.
    onStreamError('CONNECTION_INTERRUPTED', events.readyState !== EventSource.CLOSED);
  };

  // Call when the component unmounts or the user stops watching.
  return () => events.close();
}
```

Display `message` alongside a spinner or a stage indicator. There is no exact
percentage or remaining-time value because the renderer does not supply one here.
These messages describe backend stages; they do not expose private model
reasoning, prompt contents, reference photos, or provider response bodies.

Example frame:

```text
event:progress
retry:2000
data:{"stage":"GENERATING","message":"Generating your ad image","terminal":false,"ad":{"id":"OPERATION_UUID","status":"GENERATING","selectionStatus":"SELECTED","generationJobId":"JOB_UUID","creationId":null,"imageUrl":null,"aspectRatio":"4:5","quality":"high","fallbackUsed":false,"error":null},"observedAt":"2026-11-19T18:00:00Z"}
```

`ad` has the same shape as `GET /api/v1/ads/{operationId}`. On success it contains
the authorized final `imageUrl` and `creationId`. `observedAt` is the observation
time, not the database transition timestamp. Always close EventSource on
`terminal: true`; otherwise a browser may reconnect after the server completes it.

**Stages and terminal outcomes**

| Stage | Message/meaning |
| --- | --- |
| QUEUED | Your ad is queued |
| SCREENING / VERIFYING | Finding a concept for your product |
| SELECTED / PROMPTING | Preparing the creative direction |
| PROMPT_READY | Preparing to render your ad |
| WAITING_FOR_PROVIDER | Waiting for the image service |
| GENERATING | Generating your ad image |
| REVIEWING | Checking the generated ad |
| SUCCEEDED | Your ad is ready; terminal |
| STALE_CONTEXT | Source product changed or was deleted; terminal |
| RECONCILIATION_REQUIRED | Generation needs attention; terminal for this stream |
| DELETED | Saved ad was explicitly deleted; terminal |

Other existing failure/no-match outcomes are terminal too, including FAILED,
PROMPT_FAILED, OUTPUT_REJECTED, PROVIDER_FAILED, POOL_EXHAUSTED,
NO_MATCH_WITHIN_BUDGET, SELECTION_BUSY, and TEMPORARILY_SUPPRESSED. Use `ad.error`
when present for the existing bounded error details.

**Delivery and security**

The connection receives an immediate authorized snapshot, then changed snapshots.
The backend reads committed durable status at the configured interval and groups
subscribers by owner/operation, so multiple tabs share one periodic status query.
This is server-side polling feeding a live HTTP stream; it is not a notification
log or guaranteed delivery of every intermediate transition. A short stage can
finish between reads. Reconnection always obtains current state; Last-Event-ID is
not used to replay historical events.

Because state is read from the database, the generation worker can run on another
replica. Existing session requirements still apply: a replicated deployment needs
its intended shared-session or sticky-session configuration. No cross-origin CORS
policy is added; a separate-origin frontend needs that explicitly configured.

Unknown or foreign operations return 404 before streaming. A missing/invalid
session returns 401. Per-user/global connection limits return 429. Session identity
is checked again before updates, so logout/invalidation stops further snapshots.
An open stream uses no provider credentials and never mutates/cancels the ad job.

SSE comments keep idle connections alive. Connections rotate after two minutes;
EventSource reconnects with the current login session and gets a fresh snapshot.
Client disconnect, timeout, logout, completion, and shutdown release subscription
capacity. Stream readers use a separate bounded executor and do not run on image
generation workers.

A `stream-error` frame contains `{ "code": "...", "retryable": true|false }`.
`PROGRESS_UNAVAILABLE` means a status-read/transport problem; it does not mark the
ad failed and permits reconnect. `AUTHENTICATION_REQUIRED` and `AD_UNAVAILABLE`
stop that subscription. HTTP-level errors before connection establishment still
use normal HTTP statuses. If EventSource is permanently closed, use the status
endpoint once to resolve the condition or offer a reconnect action.

**Configuration and deployment**

| Property | Default | Purpose |
| --- | --- | --- |
| `giraffix.progress.worker-enabled` | `true` | Enable live subscriptions and background reads |
| `giraffix.progress.poll-ms` | `1000` | Backend scan interval, 250–30000 ms |
| `giraffix.progress.heartbeat-ms` | `15000` | Idle heartbeat interval |
| `giraffix.progress.connection-timeout-ms` | `120000` | Connection lifetime, 10000–1800000 ms |
| `giraffix.progress.max-connections` | `200` | Per-instance open stream cap |
| `giraffix.progress.max-connections-per-user` | `5` | Per-instance account cap |

Heartbeat must be at least one second and shorter than the connection lifetime.
These connection caps do not replace request-rate or spending controls. Four
bounded reader threads perform status reads and stream writes independently of
the durable pipeline.

Responses include `Cache-Control: no-store` and `X-Accel-Buffering: no`. Configure
the deployed proxy/load balancer for unbuffered streaming and an idle timeout
longer than the heartbeat interval. Verify streamed arrival through the actual
proxy; an ordinary buffered HTTP response can otherwise hide all progress until
completion. No proxy configuration was changed in this repository.

**Validation**

The full offline Maven suite passed with **291 tests, zero failures, zero errors,
and zero skipped**. Evidence: `target/ad-progress-full-tests.log`. Coverage includes
stage changes/final image data, all terminal outcomes, deduplication/heartbeat,
grouped tab reads, reconnect snapshots, timeout, logout, connection limits, safe
status-read errors, authenticated/foreign-user access, and the existing generation
and deletion flows. Tests use MockMvc, H2, local provider fixtures, and mocked S3.
Streaming through a deployed reverse proxy was not exercised.
