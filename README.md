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

## Tests

```sh
./mvnw test
```

Tests check application startup, the demo response, and HTTP 404 for an unknown endpoint.
