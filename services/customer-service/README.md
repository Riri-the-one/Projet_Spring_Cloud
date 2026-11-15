# customer-service

> Customer profiles: the first business microservice of TicketFlow.

## Concept illustrated
A standalone Spring Boot REST service with its own database. It owns customer
data (name, email, phone). Keycloak authenticates users; this service independently validates
JWTs and binds profiles to the immutable `(iss, sub)` identity. See the
[French phase 2A guide](../../docs/ARIJ_CUSTOMER_IDENTITY.md) and the
[Booking integration contract](../../docs/ARIJ_CUSTOMER_BOOKING_CONTRACT.md).

## Overview
- **Port:** 8081
- **Database:** H2 in-memory (recreated on every start)
- **Depends on:** Config Server (8888, mandatory import), Eureka (8761, registration)
- **JWT:** RS256, Keycloak issuer, audience `ticketflow-api`; no anonymous business access.

| Method | Path | Description |
|---|---|---|
| POST | `/api/customers` | CUSTOMER/ADMIN creates own profile (201 + Location, duplicate 409) |
| GET/PUT | `/api/customers/me` | CUSTOMER/ADMIN reads or updates own profile |
| GET | `/api/customers/{id}` | Owner or ADMIN |
| GET | `/api/customers` | ADMIN lists customers |
| PUT | `/api/customers/{id}` | Owner or ADMIN, immutable identity |
| DELETE | `/api/customers/{id}` | ADMIN only; coordinate persistent Booking references |
| GET | `/internal/customers/me` | Resolve authenticated profile to customerId; not routed by Gateway |
| GET | `/api/customers/whoami` | ADMIN diagnostic, identifies the service instance |

## Service discovery
Since `step-03-discovery` the service registers itself in Eureka under the name
`customer-service` (the value of `spring.application.name`). This is the name the
gateway uses in `lb://customer-service`. The public path is `/api/customers/**`, unchanged at the backend.

- Eureka address: `eureka.client.service-url.defaultZone`, overridable with the
  `EUREKA_URL` environment variable (default `http://localhost:8761/eureka/`).
- `eureka.instance.prefer-ip-address=true` registers the IP instead of the machine
  name, which avoids name-resolution problems in containers.
- If Eureka is not running, the service still starts and answers on 8081; it only
  logs registration errors.

Business errors follow RFC 9457 (`ProblemDetail`): 400 validation, 404 not found, 409 email/identity conflict.
Spring Security returns 401 for missing/invalid tokens and 403 for insufficient permissions.

## Package layout
    controllers/   REST endpoints
    service/       business logic
    repos/         Spring Data repositories
    entities/      JPA entities
    dto/           request/response records (the API contract)
    exceptions/    domain exceptions and their HTTP mapping

## Run locally
Start Config Server first (native demo or configured Git mode), then the registry (`./mvnw -pl services/discovery-server spring-boot:run`)
and look for `CUSTOMER-SERVICE` on http://localhost:8761 (up to ~30 s).

    ./mvnw -pl services/customer-service spring-boot:run

    curl -i -X POST localhost:8081/api/customers \
      -H "Authorization: Bearer $TICKETFLOW_ACCESS_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"firstName":"Sara","lastName":"Idrissi","email":"sara@example.com"}'

This shell example requires a CUSTOMER or ADMIN access token obtained using OIDC Code + PKCE.
For the complete PowerShell/local demonstration, run `node tools/Test-KeycloakIntegration.mjs`.

## Run with Docker
    docker compose -f infra/docker-compose.yml up --build customer-service

## Run the tests
    ./mvnw -pl services/customer-service test

Tests run with `eureka.client.enabled=false`, so no registry is needed.
The test-only configuration also disables Config Client and provides H2 and the required local properties.
For Config Server, environment variables, Docker and the TP2/TP3 scenario, see the
[French demonstration guide](../../docs/ARIJ_GATEWAY_DOCKER.md).

## Exercise
1. Add a `GET /api/customers?email=...` search endpoint with a test.
2. Restart the service and list customers. Why is the list empty? What would
   you change to keep the data?

## Common pitfalls
- Exposing JPA entities directly in the API couples your contract to your
  database schema: that is why we use `CustomerResponse`.
- `open-in-view` is disabled on purpose to avoid lazy-loading surprises.
- An in-memory database is fine for learning but never shared between
  services: each service owns its data.
