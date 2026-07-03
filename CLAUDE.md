# devops-microservices

University DevOps project: a 5-service Spring Boot microservices architecture, built as a Maven
multi-module project. REST communication and RabbitMQ messaging are both done (see Phase Plan
below); no tests, Docker, CI/CD, or monitoring yet.

## Architecture

```
                        ┌──────────────┐
                        │  api-gateway │  :9080  (Spring Cloud Gateway, routes only)
                        └──────┬───────┘
             ┌──────────────────┼──────────────────┬────────────────────┐
             │                  │                   │                    │
      ┌──────▼──────┐   ┌──────▼───────┐   ┌───────▼──────┐   ┌─────────▼────────┐
      │ user-service │   │catalog-service│   │ order-service │   │notification-svc │
      │    :9081     │   │    :9082      │   │    :9083      │   │      :9084       │
      └──────┬───────┘   └──────┬────────┘   └───────┬───────┘   └────────┬─────────┘
             │                  │                     │                    │
         ┌───▼───┐          ┌───▼────┐            ┌───▼────┐          ┌────▼──────┐
         │userdb │          │catalogdb│           │orderdb │          │notificationdb│
         └───────┘          └────────┘            └────────┘          └───────────┘
                                                        │                    ▲
                                                        │  OrderCreatedEvent │
                                                        └──── RabbitMQ ──────┘
                                                 (order-events-exchange, topic)
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
  decrement stock, saves the order, then publishes an `OrderCreatedEvent` to RabbitMQ via
  `OrderEventPublisher` (real `RabbitTemplate.convertAndSend`, not a stub anymore). Owns
  `orderdb`.
- **notification-service** — simulates sending a notification, two entry points converge on the
  same RxJava pipeline (`NotificationService.simulateSend`, `Single` + `Schedulers.io()`):
  - `OrderEventListener` (`@RabbitListener`) consumes `OrderCreatedEvent` from RabbitMQ and calls
    `simulateSend` automatically for every confirmed order.
  - `POST /api/notifications/send` still works as a manual trigger for the same pipeline (useful
    for testing without going through the whole order flow).
  Both paths persist a `NotificationLog` row. Owns `notificationdb`.

Each business service (user/catalog/order/notification) follows a layered structure:
`controller` → `service` → `repository` → `model`, plus `dto` for request/response shapes and
`exception` for a `@RestControllerAdvice` handler.

## Ports

| Service              | Port |
|----------------------|------|
| api-gateway           | 9080 |
| user-service          | 9081 |
| catalog-service       | 9082 |
| order-service         | 9083 |
| notification-service  | 9084 |
| PostgreSQL (dedicated container) | 5433 |
| RabbitMQ AMQP (dedicated container) | 5673 |
| RabbitMQ management UI | 15673 |

**Why 9080-9084 instead of the usual 8080-8084:** this machine also runs an unrelated Docker
Compose project ("cinebook") whose containers occupy 8080-8084, 5432, and 5672/15672. Rather than
touch that project's containers, this project's services and infra were moved to dedicated ports.
If you're on a clean machine with nothing else running, you can move everything back to
8080-8084/5432/5672 by editing each `application.yml` — nothing here depends on the 9xxx range
specifically.

## Infrastructure (Docker)

Two standalone containers, isolated from any other project on this machine:

```bash
# PostgreSQL — one server, one database per service
docker run -d --name devops-postgres -p 5433:5432 \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres postgres:16-alpine

docker exec devops-postgres psql -U postgres -c "CREATE DATABASE userdb;"
docker exec devops-postgres psql -U postgres -c "CREATE DATABASE catalogdb;"
docker exec devops-postgres psql -U postgres -c "CREATE DATABASE orderdb;"
docker exec devops-postgres psql -U postgres -c "CREATE DATABASE notificationdb;"

# RabbitMQ (with management UI)
docker run -d --name devops-rabbitmq -p 5673:5672 -p 15673:15672 rabbitmq:3-management
```

Management UI: http://localhost:15673 (guest/guest).

**Note on why these are dedicated containers, not a "local Postgres/RabbitMQ install":** this
machine has a *native* Windows PostgreSQL 17 service already bound to `0.0.0.0:5432`, which
silently shadows any Docker container's `-p 5432:5432` publish (Docker's port mapping succeeds at
the Docker level, but the native Windows process, having grabbed the port first, is what actually
answers connections). If you hit `FATAL: password authentication failed` against a container you
just started and are sure the credentials are right, check `Get-Service *postgres*` /
`Get-NetTCPConnection -LocalPort 5432` for exactly this kind of pre-existing listener before
assuming the container is misconfigured.

`spring.jpa.hibernate.ddl-auto: update` is set everywhere, so tables are created automatically on
first run against these databases — no manual schema/migration step yet.

## RabbitMQ messaging (phase 2 — done)

- **Exchange**: `order-events-exchange` (topic, durable). Declared by both order-service
  (producer) and notification-service (consumer) as idempotent `TopicExchange` beans — either one
  declares it fine if the other is down.
- **Routing key**: `order.created`
- **Queue**: `notification.order-created-queue` (durable), declared and bound by
  notification-service.
- **Wire format**: order-service serializes `OrderCreatedEvent` to a JSON string via Jackson and
  sends it as a plain AMQP string body (default `SimpleMessageConverter`, no custom
  `MessageConverter`/`__TypeId__` header dance). notification-service's `@RabbitListener` receives
  the raw JSON string and deserializes it into its **own local** `OrderCreatedEvent` record
  (`com.university.notificationservice.event.OrderCreatedEvent`) via `ObjectMapper.readValue`.
  The two services do not share a compiled class for this — only the JSON shape — so they stay
  decoupled at the code level, matching how independently-deployable services should agree on a
  message contract.

Payload shape (both services keep an identically-shaped record, just in different packages):
```json
{
  "orderId": 1,
  "bookId": 1,
  "quantity": 2,
  "buyerEmail": "alice@example.com",
  "totalPrice": 31.98,
  "createdAt": "2026-07-02T23:37:13.646516700Z"
}
```

Config keys (same convention in both services' `application.yml`, under `app.rabbitmq.*`):
```yaml
app:
  rabbitmq:
    exchange: order-events-exchange
    routing-key: order.created
    queue: notification.order-created-queue   # notification-service only
```

Flow: `POST /api/orders` (order-service) → stock check/decrement via catalog-service REST call →
order saved → `OrderEventPublisher.publishOrderCreated(...)` → RabbitMQ → notification-service's
`OrderEventListener` → `NotificationService.simulateSend(...)` (RxJava `Single`, same pipeline the
manual REST endpoint uses) → `NotificationLog` persisted. Verified end-to-end on 2026-07-03: order
creation triggered publish (~1ms after order commit), listener consumed and processed within
~70ms, queue settled at 0 ready / 0 unacked, and the resulting log entry appeared via
`GET /api/notifications`.

**Buyer identity**: order-service has no auth of its own — `POST /api/orders` takes `buyerEmail`
directly in the request body rather than deriving it from a JWT (deliberate scope decision to
keep this phase about messaging, not auth; see Phase Plan item 2 note below).

**Not implemented yet (deliberately out of scope for this phase)**: retry policy/dead-letter
queue for failed message processing. `OrderEventListener` currently catches and logs any
processing exception without rethrowing, so a bad message is acked and dropped rather than
retried or dead-lettered. Revisit if a later phase needs delivery guarantees.

## Build & run

Requires JDK 21 and Maven. Docker containers above must be running before starting any service
that owns a database (all except api-gateway) or that touches RabbitMQ (order-service,
notification-service).

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

Start order for a full local run: Postgres + RabbitMQ containers → catalog-service →
notification-service → order-service → user-service → api-gateway (gateway last since it just
routes to the others; order-service needs catalog-service up for its REST calls, and ideally
notification-service up before you create an order so you can see the consumer fire immediately).

Quick smoke test once everything is running (through the gateway on :9080):
```bash
curl -X POST http://localhost:9080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}'

curl -X POST http://localhost:9080/api/books -H "Content-Type: application/json" \
  -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441172719","genre":"Sci-Fi","price":15.99,"stockQuantity":10}'

curl -X POST http://localhost:9080/api/orders -H "Content-Type: application/json" \
  -d '{"bookId":1,"quantity":2,"buyerEmail":"alice@example.com"}'

# Confirms the RabbitMQ round trip happened without checking logs:
curl http://localhost:9080/api/notifications

# Manual trigger still works independently of the order flow:
curl -X POST http://localhost:9080/api/notifications/send -H "Content-Type: application/json" \
  -d '{"recipient":"alice@example.com","message":"Your order shipped"}'
```

**Known gotcha fixed 2026-07-03**: the parent pom's `maven-compiler-plugin` now sets
`<parameters>true</parameters>`. Without it, `@PathVariable Long id` (no explicit name) throws
`IllegalArgumentException: Name for argument of type [java.lang.Long] not specified` at request
time — compiles fine, only fails when the endpoint is actually hit. If you add new
`@PathVariable`/`@RequestParam` args without an explicit name and see that error, check this flag
survived any pom edits.

## Phase plan

1. **REST communication** — done. Services scaffolded, order-service ↔ catalog-service wired via
   REST (`RestClient`), gateway routes all traffic.
2. **RabbitMQ** — done (2026-07-03). See "RabbitMQ messaging" section above for exchange/queue
   names, payload shape, and the buyer-identity scope decision (client-supplied `buyerEmail`, no
   JWT validation in order-service).
3. **Tests** *(next)* — unit tests per service (service-layer logic, controller slice tests),
   integration tests for the order-service → catalog-service REST call and the RabbitMQ
   publish/consume flow.
4. **Docker** — Dockerfile per service, docker-compose for Postgres (+ per-service DBs) and
   RabbitMQ, so the whole stack runs with one command instead of five terminal tabs plus two
   `docker run`s. Also an opportunity to fold this project's Postgres/RabbitMQ containers and
   port scheme into one `docker-compose.yml` instead of manual `docker run` commands.
5. **CI/CD** — pipeline (GitHub Actions or similar) that builds all modules, runs tests, and
   builds/pushes Docker images on merge.
6. **Monitoring** — Spring Boot Actuator health/metrics endpoints, likely Prometheus + Grafana
   for scraping/visualizing.
