# melocoton-api

REST API for Melocotón, a management app for a ceramics workshop: class slots and bookings with capacity, attendance, make-up classes, pieces and their firing status, payments, and an AI idea assistant.

Design documents and the development plan live in `../docs/` (in Spanish). Code naming follows `../docs/glosario_en.md`.

## Stack

Kotlin · Java 21 · Spring Boot 4 · Gradle (Kotlin DSL) · Spring Data JPA · Flyway · PostgreSQL 17.

## Requirements

- JDK 21
- A local PostgreSQL 17 with the `melocoton_dev` and `melocoton_test` databases and the `melocoton_owner` and `melocoton_app` roles

## Setup

1. Copy `.env.example` to `.env` and fill in the passwords.
2. Run the commands below from this folder.

## Commands (Windows)

```
gradlew.bat bootRun     # start the API on http://localhost:8080
gradlew.bat test        # run the tests (uses melocoton_test)
gradlew.bat build       # compile, test and package
gradlew.bat updateOpenApi   # regenerate openapi/openapi.json after changing the API
```

With the API running:

- Health check: http://localhost:8080/api/v1/health
- Swagger UI: http://localhost:8080/swagger-ui.html

## API contract

`openapi/openapi.json` is the contract the PWA generates its types from. A test fails when it no longer matches the API; regenerate it with `gradlew.bat updateOpenApi`, review the diff and commit it.
