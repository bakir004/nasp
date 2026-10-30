# Observability page

A live dashboard for the rate limiter. It simulates up to eight clients calling
`GET /api/hello`, each with its own identity and traffic pattern, and shows what
the filter lets through and what it blocks. Everything the page shows comes from
the HTTP responses: the `200`/`429` status and the `RateLimit` headers.

## Running it

**1. Start two applications** with the `observability` profile, in the `NASP`
folder, one per algorithm (two terminals):

```sh
# Token bucket on port 8080
./mvnw spring-boot:run -Dspring-boot.run.profiles=observability

# Leaky bucket (queue) on port 8081
./mvnw spring-boot:run -Dspring-boot.run.profiles=observability \
    -Dspring-boot.run.arguments="--server.port=8081 --rate-limit.algorithm=leaky-bucket-queue"
```

Each application runs one algorithm, chosen at startup by `rate-limit.algorithm`
(`token-bucket` by default; see the main README for the others). If you only
want one algorithm, start only that application.

The profile (`src/main/resources/application-observability.properties`) sets:

| Setting | Value | Why |
|---|---|---|
| `rate-limit.capacity-tokens` / `window-seconds` | 20 / 10 | A 2 tokens/s refill, so limits are hit within seconds |
| `rate-limit.trust-forwarded-for` | `true` | Each simulated IP address gets its own bucket |
| `cors.allowed-origins` | `null`, `http://localhost:[*]`, `http://127.0.0.1:[*]` | Lets the page call the API from a file or a local web server |

**2. Open `observability/index.html`** in a browser (double-click it), and press
**Start all**. No build step or server is needed.

The **Token bucket / Leaky bucket** toggle switches which application the
clients call: `http://localhost:8080` and `http://localhost:8081` by default.
The **API** field in the header edits the address of the selected algorithm; the
page remembers both. Query parameters `?token=…`, `?leaky=…` and `?algo=leaky`
override them.

Without the profile, the application has CORS disabled and the page reports it
as unreachable. If IP addresses are not trusted, every IP client shares one
bucket and the page shows a warning; API-key clients work either way.

### How the page reaches the API

The page and the application are different origins, so the browser applies
CORS. The application allows the configured origins, lets them send
`X-API-Key` and `X-Forwarded-For`, and exposes `RateLimit`, `RateLimit-Policy`
and `Retry-After` to scripts. The CORS filter runs before the rate limit filter:
preflight requests are answered without consuming tokens, and the 429
responses written by the rate limit filter still carry CORS headers, so the
page can read them.

## Token bucket vs leaky bucket

Both applications use the same capacity (20) and rate (2 per second), but they
treat a burst differently:

| | Token bucket (`:8080`) | Leaky bucket queue (`:8081`) |
|---|---|---|
| A burst of 30 | 20 pass **at once**, 10 rejected | 1 passes, 19 **wait** and leave one every 0.5 s, 10 rejected |
| What reaches `/api/hello` | Spikes | A steady 2 per second per client |
| Client bucket in the flow view | Tokens left, drained by requests | Requests waiting, drained by the leak |
| Spammer (flood, 20 req/s) | About 18 of every 20 rejected at once | Bucket full at 20 waiting, overflow spilled, 2 per second leak through |
| Wait tile | About 1 ms | Seconds while buckets are full |

In the flow view every request drops into its client's bucket. With the queue,
waiting requests stay inside (the bucket fills) and leak out of the spout one at
a time toward the endpoint; a request that finds the bucket full spills over the
rim as a red ✕. With the token bucket, requests pass straight through while
tokens last.

Switching marks the moment on every time chart. The two applications keep
separate buckets, so each client's bucket view starts over after a switch.

## What the page shows

| View | What it shows |
|---|---|
| Status bar | Connection, the policy read from `RateLimit-Policy`, whether IP clients get separate buckets, and the selected application's address |
| Totals | Requests sent, passed (200), blocked (429), block rate, throughput, and wait (median and p95 time from sending to a 200) over the last 5 s |
| Request flow | Every request animated from the moment it is sent: into its client's bucket, out of the spout to the endpoint (200) or over the rim (429). Each bucket shows tokens left or requests waiting. Shown beside the client controls on wide screens |
| Throughput per second | Passed vs blocked requests per second for the last 60 s |
| Tokens remaining / Requests waiting | Token bucket: tokens left from the `RateLimit` header, following the refill rate between responses. Leaky bucket queue: requests waiting in each client's bucket, sampled five times per second |
| Request timeline | One lane per client for the last 30 s: ticks above the lane passed, ticks below were blocked |
| Clients | One compact row per client: name, traffic mode and its parameters, start/stop, counts, and a live bucket gauge. ⋯ opens the identity settings |
| Request log | The latest 150 requests with result, remaining tokens, `Retry-After`, and latency |

Hover over the charts for exact values.

## Traffic modes

| Mode | Behavior | Parameters |
|---|---|---|
| Steady | One request at a fixed interval | req/s |
| Random (Poisson) | Random gaps with the same average rate, like real users | req/s |
| Burst | Many requests at once, then quiet | size, every s |
| Ramp | Rate rises linearly, then restarts | from, to, period |
| Flood | A fixed high rate without waiting for answers, like an abusive client | req/s |
| Continuous spam | Each parallel worker sends again as soon as its answer arrives | parallel workers (1 to 24) |
| Polite | Steady, but waits `Retry-After` seconds after every 429 | req/s |

Each client is identified by an **IP address** (sent as `X-Forwarded-For`, from
the documentation ranges `192.0.2.0/24`, `198.51.100.0/24` and `203.0.113.0/24`)
or an **API key** (sent as `X-API-Key`). Both can be edited.

The page opens with five clients that show the main behaviors. With the
suggested 20 tokens per 10 s:

- **Alice:** 1 req/s, under the 2 tokens/s refill, so she is never blocked.
- **Spammer:** floods at 20 req/s. The token bucket rejects about 18 of every 20;
  the queue fills his bucket to 20 waiting and spills the rest.
- **Burst bot:** sends 30 at once against a capacity of 20, so 20 pass and 10 are blocked.
- **Polite client:** sends 5 req/s, hits the limit, and backs off for `Retry-After`.
- **Mobile app:** sends at random at 2 req/s, exactly the refill rate, so it is
  occasionally blocked after unlucky clusters.

## Notes

- **Buckets live in the application.** *Clear data* resets only the page; a
  drained bucket refills at its own pace. *New identity* gives a client a fresh
  bucket immediately.
- **Browser connection limit.** Browsers open at most six connections to one
  host, and a queued request keeps its connection open while it waits. The page
  therefore spreads each client's requests over eight host names
  (`c1a.localhost` … `c1h.localhost`), which browsers resolve to this machine,
  allowing up to 48 open requests per client: enough for a full bucket of 20
  plus room for overflow requests to reach the server and be rejected. The
  header shows which applies. If the browser cannot reach `*.localhost`, the
  page falls back to one host name shared by all clients, with at most six open
  requests.
- **Closed-loop clients cannot overfill a queue.** *Continuous spam* waits for
  each answer before sending again, so with the queue it never has more than
  *parallel* requests waiting. Use *Flood* to overflow a bucket.
- **Background tabs.** Browsers slow timers in background tabs, so traffic
  generation pauses while the tab is hidden.
- **`#autostart`.** Adding `#autostart` to the page address starts all clients
  on load.
