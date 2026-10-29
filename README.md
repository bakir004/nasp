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
reaches a controller, and charges one token per request. Clients are currently
identified by servlet remote address only. Unknown `/api/*` paths also consume
quota; paths outside `/api/*` are never limited.

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

## Rate limit configuration

`src/main/resources/application.properties` defines capacity (100 tokens), refill
window (60 seconds), and forwarded-header trust (disabled). Capacity and window
must be positive; invalid values fail application startup.

The algorithm contract is `RateLimiter.tryAcquire(key, costTokens)`. It returns
an immutable `RateLimitDecision` containing allowance, remaining whole tokens,
capacity, window, and retry delay in seconds. Keys must be nonblank and costs
positive; implementations reject costs greater than capacity.

## Synchronized token bucket

`SynchronizedTokenBucketLimiter` is a standalone, in-memory baseline. Each client
starts with a full bucket; tokens refill continuously at `capacity / window`
tokens per second, up to capacity. Rejected requests do not consume tokens.
Fractional tokens are retained internally; remaining quota is rounded down and
retry delay is rounded up to whole seconds. The time source is monotonic.

One synchronized method protects all buckets, prioritizing simplicity over
throughput. State is local to one JVM, resets on restart, and currently has no
idle-client eviction. It is the limiter the HTTP filter currently uses.

## Tests

```sh
./mvnw test
```

Tests cover application startup, the demo endpoint, configuration binding and
validation, decision metadata, bucket exhaustion, weighted requests, refill
boundaries, client isolation, invalid input, and concurrent quota consumption.
HTTP integration tests cover quota headers, the `429` problem response, per-client
isolation, and the `/api/*` filter scope.
