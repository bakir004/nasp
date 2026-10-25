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
Rate limiting is not implemented yet.

## Tests

```sh
./mvnw test
```

Tests check application startup, the demo response, and HTTP 404 for an unknown endpoint.
