# NASP: Local HTTP Rate Limiter

An in-memory HTTP rate limiter for a Java 17 / Spring Boot 3 application. A
servlet filter checks every API request against a per-client quota before the
request reaches a controller, and rejects clients that exceed it with
`429 Too Many Requests`.

It contains two algorithms behind one interface, chosen at startup:

- **Token bucket** (default): lets bursts through at once and bounds the
  long-run rate. Lock-free: one `AtomicLong` per client.
- **Leaky bucket**: a queue that delays bursts and releases each client's
  requests at a steady rate. Also lock-free: one `AtomicLong` per client, plus a
  timer for each waiting request.

## Contents

- [Quick start](#quick-start)
- [Architecture](#architecture)
- [Algorithms](#algorithms)
- [Configuration](#configuration)
- [HTTP behavior](#http-behavior)
- [Client identification](#client-identification)
- [Usage examples](#usage-examples)
- [Observability page](#observability-page)
- [Testing](#testing)
- [Limitations](#limitations)

## Quick start

Requirements: JDK 17 or newer, and internet access for the first Maven wrapper
run. On Windows, use `mvnw.cmd` instead of `./mvnw`.

```sh
./mvnw clean verify      # build and run all tests
./mvnw spring-boot:run   # start on http://localhost:8080
curl -i http://localhost:8080/api/hello
```

A request within quota returns `200 OK` with body `ok` and the current quota in
`RateLimit` headers.

## Architecture

```text
HTTP request
  -> servlet container
  -> CorsFilter               (only for /api/*, only if origins are configured)
  -> RateLimitFilter          (only for /api/*)
       ClientKeyResolver  -> client key
       RateLimiter        -> Decision
       allowed, ready:    add RateLimit headers, continue
       allowed, waiting:  add RateLimit headers, suspend (servlet async), continue when ready
       rejected:          add RateLimit headers, write 429 problem response, stop
  -> DispatcherServlet
  -> DemoController           (GET /api/hello)
```

The filter depends only on the `RateLimiter` interface, and the algorithms know
nothing about HTTP.

```text
src/main/java/ba/etf/nasp
  Application.java
  api/DemoController.java          GET /api/hello, the protected endpoint
  ratelimit/
    RateLimiter.java               the interface and its Decision record
    TokenBucketLimiter.java        token bucket
    LeakyBucketLimiter.java        leaky bucket (queue)
    RateLimitFilter.java           servlet filter: decision → headers, 429, or waiting
    ClientKeyResolver.java         who is asking: principal, API key, or IP address
    RateLimitProperties.java       all rate-limit.* settings
    RateLimitConfig.java           Spring wiring: limiter, filter, CORS
observability/
  index.html                       live dashboard (see Observability page)
```

### The interface

```java
public interface RateLimiter {
    Decision tryAcquire(String client);

    record Decision(boolean allowed, long remaining, long retryAfterSeconds,
                    CompletableFuture<Void> ready) { ... }
}
```

Every request costs one token. The client key must be nonblank. The fields of
a `Decision`:

- `remaining`: how many more requests the client could make right now.
- `retryAfterSeconds`: set for rejections, rounded **up** to whole seconds, so
  clients never get an optimistic answer.
- `ready`: completes when an allowed request may proceed. For the token bucket
  it is already complete; for the leaky bucket it completes when the request
  leaves the queue.

Both algorithms read time from `System.nanoTime()`, which is monotonic, so
wall-clock changes such as NTP corrections cannot create or destroy tokens. The
clock is injectable in tests.

## Algorithms

Both algorithms share one configuration: a bucket of `capacity` that refills
(token bucket) or drains (leaky bucket) at `capacity / window` per second. With
the defaults that is 100 per 60 seconds, about 1.67 per second. They differ in
what happens to a burst:

| A burst of 30 (capacity 20, 2 per second) | Token bucket | Leaky bucket |
|---|---|---|
| Admitted | 20, all at once | 20: one now, then one every 0.5 s |
| Rejected | 10 | 10 |
| Longest wait | none | 9.5 s |

The token bucket *polices* traffic: each request is allowed or rejected on the
spot. The leaky bucket *shapes* it: admitted requests wait their turn, so
whatever the arrival pattern, the endpoint sees a steady stream.

### Token bucket

`TokenBucketLimiter` keeps buckets in a `ConcurrentHashMap<String, AtomicLong>`.
Each client's entire state is a single `long`: the monotonic time, in
nanoseconds, at which its bucket would have been empty.

- Available tokens = `(now - emptyAt) / nanosPerToken`, capped at capacity
  (an `emptyAt` older than one full refill means a full bucket).
- Spending a token moves `emptyAt` forward by `nanosPerToken`.
- `nanosPerToken = ceil(window / capacity)` in nanoseconds, computed once at
  startup. All later arithmetic is exact integer math, so a full bucket holds
  exactly `capacity` tokens, and each token arrives less than 1 ns late.

Updates use a compare-and-set loop:

```text
loop:
  emptyAt  = state.get()
  base     = max(emptyAt, now - fullBucketNanos)
  if now - base < nanosPerToken:                     reject (state is not written)
  if state.compareAndSet(emptyAt, base + nanosPerToken): allow
  else: another request won the race; loop
```

Two requests can never spend the same token, because only one of two competing
CAS operations can succeed. No thread holds a lock, and a failed CAS always
means another thread's update succeeded, so the limiter is lock-free: the
system as a whole always makes progress. Rejections never write, so a client
that is already over quota adds no contention.

Storing one timestamp instead of a `(tokens, lastRefill)` pair lets a single
atomic word hold the whole bucket and keeps nanosecond precision. The encoding
is the same as the theoretical arrival time of the Generic Cell Rate Algorithm
(GCRA).

A leaky bucket used as a *meter* (a level that rises with each request and
drains at the rate) would make exactly the same decisions, since its level is
`capacity - tokens`. The leaky bucket here is the *queue* form, which also
changes *when* admitted requests proceed.

### Leaky bucket

`LeakyBucketLimiter` releases one request per client every
`window / capacity`. Because that rate is fixed, a request's release time is
known the moment it arrives: one interval after the request ahead of it. So no
queue is stored. Like the token bucket, each client's bucket is one
`AtomicLong` in a `ConcurrentHashMap<String, AtomicLong>`: `drainedAt`, the
time at which everything admitted so far will have leaked out.

```
releaseAt = max(now, drainedAt)            // wait behind the requests ahead
wait      = releaseAt - now
wait > (capacity - 1) * interval  → reject  // capacity requests already occupy it
otherwise CAS drainedAt → releaseAt + interval
wait == 0 → proceed now; else a timer completes Decision.ready after `wait`
```

- Each request occupies the bucket for one interval after its release, so a
  burst admits exactly `capacity` requests, as many as the token bucket: one
  proceeds at once and the rest leave one interval apart, in arrival order.
- A request that finds the bucket full is rejected with `Retry-After`: the time
  until the next leak frees a place. Rejections do not write the state.
- Updates use compare-and-set, as in the token bucket, so the leaky bucket is
  lock-free too.

**Where the queue went.** The order of waiting requests is kept by the timers:
the scheduler (`ScheduledThreadPoolExecutor`) holds them in a binary min-heap
ordered by release time, and one daemon thread completes each request's future
when its time comes. That thread stops when the application shuts down.

**Waiting without threads.** `RateLimitFilter` suspends a waiting request with
servlet async processing (`request.startAsync()`). The servlet thread returns
to the pool, and when the future completes the request is dispatched to the
controller. Waiting requests hold a place in the queue and an open connection,
but no server thread; a test runs four waiting requests on a server with two
threads.

The `RateLimit` header's `r` is the number of free places in the bucket after
the request was queued.

## Configuration

Settings live in `src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `rate-limit.capacity-tokens` | `100` | Bucket size: the largest burst a client may send |
| `rate-limit.window-seconds` | `60` | Time to refill (or drain) a full bucket |
| `rate-limit.algorithm` | `token-bucket` | `token-bucket` or `leaky-bucket` |
| `rate-limit.trust-forwarded-for` | `false` | Identify clients by `X-Forwarded-For` (see [Client identification](#client-identification)) |
| `rate-limit.cors-allowed-origins` | empty (CORS off) | Browser origins allowed to call `/api/*` (see [CORS](#cors)) |

Capacity and window must be positive, and the algorithm must be one of the two
names; invalid values stop the application at startup.

Properties can be overridden without editing the file, using standard Spring
Boot mechanisms:

```sh
# Command-line arguments
./mvnw spring-boot:run -Dspring-boot.run.arguments="--rate-limit.algorithm=leaky-bucket"

# Environment variables (dashes removed, dots become underscores)
RATELIMIT_CAPACITYTOKENS=3 RATELIMIT_TRUSTFORWARDEDFOR=true ./mvnw spring-boot:run
```

The `observability` profile (`application-observability.properties`) sets a
20-request / 10-second quota, trusts `X-Forwarded-For`, and enables CORS for
local pages:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=observability
```

## HTTP behavior

`RateLimitFilter` runs for every request matching `/api/*`, before Spring MVC,
and charges one token per request. Unknown `/api/*` paths also consume quota,
so probing for endpoints is not free; paths outside `/api/*` are never limited.

Every limited response carries the quota, using the syntax of the IETF HTTPAPI
draft "RateLimit header fields for HTTP":

```http
RateLimit-Policy: "default";q=100;w=60
RateLimit: "default";r=99
```

| Parameter | Meaning |
|---|---|
| `q` | Quota: bucket capacity in tokens |
| `w` | Window: seconds to refill a full bucket |
| `r` | Remaining whole tokens after this request |
| `t` | Seconds until a request can succeed (only on rejections) |

When the bucket is exhausted, the filter answers itself and the controller is
never called. The body is an RFC 9457 problem details object:

```http
HTTP/1.1 429
RateLimit-Policy: "default";q=100;w=60
RateLimit: "default";r=0;t=1
Retry-After: 1
Cache-Control: no-store
Content-Type: application/problem+json

{"type":"https://iana.org/assignments/http-problem-types#quota-exceeded",
 "title":"Too Many Requests","status":429,
 "detail":"Request quota exceeded. Retry after 1 seconds.",
 "instance":"/api/hello","policy":"default"}
```

`Retry-After` tells well-behaved clients when to try again, and
`Cache-Control: no-store` stops caches from replaying the rejection.

### CORS

CORS is off by default. When `rate-limit.cors-allowed-origins` lists origins (Spring origin
patterns such as `http://localhost:[*]`, or `null` for pages opened from disk),
a `CorsFilter` serves `/api/*` for those origins:

- **Allowed:** method `GET`, and request headers `X-API-Key` and `X-Forwarded-For`.
- **Exposed to scripts:** `RateLimit`, `RateLimit-Policy` and `Retry-After`. Browsers
  hide non-standard response headers otherwise.
- **Preflight cache:** one hour, so browsers do not preflight every request.

The CORS filter runs **before** the rate limit filter. Preflight requests are
answered by the CORS filter and never consume tokens; requests from origins that
are not allowed get `403` without consuming tokens; and the `429` responses
written by the rate limit filter still carry CORS headers, so a page can read
them.

## Client identification

Each request is charged to one bucket. `ClientKeyResolver` picks the key from
the first source that is present and nonblank:

| Priority | Source | Key |
|---|---|---|
| 1 | Authenticated principal name | `principal:<sha256>` |
| 2 | `X-API-Key` header | `api-key:<sha256>` |
| 3 | `X-Forwarded-For`, only if `trust-forwarded-for=true` | `ip:<address>` |
| 4 | Connection's remote address | `ip:<address>` (or `ip:unknown`) |

Principal names and API keys are hashed with SHA-256, so raw credentials are
never held in limiter memory. The source prefix keeps the namespaces apart:
a principal can never collide with an IP address.

**Forwarded addresses.** `X-Forwarded-For` is ignored by default, because any
client can send it and would otherwise get a fresh bucket per request. Enable it
only when the application is reachable solely through one reverse proxy that
appends the real client address. The resolver then uses the **last** entry,
the one written by that proxy. Earlier entries come from the client and can be
forged, so they are never used. Chains of several proxies are not supported.

## Usage examples

Start the application with a small quota so limits are easy to hit:

```sh
RATELIMIT_CAPACITYTOKENS=3 RATELIMIT_TRUSTFORWARDEDFOR=true ./mvnw spring-boot:run
```

Inspect the quota headers:

```sh
curl -si http://localhost:8080/api/hello | grep -i '^RateLimit'
# RateLimit-Policy: "default";q=3;w=60
# RateLimit: "default";r=2
```

Exhaust the bucket. The fourth request from the same address is rejected:

```sh
for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code} ' http://localhost:8080/api/hello; done
# 200 200 200 429
```

See the full rejection (with capacity 3 and a 60 s window, one token takes 20 s):

```sh
curl -si http://localhost:8080/api/hello
# HTTP/1.1 429
# RateLimit: "default";r=0;t=20
# Retry-After: 20
# ...
```

An API key gets its own bucket, separate from the caller's address:

```sh
for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code} ' -H 'X-API-Key: demo-key' http://localhost:8080/api/hello; done
# 200 200 200 429
```

With forwarded-header trust enabled, the address appended by the proxy (the
last entry) is the identity, so forging the earlier entries does not help:

```sh
for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code} ' -H 'X-Forwarded-For: 203.0.113.7' http://localhost:8080/api/hello; done
# 200 200 200 429
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-For: 198.51.100.1, 203.0.113.7' http://localhost:8080/api/hello
# 429
```

## Observability page

`observability/index.html` is a live dashboard. It simulates up to eight
clients with different identities and traffic patterns (steady, random, burst,
ramp, flood, continuous spam, and a client that honors `Retry-After`). It
animates each request into its client's bucket and out to the endpoint, and
charts what passes, what waits and what is blocked. A toggle switches between a
token bucket application and a leaky bucket application. Open the file in a
browser after starting the applications; see
[`observability/README.md`](observability/README.md).

## Testing

```sh
./mvnw test
```

| Test class | Covers |
|---|---|
| `TokenBucketLimiterTests` | Full start and exhaustion, refill boundaries to the nanosecond, retry rounding, refill cap after idle periods, clock moving backward, fractional refill intervals, largest configuration without overflow, invalid input, and concurrency (one shared client, many clients, requests racing refills) |
| `LeakyBucketLimiterTests` | First request passes at once, a burst leaves one per interval in arrival order, full bucket rejects with time to next leak, drained bucket passes again, release times fixed at arrival, separate clients, concurrent requests cannot overfill the bucket, invalid input (deterministic clock and scheduler) |
| `LeakyBucketLimiterHttpTests` | Real server: a burst of 6 against capacity 4 gives 2 immediate 429s and 4 responses released about 250 ms apart, on a server with only two threads |
| `RateLimitFilterTests` | Quota headers, the 429 problem response, per-client and per-API-key isolation, ignored `X-Forwarded-For`, `/api/*` scope |
| `ClientKeyResolverTests` | Source priority, SHA-256 hashing, blank values, forwarded-header trust boundary and last-entry selection |
| `CorsTests` | Preflights consume no tokens, quota headers are exposed on 200 and 429, pages opened from disk are allowed, unknown origins are refused without consuming tokens, CORS is off by default |
| `RateLimitConfigTests` | Configuration binding and validation, algorithm selection |
| `DemoControllerTests`, `ApplicationTests` | Endpoint and application startup |

Time-dependent tests drive an injected clock, so they are deterministic and
never sleep. Concurrency tests release all threads together from a latch to
maximize contention. With a fixed clock the correct number of allowed requests
is known exactly, so the tests assert exact counts: any lost update or
double-spent token fails the test.

## Limitations

- **Single JVM.** State lives in process memory: it is not shared between
  application instances and resets on restart. Several replicas would need a
  shared store, such as Redis, with atomic updates.
- **No idle-client eviction.** A bucket is kept for every client key ever seen,
  so memory grows with the number of distinct clients.
- **API keys are not validated.** There is no key registry; a client sending a
  different made-up key on each request gets a fresh bucket each time. The
  API-key policy is only meaningful once keys are verified upstream.
- **One proxy hop.** Forwarded addresses assume exactly one trusted reverse proxy.
- **One policy.** Every `/api/*` request costs one token from the same quota;
  there are no per-endpoint limits or weights over HTTP.
- **Waiting requests hold connections.** The leaky bucket keeps each
  waiting request's connection open for up to one window. Waiting requests use
  no server threads, but every client can hold up to `capacity` open
  connections, and a queued request whose client disconnects still occupies its
  place until it leaks.
