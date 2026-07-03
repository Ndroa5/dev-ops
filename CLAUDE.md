# devops-microservices

University DevOps project: a 5-service Spring Boot microservices architecture, built as a Maven
multi-module project. Currently at the "runs locally" scaffold stage — no Docker, no RabbitMQ,
no tests, no monitoring yet (see Phase Plan below).

## Architecture

```
                        ┌──────────────┐
                        │  api-gateway │  :8080  (Spring Cloud Gateway, routes only)
                        └──────┬───────┘
             ┌──────────────────┼──────────────────┬────────────────────┐
             │                  │                   │                    │
      ┌──────▼──────┐   ┌──────▼───────┐   ┌───────▼──────┐   ┌─────────▼────────┐
      │ user-service │   │catalog-service│   │ order-service │   │notification-svc │
      │    :8081     │   │    :8082      │   │    :8083      │   │      :8084       │
      └──────┬───────┘   └──────┬────────┘   └───────┬───────┘   └────────┬─────────┘
             │                  │                     │                    │
         ┌───▼───┐          ┌───▼────┐            ┌───▼────┐          ┌────▼──────┐
         │userdb │          │catalogdb│           │orderdb │          │notificationdb│
         └───────┘          └────────┘            └────────┘          └───────────┘
```

- **api-gateway** — Spring Cloud Gateway. Single entry point, routes `/api/auth/**` and
  `/api/users/**` → user-service, `/api/books/**` → catalog-service, `/api/orders/**` →
  order-service, `/api/notifications/**` → notification-service. No database, no
  controller/service/repository layering (routing config only) — that's not how gateways work.
- **user-service** — registration/login, issues and validates JWTs (jjwt), Spring Security,
  BCrypt password hashing. Owns `userdb`.
- **catalog-service** — CRUD over the `Book` domain (title, author, isbn, genre, price,
  stockQuantity), plus a `PATCH /api/books/{id}/decrement-stock` endpoint used by order-service.
  Owns `catalogdb`.
- **order-service** — creates orders: calls catalog-service via REST (`RestClient`) to check/
  decrement stock, saves the order, then calls a stub `OrderEventPublisher` that currently just
  logs what it *would* publish to RabbitMQ (see Phase Plan — messaging isn't wired up yet). Owns
  `orderdb`.
- **notification-service** — simulates sending a notification. `POST /api/notifications/send`
  triggers `NotificationService.simulateSend(...)`, which runs the "send" reactively via RxJava
  (`Single`, `Schedulers.io()`), logs it, and persists a record. In phase 2 a RabbitMQ listener
  will call the same method instead of a human hitting the REST endpoint. Owns `notificationdb`.

Each business service (user/catalog/order/notification) follows a layered structure:
`controller` → `service` → `repository` → `model`, plus `dto` for request/response shapes and
`exception` for a `@RestControllerAdvice` handler.

## Ports

| Service              | Port |
|----------------------|------|
| api-gateway           | 8080 |
| user-service          | 8081 |
| catalog-service       | 8082 |
| order-service         | 8083 |
| notification-service  | 8084 |
| PostgreSQL            | 5432 (default) |

## Databases

One local PostgreSQL instance, one database per service (database-per-service pattern):

```sql
CREATE DATABASE userdb;
CREATE DATABASE catalogdb;
CREATE DATABASE orderdb;
CREATE DATABASE notificationdb;
```

All services assume `postgres`/`postgres` on `localhost:5432` (see each service's
`application.yml` — `datasource.username` / `datasource.password`). Change these if your local
Postgres uses different credentials. `spring.jpa.hibernate.ddl-auto: update` is set everywhere,
so tables are created automatically on first run — no manual schema/migration step yet.

## Build & run

Requires JDK 21 and Maven. Postgres must be running locally with the databases above created
before starting any service that owns one (all except api-gateway).

Build everything from the root:
```
mvn clean install
```

Run a single service (each is independently runnable):
```
cd user-service && mvn spring-boot:run
cd catalog-service && mvn spring-boot:run
cd order-service && mvn spring-boot:run
cd notification-service && mvn spring-boot:run
cd api-gateway && mvn spring-boot:run
```

Start order for a full local run: Postgres → catalog-service → order-service → user-service →
notification-service → api-gateway (gateway last since it just routes to the others; the order
among the rest doesn't matter, but order-service will fail its REST calls if catalog-service
isn't up yet).

Quick smoke test once everything is running (through the gateway on :8080):
```
curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}'

curl -X POST http://localhost:8080/api/books -H "Content-Type: application/json" \
  -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441172719","genre":"Sci-Fi","price":15.99,"stockQuantity":10}'

curl -X POST http://localhost:8080/api/orders -H "Content-Type: application/json" \
  -d '{"bookId":1,"quantity":2}'

curl -X POST http://localhost:8080/api/notifications/send -H "Content-Type: application/json" \
  -d '{"recipient":"alice@example.com","message":"Your order shipped"}'
```

## Phase plan

1. **REST communication** *(current phase)* — services scaffolded, order-service ↔
   catalog-service wired via REST (`RestClient`), gateway routes all traffic. Done.
2. **RabbitMQ** — add `spring-boot-starter-amqp` to order-service and notification-service.
   Replace `OrderEventPublisher`'s log stub with an actual `RabbitTemplate.convertAndSend(...)`.
   Replace notification-service's manual `POST /api/notifications/send` trigger with a
   `@RabbitListener` that calls `NotificationService.simulateSend(...)` for each consumed
   message (the RxJava processing stays the same, just triggered by a queue instead of a human).
3. **Tests** — unit tests per service (service-layer logic, controller slice tests), integration
   tests for the order-service → catalog-service REST call and the eventual RabbitMQ flow.
4. **Docker** — Dockerfile per service, docker-compose for Postgres (+ per-service DBs) and
   RabbitMQ, so the whole stack runs with one command instead of five terminal tabs.
5. **CI/CD** — pipeline (GitHub Actions or similar) that builds all modules, runs tests, and
   builds/pushes Docker images on merge.
6. **Monitoring** — Spring Boot Actuator health/metrics endpoints, likely Prometheus + Grafana
   for scraping/visualizing.
