# NASP: Local HTTP Rate Limiter

An in-memory HTTP rate limiter for a Java 17 / Spring Boot 3 application. A
servlet filter checks every API request against a per-client quota before the
request reaches a controller, and rejects clients that exceed it with
`429 Too Many Requests`.

The project contains three interchangeable limiter implementations behind one
interface, so their correctness and concurrency strategies can be compared:

- **Atomic token bucket**: lock-free, one `AtomicLong` per client (the default)
- **Synchronized token bucket**: one lock around all buckets (baseline)
- **Leaky bucket meter**: lock-free, an immutable state record per client swapped with `AtomicReference` (`rate-limit.algorithm=leaky-bucket`)

It also contains a **leaky bucket queue**, a traffic shaper that delays bursts
instead of rejecting them. Each client's waiting requests sit in a ring buffer
and leak out at a steady rate (`rate-limit.algorithm=leaky-bucket-queue`).

## Contents

- [Quick start](#quick-start)
- [Architecture](#architecture)
- [Algorithms](#algorithms)
- [Configuration](#configuration)
- [HTTP behavior](#http-behavior)
- [Client identification](#client-identification)
- [Metrics](#metrics)
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
       RequestAdmitter    -> RateLimitDecision + when the request may proceed
       allowed now:   add RateLimit headers, continue
       allowed later: add RateLimit headers, suspend (servlet async), continue on release
       rejected:      add RateLimit headers, write 429 problem response, stop
  -> DispatcherServlet
  -> DemoController           (GET /api/hello)
```

The HTTP layer depends only on the `RequestAdmitter` interface; the algorithms
know nothing about HTTP. A policing `RateLimiter` is adapted with
`RequestAdmitter.Immediate`, which admits or rejects at once. That keeps each part testable on its own and lets the
algorithm be replaced in one place (`RateLimitConfig`).

| Component | Responsibility |
|---|---|
| `RateLimitFilter` | Servlet filter on `/api/*`: admits each request, writes headers and 429 responses, suspends queued requests until release |
| `RequestAdmitter` | Admission contract: `Admission admit(String key)`, a decision plus a `CompletionStage` completed when the request may proceed |
| `ClientKeyResolver` | Chooses the client key: principal, API key, trusted forwarded address, or remote address |
| `RateLimiter` | Algorithm contract: `RateLimitDecision tryAcquire(String key, long costTokens)` |
| `RateLimitDecision` | Immutable result: allowed, remaining tokens, capacity, window, retry delay |
| `AtomicTokenBucketLimiter` | Lock-free token bucket; the default limiter |
| `SynchronizedTokenBucketLimiter` | Lock-based token bucket baseline |
| `LeakyBucketLimiter` | Lock-free leaky bucket meter |
| `LeakyBucketQueue` | Leaky bucket queue (traffic shaper): per-client ring buffer drained at the policy rate |
| `RingBuffer` | Bounded FIFO queue on a fixed circular array |
| `RateLimitMetrics` | Allowed, rejected and CAS-retry counters |
| `RateLimitProperties` | Validated `rate-limit.*` configuration |
| `RateLimitConfig` | Spring wiring: properties, metrics, limiter, resolver, filter registration |
| `CorsConfig`, `CorsProperties` | Optional CORS filter for browser pages on other origins |

```text
src/main/java/ba/etf/nasp
  Application.java
  api/DemoController.java
  ratelimit/
    RateLimitFilter.java, ClientKeyResolver.java
    RateLimiter.java, RateLimitDecision.java
    AtomicTokenBucketLimiter.java, SynchronizedTokenBucketLimiter.java, LeakyBucketLimiter.java
    RequestAdmitter.java, LeakyBucketQueue.java, RingBuffer.java
    RateLimitMetrics.java, RateLimitProperties.java, RateLimitConfig.java
    CorsConfig.java, CorsProperties.java
observability/
  index.html                  live dashboard (see Observability page)
```

### The limiter contract

`tryAcquire(key, costTokens)` tries to spend `costTokens` from the bucket of
`key`. Keys must be nonblank, and costs must be positive and at most the
capacity; anything else throws `IllegalArgumentException` without touching any
bucket. Rejected requests never consume tokens.

In every `RateLimitDecision`, remaining tokens are rounded **down** and the retry
delay is rounded **up** to whole seconds, so clients never receive an
optimistic answer. The delay is 0 exactly when the request was allowed.

All implementations read time from `System.nanoTime()`, which is monotonic, so
wall-clock changes such as NTP corrections cannot create or destroy tokens. The
clock is injectable in tests.

## Algorithms

All three limiters share one configuration: a bucket of `capacity` tokens that
refills (or drains) at `capacity / window` tokens per second. With the defaults,
that is 100 tokens per 60 seconds, about 1.67 tokens per second, with bursts of
up to 100 requests.

### Token bucket

Each client starts with a full bucket of `capacity` tokens. A request spends
tokens; tokens flow back continuously at the refill rate, never exceeding
capacity. Short bursts up to the capacity are allowed, while the long-run rate
is bounded by the refill rate.

### Atomic token bucket (default)

`AtomicTokenBucketLimiter` keeps buckets in a
`ConcurrentHashMap<String, AtomicLong>`. Each client's entire state is a single
`long`: the monotonic time, in nanoseconds, at which its bucket would have been
empty.

- Available tokens = `(now - emptyAt) / nanosPerToken`, capped at capacity
  (an `emptyAt` older than one full refill means a full bucket).
- Spending `n` tokens moves `emptyAt` forward by `n * nanosPerToken`.
- `nanosPerToken = ceil(window / capacity)` in nanoseconds, computed once at
  startup. All later arithmetic is exact integer math, so a full bucket holds
  exactly `capacity` tokens, and each token arrives less than 1 ns late.

Updates use a compare-and-set loop:

```text
loop:
  emptyAt  = state.get()
  base     = max(emptyAt, now - fullBucketNanos)
  if now - base < costNanos:            reject (state is not written)
  if state.compareAndSet(emptyAt, base + costNanos): allow
  else: another request won the race; count a CAS retry and loop
```

Two requests can never spend the same token, because only one of two
competing CAS operations can succeed. No thread holds a lock, and a failed CAS
always means another thread's update succeeded, so the limiter is lock-free:
the system as a whole always makes progress. Rejections never write, so a
client that is already over quota adds no contention.

Storing one timestamp instead of a packed `(timestamp, tokens)` pair needs no
bit-field layout, keeps nanosecond refill precision, and works for every valid
capacity and window. The encoding is the same as the theoretical arrival time
of the Generic Cell Rate Algorithm (GCRA).

### Synchronized token bucket (baseline)

`SynchronizedTokenBucketLimiter` stores each bucket as a mutable
`(double tokens, long lastRefillNanos)` pair in a `HashMap`, and one
`synchronized` method guards the entire map. It is the simplest correct
implementation and the baseline for the concurrency comparison: every
request, for every client, serializes on the same lock.

### Leaky bucket meter

`LeakyBucketLimiter` implements the leaky bucket as a meter. Each request pours
its cost into the client's bucket, the bucket drains at the configured rate,
and a request is allowed only if it fits without overflowing. New clients start
with an empty bucket.

For the same capacity and window, the meter makes exactly the same decisions as
the token bucket, because its level is the token bucket's spent quota
(`level = capacity - tokens`). A randomized test checks this over thousands of
requests, decision by decision. What differs is state representation and
synchronization:

| | Atomic token bucket | Leaky bucket | Synchronized token bucket |
|---|---|---|---|
| Per-client state | one `long` (`emptyAt`) | immutable `Level(levelNanos, updatedAt)` | mutable `(double, long)` pair |
| Container | `ConcurrentHashMap` | `ConcurrentHashMap` | `HashMap` |
| Synchronization | `AtomicLong` CAS | `AtomicReference` CAS | one global monitor |
| Allocation per allowed request | none | one `Level` | none |
| Arithmetic | exact integer | exact integer | floating point |
| Progress guarantee | lock-free | lock-free | blocking |

Swapping an immutable record through `AtomicReference` updates two fields
together without bit packing, at the cost of one short-lived allocation per
allowed request. If the clock appears to move backward, the leaky bucket keeps
the later timestamp, so the same interval is never drained twice.

### Leaky bucket queue (traffic shaping)

The algorithms above *police* traffic: a request is allowed or rejected at once,
so a full bucket's worth of requests can pass in a single burst.
`LeakyBucketQueue` *shapes* it instead. Each client's requests wait in a queue
and leave one at a time, every `window / capacity`, so whatever the arrival
pattern, the endpoint sees a steady stream:

| A burst of 30 (capacity 20, 2 per second) | Token bucket | Leaky bucket queue |
|---|---|---|
| Admitted | 20, all at once | 20: one now, then one every 0.5 s |
| Rejected | 10 | 10 |
| Longest wait | none | 9.5 s |

Admission is the same: both admit `capacity` requests from a burst. The
difference is when admitted requests proceed.

**Data structure.** Each client's waiting requests are kept in a `RingBuffer`, a
bounded FIFO queue on a fixed array whose head index and size wrap around the
end. Offering and polling are O(1), never move elements, and never allocate.
Each element is a `CompletableFuture` that completes when its request is
released.

**Leaking.** One request leaks out per interval:

- If the bucket is empty and no leak is due, a request leaks straight through.
- Otherwise it is appended to the ring buffer. A single scheduled task per
  non-empty bucket polls the oldest request each interval and completes its
  future, then reschedules itself while requests remain.
- The request that leaked last still occupies the bucket until the next leak is
  due, so a burst admits exactly `capacity` requests, as many as the token bucket.
- A request that finds the bucket full is rejected with `Retry-After`: the time
  until the next leak frees a place.
- The next leak is timed from the actual release, so a late timer never lets two
  requests out closer together than the interval.

A per-client monitor guards the buffer and timing fields. Admissions and leaks
of different clients never contend.

**Waiting without threads.** `RateLimitFilter` suspends a queued request with
servlet async processing (`request.startAsync()`). The servlet thread returns
to the pool, and when the future completes the request is dispatched to the
controller. Waiting requests therefore hold a queue slot and an open connection,
but no server thread; a test runs four waiting requests on a server with two
threads.

The `RateLimit` header's `r` is the number of free places in the bucket after
the request was queued.

## Configuration

Settings live in `src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `rate-limit.capacity-tokens` | `100` | Bucket size: the largest burst a client may send |
| `rate-limit.window-seconds` | `60` | Time to refill a full bucket from empty |
| `rate-limit.trust-forwarded-for` | `false` | Identify clients by `X-Forwarded-For` (see [Client identification](#client-identification)) |
| `rate-limit.algorithm` | `token-bucket` | How the filter admits requests: `token-bucket` (`AtomicTokenBucketLimiter`), `leaky-bucket` (`LeakyBucketLimiter`), or `leaky-bucket-queue` (`LeakyBucketQueue`) |
| `cors.allowed-origins` | empty (CORS off) | Browser origins allowed to call `/api/*` (see [CORS](#cors)) |

Capacity and window must be positive; invalid values stop the application at
startup. The refill rate is `capacity / window` tokens per second.

Properties can be overridden without editing the file, using standard Spring
Boot mechanisms:

```sh
# Command-line arguments
./mvnw spring-boot:run -Dspring-boot.run.arguments="--rate-limit.capacity-tokens=3"

# Environment variables (dashes removed, dots become underscores)
RATELIMIT_CAPACITYTOKENS=3 RATELIMIT_TRUSTFORWARDEDFOR=true ./mvnw spring-boot:run
```

The `observability` profile (`application-observability.properties`) sets a
20-token / 10-second quota, trusts `X-Forwarded-For`, and enables CORS for
local pages:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=observability
```

`rate-limit.algorithm` selects the limiter at startup; the synchronized token
bucket is kept for tests and comparison only.

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

CORS is off by default. When `cors.allowed-origins` lists origins (Spring origin
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

## Metrics

`RateLimitMetrics` is a Spring bean holding three counters:

| Counter | Recorded by | Meaning |
|---|---|---|
| `allowedRequests` | all limiters | Decisions that spent tokens |
| `rejectedRequests` | all limiters | Decisions refused for lack of tokens |
| `casRetries` | atomic token bucket, leaky bucket | CAS attempts lost to a concurrent update |

Invalid calls (blank key, bad cost) are not counted. The counters are
`LongAdder`s, which spread concurrent increments across cells instead of
contending on a single value, so measuring adds no hot spot to the request
path. `snapshot()` returns all three values; since each counter is read
separately, a snapshot taken under load is approximate.

`casRetries` measures contention on the lock-free path: a retry happens only
when two allowed requests for the same client race. Metrics are not exposed
over HTTP; they are read in code and tests.

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
ramp, continuous spam, and a client that honors `Retry-After`). It animates
each request through the filter and charts what passes and what is blocked.
Start the application with the `observability` profile and open the file in a
browser; see [`observability/README.md`](observability/README.md).

## Testing

```sh
./mvnw test
```

| Test class | Covers |
|---|---|
| `RateLimiterContractTests` | Shared behavior, run against **all three** limiters: full start, exhaustion, weighted costs, refill boundaries, refill cap after idle periods, clock moving backward, invalid input, per-outcome metrics, and concurrency (one shared client, many clients, weighted costs) |
| `AtomicTokenBucketLimiterTests` | Nanosecond refill boundary, fractional refill intervals, largest configuration without overflow, requests racing refills, observed CAS retries |
| `RingBufferTests` | Empty and full states, FIFO order, wrap-around, rejection when full, and 10,000 random operations checked against `ArrayDeque` |
| `LeakyBucketQueueTests` | First request passes at once, a burst leaves one per interval in arrival order, full bucket rejects with time to next leak, drained bucket passes again, late timers keep the interval, separate clients, metrics (deterministic clock and scheduler) |
| `LeakyBucketQueueHttpTests` | Real server: a burst of 6 against capacity 4 gives 2 immediate 429s and 4 responses released about 250 ms apart, on a server with only two threads |
| `LeakyBucketLimiterTests` | Continuous draining, nanosecond drain boundary, fractional intervals, overflow safety, decision-by-decision equivalence with the atomic token bucket, observed CAS retries |
| `SynchronizedTokenBucketLimiterTests` | The shared contract |
| `RateLimitFilterTests` | Quota headers, the 429 problem response, per-client and per-API-key isolation, ignored `X-Forwarded-For`, `/api/*` scope, metrics wiring |
| `ClientKeyResolverTests` | Source priority, SHA-256 hashing, blank values, forwarded-header trust boundary and last-entry selection |
| `RateLimitMetricsTests` | Counting by outcome, no lost increments under concurrency |
| `CorsTests` | Preflights consume no tokens, quota headers are exposed on 200 and 429, pages opened from disk are allowed, unknown origins are refused without consuming tokens, CORS is off by default |
| `RateLimitContractTests` | Configuration binding and validation, decision invariants |
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
- **Metrics are internal.** Counters are not exported to a monitoring system.
- **Queued requests hold connections.** The leaky bucket queue keeps each
  waiting request's connection open for up to one window. Waiting requests use
  no server threads, but every client can hold up to `capacity` open
  connections, and a queued request whose client disconnects still occupies its
  place until it leaks.
