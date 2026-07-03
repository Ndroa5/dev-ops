# devops-microservices

University DevOps project: a 5-service Spring Boot microservices architecture, built as a Maven
multi-module project. REST communication, RabbitMQ messaging, automated tests, and Docker
packaging are all done (see Phase Plan below); no CI/CD or monitoring yet.

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

## Tests (phase 3 — done)

Each of the 4 business services (user/catalog/order/notification) has two layers of automated
tests:

- **Unit tests** (JUnit 5 + Mockito, `service/` package) — service-layer logic in isolation,
  repositories/external clients mocked. No Spring context, no Docker, run in ~1s each.
- **Integration tests** (`@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc` +
  Testcontainers) — hit the real REST controllers backed by a real, throwaway Postgres container.
  `webEnvironment = MOCK` means no real port is bound, so these never conflict with a
  `mvn spring-boot:run` instance already listening on the service's real port.

| Service | Unit test(s) | Integration test | What the integration test proves |
|---|---|---|---|
| user-service | `AuthServiceTest`, `JwtServiceTest` | `UserFlowIntegrationTest` | register → login → JWT issued → `GET /api/users/me` works with it; bad password and missing token are rejected |
| catalog-service | `BookServiceTest` | `BookControllerIntegrationTest` | full CRUD lifecycle through the real controller, including the 409 on over-decrementing stock |
| order-service | `OrderServiceTest` | `OrderControllerIntegrationTest` | order creation persists correctly and calls `RabbitTemplate.convertAndSend(...)` with the right exchange/routing-key/payload |
| notification-service | `NotificationServiceTest` | `NotificationConsumerIntegrationTest` | a **real** message published to a **real** RabbitMQ testcontainer is picked up by `OrderEventListener` and lands as a `NotificationLog` row; manual `POST /api/notifications/send` still works too |

**Why order-service mocks `RabbitTemplate` but notification-service uses a real RabbitMQ
testcontainer**: order-service's integration test job is proving the *publish call* happens with
the right payload (a mocked `RabbitTemplate` + `ArgumentCaptor` is enough, and skips needing a
broker). notification-service's job is proving the *actual consume* works — that only means
something if a real message crosses a real broker into a real `@RabbitListener`. Splitting it this
way instead of standing up RabbitMQ in both avoids double-testing the same transport while still
covering both ends of the pipe.

Run everything:
```
mvn clean install
```
Run a single module's tests:
```
mvn -pl user-service test
```
Testcontainers spins up its own Postgres (and, for notification-service, RabbitMQ) containers per
test class — **the services you run locally via `mvn spring-boot:run` do not need to be running**
for tests to pass, and the reverse is also true (tests don't touch your dev Postgres/RabbitMQ
containers at all, they get their own ephemeral ones).

**Requires Docker to be running** — Testcontainers needs a working Docker daemon to start its
throwaway containers.

**Windows-specific gotcha hit on this machine (2026-07-03), only relevant if `mvn test` fails
immediately with `IllegalStateException: Could not find a valid Docker environment`**: this
machine's Docker Desktop (4.69.0 / Engine 29.4.0) doesn't work with Testcontainers' default
Windows named-pipe auto-detection here — it connects, but gets a stubbed/rejected response instead
of real daemon info. Two things fixed it:
1. Set `DOCKER_HOST=npipe:////./pipe/docker_engine_linux` in the shell before running Maven (the
   default-detected pipe, `docker_cli`, doesn't behave the same as this one — if `docker info`
   works fine from your terminal but Testcontainers still can't find Docker, try this).
2. The parent pom's `maven-surefire-plugin` now sets `-Dapi.version=1.44` via `argLine` — without
   it, docker-java's own default (API 1.32) gets rejected by this Engine build with
   `client version 1.32 is too old. Minimum supported API version is 1.40`. This is baked into the
   pom (harmless on older Docker installs too), so only the `DOCKER_HOST` env var should ever need
   setting by hand.

If tests fail with this error on a different machine/setup, check `docker context ls` for the
active endpoint and adjust `DOCKER_HOST` accordingly — the specific pipe name can differ by Docker
Desktop version.

## Docker (phase 4 — done)

Every service has a multi-stage `Dockerfile` (Maven+JDK build stage → `eclipse-temurin:21-jre-alpine`
runtime stage, non-root `spring` user, only the built jar copied in) and the whole stack runs via
`docker-compose.yml` at the repo root: 5 app services + Postgres (one container, 4 databases via
an init script) + RabbitMQ, on a dedicated bridge network, with health-check-gated startup
ordering.

**Two separate ways to run this project locally — don't mix them up:**

| | Manual dev setup (phases 1-3) | Docker Compose (phase 4) |
|---|---|---|
| How | `mvn spring-boot:run` per service, `docker run` for Postgres/RabbitMQ | `docker compose up -d` |
| Postgres | `devops-postgres` container, port **5433** | `compose-postgres`, port **5434** |
| RabbitMQ | `devops-rabbitmq` container, ports **5673**/**15673** | `compose-rabbitmq`, ports **5674**/**15674** |
| App services | your JVM processes, ports 9080-9084 | containers, **same** ports 9080-9084 |

Infra ports are deliberately different so both setups can have their containers running at the
same time without conflict. App service ports are the same in both because they represent "the
same services" — don't run `mvn spring-boot:run` for a service at the same time as its
Compose container, they'll fight over the port.

### Build & run via Compose

```bash
docker compose build          # builds all 5 images (devops-project/<service>:latest)
docker compose up -d          # starts everything, healthcheck-gated startup order
docker compose ps             # check status — wait for all to show "healthy"
docker compose logs -f order-service   # tail a specific service
docker compose down           # stop everything (add -v to also wipe the postgres/rabbitmq volumes)
```

Smoke test once everything is healthy (same flow as the manual setup, same port 9080):
```bash
curl -X POST http://localhost:9080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}'

curl -X POST http://localhost:9080/api/books -H "Content-Type: application/json" \
  -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441172719","genre":"Sci-Fi","price":15.99,"stockQuantity":10}'

curl -X POST http://localhost:9080/api/orders -H "Content-Type: application/json" \
  -d '{"bookId":1,"quantity":2,"buyerEmail":"alice@example.com"}'

curl http://localhost:9080/api/notifications   # should show the order confirmation
```
Verified working end-to-end on 2026-07-03 entirely through the Compose stack.

### Externalized config

Every value that used to be hardcoded to `localhost` is now `${ENV_VAR:default}` in
`application.yml`, defaulting to the manual-dev-setup values so the *same jar* runs unmodified
locally or in a container:

| Env var | Used by | Local default | Compose value |
|---|---|---|---|
| `DB_HOST` / `DB_PORT` | user/catalog/order/notification | `localhost` / `5433` | `postgres` / `5432` |
| `DB_USERNAME` / `DB_PASSWORD` | same 4 | `postgres` / `postgres` | same |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | order, notification | `localhost` / `5673` | `rabbitmq` / `5672` |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | order, notification | `guest` / `guest` | same |
| `JWT_SECRET` / `JWT_EXPIRATION_MS` | user-service | dev placeholder secret | same (still a placeholder — see note below) |
| `CATALOG_SERVICE_URL` | order-service | `http://localhost:9082` | `http://catalog-service:9082` |
| `USER_SERVICE_URL` / `CATALOG_SERVICE_URL` / `ORDER_SERVICE_URL` / `NOTIFICATION_SERVICE_URL` | api-gateway (route targets) | `http://localhost:908x` | `http://<service-name>:908x` |

**Not done**: real secret management. `JWT_SECRET` and DB/RabbitMQ credentials are still plaintext
defaults/compose environment values, fine for a local university project but not something to
carry into a real deployment — flagging in case a later phase (or grading rubric) expects
Docker/Kubernetes secrets instead.

### Three real bugs Compose caught that local dev hadn't

1. **Executable jars weren't actually executable.** `spring-boot-maven-plugin` was declared in
   every pom but never bound to the `repackage` goal (that binding normally comes for free from
   `spring-boot-starter-parent`, which this project doesn't extend). `mvn package` was silently
   producing a plain, non-runnable jar the whole time — invisible locally because
   `mvn spring-boot:run` doesn't need the packaged jar at all, only surfaced once Docker tried
   `java -jar app.jar` and got `no main manifest attribute`. Fixed by adding an `<executions>`
   block binding `repackage` in the parent pom's `pluginManagement` (applies to all 5 modules).
2. **user-service's `/actuator/health` returned 403.** Its Spring Security config only permits
   `/api/auth/**`; everything else — including actuator — required a JWT. Fixed by adding
   `/actuator/health` and `/actuator/health/**` to the permitted paths in `SecurityConfig`.
3. **RabbitMQ container failing with `Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces`.**
   A first-boot race in the official image's entrypoint (on this machine's Docker
   Desktop/Windows/WSL2) can leave that file owned `root:root` mode `400`, unreadable by the
   `rabbitmq` user on every subsequent start. Fixed by setting `RABBITMQ_ERLANG_COOKIE` explicitly
   in compose (skips that file) — if you still hit this on a fresh volume, delete the stale file
   per the comment in `docker-compose.yml`.

Also tuned: healthcheck `start_period` for the Spring Boot services is `90s`, not the more typical
`30s` — cold JVM boot under 5 containers starting/competing for CPU simultaneously was observed
taking ~60s for a single service.

## Phase plan

1. **REST communication** — done. Services scaffolded, order-service ↔ catalog-service wired via
   REST (`RestClient`), gateway routes all traffic.
2. **RabbitMQ** — done (2026-07-03). See "RabbitMQ messaging" section above for exchange/queue
   names, payload shape, and the buyer-identity scope decision (client-supplied `buyerEmail`, no
   JWT validation in order-service).
3. **Tests** — done (2026-07-03). See "Tests" section above for the unit/integration split per
   service and the Windows Docker/Testcontainers gotcha.
4. **Docker** — done (2026-07-03). See "Docker" section above for the Dockerfile pattern, Compose
   topology, port-mapping distinction from the manual dev setup, and the three real bugs it caught.
5. **CI/CD** *(next)* — pipeline (GitHub Actions or similar) that builds all modules, runs tests,
   and builds/pushes Docker images on merge.
6. **Monitoring** — Spring Boot Actuator health/metrics endpoints (already added for health checks
   in phase 4), likely Prometheus + Grafana for scraping/visualizing.
