# NASP

Local HTTP rate limiter project built with Java 17 and Spring Boot.

## Requirements

- JDK 17 or a compatible newer JDK (Java 17 bytecode target)
- Internet access for the first Maven wrapper run

## Build

```sh
./mvnw clean verify
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

## Run

```sh
./mvnw spring-boot:run
```

The application starts on port 8080.

## Demo endpoint

```sh
curl -i http://localhost:8080/api/hello
```

Expected: HTTP `200 OK`, content type `text/plain`, and body `ok`.

## HTTP rate limiting

`RateLimitFilter` runs for every request under `/api/*`, before the request
reaches a controller, and charges one token per request. Unknown `/api/*` paths
also consume quota; paths outside `/api/*` are never limited.

Every limited response carries the current quota, following the IETF HTTPAPI
RateLimit header fields draft:

```http
RateLimit-Policy: "default";q=100;w=60
RateLimit: "default";r=99
```

`q` is bucket capacity, `w` the refill window in seconds, and `r` the whole
tokens remaining. When the bucket is exhausted, the filter answers without
calling the controller:

```http
HTTP/1.1 429 Too Many Requests
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

To see a rejection locally, send more than 100 requests within a minute:

```sh
for i in $(seq 101); do curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/hello; done | sort | uniq -c
```

## Client identification

`ClientKeyResolver` chooses each request's bucket, using the first source that
is present and nonblank:

1. Authenticated principal name: `principal:<sha256>`
2. `X-API-Key` header: `api-key:<sha256>`
3. `X-Forwarded-For`, only when `rate-limit.trust-forwarded-for=true`: `ip:<address>`
4. Servlet remote address: `ip:<address>`

Principals and API keys are hashed with SHA-256, so raw credentials are never
stored as limiter keys.

`X-Forwarded-For` is ignored by default because any client can send it. Enable
it only when the application is reachable solely through one reverse proxy that
appends the client address. The resolver then uses the **last** entry, the one
added by that proxy; earlier entries come from the client and can be forged.
Chains of several proxies are not supported.

API keys are not validated, since the project has no key registry. A client
that sends a different `X-API-Key` on each request gets a fresh bucket each
time, so the API-key policy only has value once keys are checked upstream.

## Rate limit configuration

`src/main/resources/application.properties` defines capacity (100 tokens), refill
window (60 seconds), and forwarded-header trust (disabled). Capacity and window
must be positive; invalid values fail application startup.

The algorithm contract is `RateLimiter.tryAcquire(key, costTokens)`. It returns
an immutable `RateLimitDecision` containing allowance, remaining whole tokens,
capacity, window, and retry delay in seconds. Keys must be nonblank and costs
positive; implementations reject costs greater than capacity.

## Atomic token bucket

`AtomicTokenBucketLimiter` is the limiter the HTTP filter uses. Buckets live in
a `ConcurrentHashMap<String, AtomicLong>`, and each client's entire bucket state
is that single `long`: the monotonic time, in nanoseconds, at which the bucket
would have been empty.

- Available tokens = `(now - emptyAt) / nanosPerToken`, capped at capacity
  (an `emptyAt` older than one full refill counts as a full bucket).
- Consuming `n` tokens moves `emptyAt` forward by `n * nanosPerToken`.
- `nanosPerToken` is `window / capacity` rounded up to a whole nanosecond once,
  at startup. All later arithmetic is exact integer math: a full bucket holds
  exactly `capacity` tokens, and each token arrives less than 1 ns late.

Every update is a compare-and-set loop: read `emptyAt`, compute the new value,
and `compareAndSet` it. If another request changed the bucket in between, the
CAS fails and the loop re-reads, so two requests can never spend the same token.
No thread holds a lock, and a failed CAS always means another request succeeded,
so the limiter is lock-free. Rejections never write, so they never contend.

Storing one timestamp instead of a packed `(timestamp, tokens)` pair needs no
bit-field layout, keeps nanosecond refill precision, and works for every valid
capacity and window. This encoding is the same as the theoretical arrival time
used by the Generic Cell Rate Algorithm (GCRA).

## Synchronized token bucket

`SynchronizedTokenBucketLimiter` is a standalone, in-memory baseline kept for
comparison with the atomic implementation. Each client
starts with a full bucket; tokens refill continuously at `capacity / window`
tokens per second, up to capacity. Rejected requests do not consume tokens.
Fractional tokens are retained internally; remaining quota is rounded down and
retry delay is rounded up to whole seconds. The time source is monotonic.

One synchronized method protects all buckets, prioritizing simplicity over
throughput. State is local to one JVM, resets on restart, and currently has no
idle-client eviction. The atomic limiter shares these limitations.

## Tests

```sh
./mvnw test
```

Tests cover application startup, the demo endpoint, configuration binding and
validation, decision metadata, bucket exhaustion, weighted requests, refill
boundaries, client isolation, invalid input, and concurrent quota consumption.
Both token buckets run the same contract tests (`TokenBucketLimiterContractTests`).
The atomic limiter adds nanosecond refill boundaries, fractional refill
intervals, the largest valid configuration, and concurrency tests across many
clients, weighted costs, and refills during contention.
HTTP integration tests cover quota headers, the `429` problem response, per-client
isolation, and the `/api/*` filter scope. Client identification tests cover
source priority, credential hashing, and the `X-Forwarded-For` trust boundary.
