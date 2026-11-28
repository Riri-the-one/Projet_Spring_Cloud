# TicketFlow

Contribution Arij et démonstration Windows : [guide de livraison final](docs/ARIJ_FINAL_DELIVERY.md).

**A hands-on reference project for learning Spring Cloud and microservices, built around a real-world ticket booking platform.**

TicketFlow is an open-source educational project that takes you from
"what is a microservice?" to building resilient, event-driven, observable
distributed systems with the Spring ecosystem.

Instead of yet another e-commerce demo, it tackles problems that naturally
require distributed-systems patterns: limited seat inventory, concurrent
bookings, unreliable payment providers, and asynchronous notifications.

Le cahier des charges détaillé (en français) est disponible dans
[`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md). Il distingue les
composants déjà présents des éléments planifiés et des choix restant à valider.

## What you will learn

- **Service discovery & routing**: Eureka and Spring Cloud Gateway
- **Centralized configuration**: Spring Cloud Config with dynamic refresh
- **Inter-service communication**: declarative REST clients with OpenFeign
- **Resilience**: circuit breaker, retry, timeout, bulkhead and fallbacks (Resilience4J)
- **Event-driven architecture**: asynchronous messaging with Apache Kafka
- **Distributed transactions**: Saga pattern, Outbox pattern, idempotency
- **Containerization**: Docker and Docker Compose
- **AI integration** *(later)*: natural-language search and RAG assistant with Spring AI

## Architecture

Le guide pratique en français [Gateway et Docker d'Arij](docs/ARIJ_GATEWAY_DOCKER.md)
décrit les six services, les routes explicites `/api/customers`, `/api/events`, `/api/bookings`,
les modes Config Server native/Git et les commandes PowerShell de démonstration.
La [phase 1 Keycloak/JWT](docs/ARIJ_SECURITY_KEYCLOAK.md) protège maintenant le Gateway.
La [phase 2A Customer](docs/ARIJ_CUSTOMER_IDENTITY.md) ajoute la validation JWT directe et la propriété
des profils. Les contrôles métier Event/Booking restent à réaliser.

L'[infrastructure PostgreSQL optionnelle](docs/ARIJ_POSTGRESQL_INFRA.md) prépare trois bases isolées
avec un volume persistant. Elle s'active via un overlay/profil explicite ; les services restent en H2
jusqu'aux migrations de leurs propriétaires. [Coordination Manar/Salma](docs/ARIJ_POSTGRESQL_COORDINATION.md).

| Module | Role | Key technology | Status |
|---|---|---|---|
| `customer-service` | Customer profiles, JWT identity and ownership | Spring MVC, JPA, Spring Security | Security phase 2A |
| `api-gateway` | Explicit load-balanced routes, JWT and role checks | Spring Cloud Gateway WebFlux, Spring Security | Security phase 1 |
| `discovery-server` | Service registry | Eureka | Done |
| `event-service` | Events and seats catalog | Spring Web, JPA | Implemented |
| `config-server` | Centralized configuration (Git / native demo) | Spring Cloud Config | Implemented |
| `booking-service` | Booking and seat locking | OpenFeign, Resilience4J | Implemented |
| `payment-service` | Simulated payment provider (random failures) | Resilience4J | Planned |
| `notification-service` | Confirmation emails/SMS | Kafka consumer | Planned |
| `web-frontend` | Event catalog and customer booking journey | Angular | Planned |
| `ai-assistant-service` | Read-only assistant via Telegram | Cloud AI provider + Telegram Bot API (to be confirmed) | Planned |

## Roadmap

Each step has a Git tag and a lesson in `docs/lessons/`.

| Tag | Content |
|---|---|
| `step-00-setup` | Parent POM, repo structure, CI |
| `step-01-customer-service` | First business service |
| `step-02-gateway-static` | Gateway with static routes |
| `step-03-discovery` | Eureka + load-balanced routing |
| `step-04-event-service` | Second business service |
| `step-05-config` | Config Server |
| `step-06-booking-feign` | booking-service + OpenFeign |
| `step-07-resilience` | payment-service + Resilience4J |
| `step-08-kafka` | Events + notification-service |
| `step-09-saga` | Saga, Outbox, idempotency |
| `step-10-security` | Keycloak (customer-service integration) |
| `step-11-observability` | Tracing and metrics |
| `step-12-ai` | Spring AI |

To follow a lesson, check out its tag: `git checkout step-01-customer-service`.

## Tech stack

Java 21 · Spring Boot 4 · Spring Cloud 2025.1 · Resilience4J · Apache Kafka ·
PostgreSQL · Docker / Docker Compose · Spring AI (later)

## Repository structure

    ticketflow/
    ├── docs/            # lessons, ADRs, templates
    ├── infra/           # docker-compose and infrastructure config
    ├── infra/config-demo/ # native development configuration; Git repo is external
    └── services/        # one folder per microservice, each with its own README

Every module is independently runnable and testable, and documents itself in
its own `README.md`.

## Getting started

Prerequisites: JDK 21, Maven (or the included wrapper), Docker.

    git clone https://github.com/Riri-the-one/Projet_Spring_Cloud.git
    cd Projet_Spring_Cloud
    ./mvnw -B -ntp verify

PowerShell : `.\mvnw.cmd -B -ntp verify`. Avec Docker Desktop (conteneurs Linux) :

```powershell
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml up -d --build --wait --wait-timeout 300
node tools/Test-KeycloakIntegration.mjs
```

Le test OIDC nécessite Node.js 22 ou plus et utilise Code + PKCE avec trois comptes fictifs.
Compose utilise le mode native local, six services, Keycloak 26.8.2 et H2. Le mode TP3/Git
nécessite l'URL réelle `CONFIG_GIT_URI` et l'overlay `infra/docker-compose.git.yml`.

## Ticket Service — premier lot

Le [module Ticket](services/ticket-service/README.md) fournit le cœur d'émission
idempotente et une consultation JWT protégée via Gateway. Son
[stockage PostgreSQL dédié](docs/ARIJ_TICKET_PERSISTENCE_GATEWAY.md) est activable
explicitement ; H2 reste disponible et aucune émission réseau n'est activée. Le
[contrat BookingConfirmed proposé](docs/ARIJ_TICKET_BOOKING_CONTRACT.md) attend Salma.

## Customer PostgreSQL optionnel

Customer peut utiliser la base PostgreSQL existante via un overlay explicite, avec
Flyway et validation du schéma. Le mode H2 reste disponible. Voir le
[guide Customer PostgreSQL](docs/ARIJ_CUSTOMER_POSTGRESQL.md).

## Kafka local optionnel

Le broker KRaft est activé explicitement par `infra/docker-compose.kafka.yml` et le
profil `kafka`. Les services existants restent indépendants de Kafka.
Voir le [guide PowerShell et les tests réels](docs/ARIJ_KAFKA_INFRA.md) et les
[contrats proposés aux producteurs métier](docs/ARIJ_KAFKA_CONTRACTS.md).

## Who is this for?

Java developers who know Spring Boot basics and want to move into
microservices, and anyone preparing for backend or architecture interviews.

<!--
## Contributing

Contributions, issues and exercises are welcome. A `CONTRIBUTING.md` will be
added soon.
-->

## Notification Service indépendant

Le [guide Notification](docs/ARIJ_NOTIFICATION_SERVICE.md) décrit PostgreSQL isolé,
inbox Kafka technique, déduplication, canal simulé et reprises durables. Aucun
consommateur Booking/Payment n'est branché sans contrat validé.

## License

To be defined (MIT recommended for an educational project).

## Author

**AHAIDOUS Khadija**   | [GitHub](https://github.com/kahaidous>) · [LinkedIn](https://www.linkedin.com/in/khadijaahaidous)
