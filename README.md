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
HTTP rate limiting is not enforced yet.

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
idle-client eviction. This implementation is not yet wired into HTTP requests.

## Tests

```sh
./mvnw test
```

Tests cover application startup, the demo endpoint, configuration binding and
validation, decision metadata, bucket exhaustion, weighted requests, refill
boundaries, client isolation, invalid input, and concurrent quota consumption.
