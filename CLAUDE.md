# devops-microservices

University DevOps project: a 5-service Spring Boot microservices architecture, built as a Maven
multi-module project. REST communication, RabbitMQ messaging, automated tests, Docker packaging,
CI/CD, monitoring (logs, metrics, and traces), and static analysis (SonarCloud) are all done —
every phase in the spec.

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

### End-to-end test (`e2e-tests` module)

The unit/integration tests above each cover **one service in isolation** (integration tests use a
real Postgres/RabbitMQ Testcontainer, but drive the service's own controller directly via MockMvc
— they never make a real HTTP call to another live service). There was no automated test proving
the **whole chain** works together across independently running containers, only a one-off manual
`curl` walkthrough documented in this file. Added a 6th Maven module, `e2e-tests`, specifically to
close that gap:

- **`e2e-tests/docker-compose.e2e.yml`** — a dedicated compose file (all 5 services + Postgres +
  RabbitMQ, no observability stack) on its own port range (services **9180-9184**, Postgres
  **5436**, RabbitMQ **5676**/**15676**) so it never collides with the manual dev setup, the
  regular `docker-compose.yml` dev stack, or `docker-compose.prod.yml` — all of which can be
  running at the same time. No `container_name` is set on any service, so Testcontainers can
  freely assign unique per-run container names instead of colliding with the dev stack's fixed
  ones (e.g. plain `user-service`).
- **`FullOrderFlowE2ETest`** — brings that compose file up via Testcontainers'
  `ComposeContainer`, waits for `api-gateway`'s Docker healthcheck, then drives the real flow over
  plain HTTP through the gateway: register → create book → create order → assert stock decremented
  on catalog-service → poll `/api/notifications` (RabbitMQ + RxJava are async, so this uses
  Awaitility) until the confirmation lands. This is the one test that proves REST, RabbitMQ, and
  the RxJava pipeline all actually work together end-to-end, not just within a single service's
  Spring context.
- Runs automatically as part of `mvn clean install` like every other module's tests — no separate
  CI wiring needed, since it's just another module in the reactor.

**Every one of the 5 Dockerfiles needed a one-line fix for this to build at all**: each Dockerfile
copies only the specific sibling `pom.xml` files it needs (for Docker layer caching), and Maven
validates the *entire* module list declared in the parent `pom.xml` before honoring `-pl <service>
-am`, even though the build only targets one module. Adding `e2e-tests` to the parent's
`<modules>` without also adding `COPY e2e-tests/pom.xml e2e-tests/pom.xml` to all 5 Dockerfiles
broke every single service image build (`Child module /build/e2e-tests of /build/pom.xml does not
exist`). Fixed by adding that one COPY line to each Dockerfile, mirroring the existing pattern
where every Dockerfile already lists all of its siblings' poms for exactly this reason.

**Healthcheck margins were widened for this compose file specifically** (`retries: 20` instead of
the usual `10` on all 5 app services, ~290s max instead of ~190s): a first attempt at running this
test *while the regular dev `docker-compose.yml` stack (12 containers) was also running* caused
`order-service` to blow past the original budget under real resource contention and get killed as
unhealthy, even though the same compose file came up perfectly healthy standalone. Since this test
needs to pass reliably on shared/variable-load CI runners too, not just an idle dev machine, the
margin was widened rather than just re-running on a quieter machine.

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

## CI (phase 5 — done)

`.github/workflows/ci.yml` — two jobs:

1. **`build-and-test`** — checks out the repo, sets up JDK 21 (Temurin), caches `~/.m2` via
   `actions/setup-java`'s built-in maven cache, then runs `mvn --batch-mode clean install` for
   the whole multi-module reactor (build + all unit and integration tests). A test failure fails
   the job, which blocks `docker-build` from running. Uploads every service's built jar
   (`*/target/*.jar`) as a workflow artifact (`service-jars`, 7-day retention) so they're
   downloadable without re-running the build — `if: always()` so jars from a partially-successful
   build are still uploaded for inspection even if a later module failed.
2. **`docker-build`** — matrix over all 5 services, `needs: build-and-test` (only runs if tests
   passed). Runs `docker build -f <service>/Dockerfile -t devops-project/<service>:ci .` for each,
   confirming every Dockerfile still builds cleanly. **Build only, no push** — pushing to a
   registry is the CD phase, not this one.

**`build-and-test` now also builds all 5 Docker images a second time, via the `e2e-tests` module**:
since `mvn clean install` runs every module's tests including `FullOrderFlowE2ETest`, which brings
up `docker-compose.e2e.yml` (all 5 services built from their Dockerfiles) as part of the test
itself, `build-and-test` ends up building every image once here *and* again in the separate
`docker-build` job below. This is deliberate duplication, not an oversight — `docker-build`'s job is
proving the committed Dockerfiles build cleanly in isolation (its whole purpose), while
`e2e-tests`'s build is just a side effect of needing real containers to test against. Removing
either would either lose Dockerfile-build verification or lose the end-to-end test; adds a few
extra minutes to `build-and-test` but was judged worth it for genuine cross-service test coverage.

**Triggers**: every push to any branch, and every pull request targeting `main`. This means a
feature branch gets CI feedback on every push (not just when a PR is opened), and a PR gets a
second, identical run against the merge commit.

**Testcontainers on GitHub-hosted runners just works, no workaround needed**: ubuntu-latest
runners ship Docker preinstalled and reachable on the standard unix socket, so Testcontainers'
default auto-detection finds it with no `DOCKER_HOST` override — unlike the Windows/Docker
Desktop pipe workaround documented in the Tests section above, which is purely a local-dev-machine
quirk and isn't referenced anywhere in committed config (the surefire `-Dapi.version=1.44` argLine
is harmless/generic and works fine on Linux too, since it just forces a modern API version
request that any reasonably current Docker Engine accepts).

## CD (phase 5 — done)

`.github/workflows/cd.yml` — publishes images to Docker Hub. Two jobs:

1. **`build-and-push`** — matrix over all 5 services, on a regular `ubuntu-latest` GitHub-hosted
   runner. Logs into Docker Hub (`docker/login-action`, using the
   `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` repo secrets), builds each service's image from its
   existing `Dockerfile`, tags it two ways, and pushes both tags.
2. **`post-deploy-health-check`** — `needs: build-and-push` (only runs once every image is
   pushed). Pulls the just-published images via `docker-compose.prod.yml` and brings up the whole
   stack (`docker compose up -d --wait`), then explicitly curls each of the 5 services'
   `/actuator/health` endpoint and fails the job if any isn't `UP`.

**This job runs on a self-hosted runner (a Windows service on the dev machine, `runs-on:
self-hosted`), not `ubuntu-latest`, and deliberately does not tear the stack down afterward.** A
GitHub-hosted runner is destroyed the instant its job ends, so anything "deployed" there vanishes
immediately — the previous version of this job proved the images build and boot, then discarded
that proof by tearing the stack down in an `if: always()` step. Moving it to a self-hosted runner
and dropping the teardown step is what makes "automatski deploy nove verzije aplikacije" (spec item
9) actually true: a merge to `main` now leaves `docker-compose.prod.yml` running and updated on
this machine, not just health-checked and discarded. This is the **local** deployment path the
spec explicitly allows as an equal alternative to cloud (item 8) — chosen over a real cloud target
(Render/Railway/Fly.io) specifically because it reuses infrastructure this project already has
(this machine, already running Docker for everything else) instead of introducing a new external
dependency with its own account/free-tier/uptime concerns.

**Verified end-to-end on 2026-07-07**: merged a real PR, watched `build-and-push` publish all 5
images, watched `post-deploy-health-check` pull them and bring up `docker-compose.prod.yml` on this
machine, confirmed all 5 health checks passed, then — critically — confirmed the containers were
still `Up ... (healthy)` *after* the workflow run completed (not torn down), and ran a full
register → create book → create order → notification smoke test directly against the CD-deployed
stack on port 9080. All of it worked.

### Setting up the self-hosted runner (one-time, per machine)

1. GitHub repo → Settings → Actions → Runners → New self-hosted runner → Windows/x64. Follow the
   displayed download/config commands (they include a short-lived registration token).
2. Install as a persistent Windows service so it survives reboots and doesn't need a terminal open:
   `./config.cmd --url <repo-url> --token <token> --runasservice` **run from an elevated
   (Administrator) PowerShell** — the service-install step silently fails with just a warning
   ("Needs Administrator privileges...") if you're not elevated, while the GitHub-side registration
   still succeeds, which is a confusing partial-success state if you don't notice the warning.
3. **Security tradeoff, stated plainly**: a self-hosted runner executes arbitrary code from every
   future workflow run on this repo, automatically, on this machine. GitHub explicitly warns
   against this for public repos with external contributors, since a malicious PR's workflow code
   would run here. Acceptable for this project specifically because it's a solo repo with no
   external collaborators — revisit if that ever changes.

### Four real, non-obvious problems hit getting the runner to actually work

Each of these produced a passing `build-and-push` (that job runs on `ubuntu-latest`, unaffected)
followed by a failing `post-deploy-health-check` (the new self-hosted job) — four separate
merge-and-observe-the-failure cycles before the deploy step actually worked:

1. **`shell: bash` silently resolved to WSL, not Git Bash.** On Windows, the bare `bash` shell
   keyword resolved to `C:\Windows\System32\bash.exe` (the WSL launcher stub) instead of Git Bash,
   failing immediately with "Windows Subsystem for Linux has no installed distributions" — this
   machine has Git Bash but no WSL distro installed. First fix attempt: point `shell:` at Git
   Bash's actual `.exe` path directly.
2. **Explicit shell path also failed, for a different reason.** `shell: 'C:\Program
   Files\Git\usr\bin\bash.exe -e {0}'` failed with `Second path fragment must not be a drive or UNC
   name` — the runner's custom-shell command parsing splits the shell string on whitespace to
   separate the executable from its arguments, and "Program Files" has a space in it, so the path
   itself got torn in half and mis-tokenized. Fixed by abandoning bash entirely for these steps and
   rewriting them in native PowerShell (`shell: powershell`), which is the Windows runner's actual
   default and has no such ambiguity.
3. **PowerShell scripts were blocked outright by the machine's execution policy.** Every `run:`
   step on a Windows runner is written to a temporary `.ps1` file and dot-sourced; with this
   machine's `LocalMachine`-scope execution policy left at its out-of-the-box `Undefined` (which
   behaves as `Restricted`), that dot-sourcing failed with "running scripts is disabled on this
   system" for *any* PowerShell step, regardless of what the script contained. Fixed with
   `Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope LocalMachine` (elevated) — `RemoteSigned`
   is the standard baseline for a dev machine: local scripts run freely, downloaded ones need a
   signature.
4. **The runner service account had no Docker permission.** Once scripts could actually run, `docker
   compose pull` failed with `permission denied while trying to connect to the docker API at
   npipe:////./pipe/docker_engine`. The runner's Windows service logs on as `NT AUTHORITY\NETWORK
   SERVICE` by default, and Docker Desktop on Windows only grants named-pipe access to members of
   the local `docker-users` group — which, before this, only had the interactive user account in
   it. Fixed with `net localgroup docker-users "NT AUTHORITY\NETWORK SERVICE" /add` (elevated),
   followed by restarting the runner service so its process token actually picks up the new group
   membership (adding the group alone, without a restart, isn't enough — the already-running
   service process keeps its original token until restarted).

**Trigger — merge only, not every push**: `on: pull_request: types: [closed], branches: [main]`
with `if: github.event.pull_request.merged == true` at the job level. A PR that's closed *without*
merging does not trigger this — only an actual merge into `main` does. This is deliberately
different from CI's "every push" trigger: CD publishes real artifacts to a real registry, so it
should only fire on the one event that means "this is now what's on main."

**Why `github.event.pull_request.merge_commit_sha`, not `github.sha`**: for a `pull_request`
event, `github.sha`/the default checkout ref point at the last commit on the PR's *head* branch,
not necessarily the actual merge commit that landed on `main` (these can differ depending on merge
strategy). Both jobs explicitly check out and tag from
`github.event.pull_request.merge_commit_sha` so what gets built, tagged, and health-checked is
exactly what's now on `main`.

**Image naming & tagging convention**: `<dockerhub-username>/devops-<service-name>`, tagged both
`:latest` and `:<short-sha>` (first 7 chars of the merge commit SHA):
```
<dockerhub-username>/devops-api-gateway:latest
<dockerhub-username>/devops-api-gateway:a1b2c3d
<dockerhub-username>/devops-user-service:latest
<dockerhub-username>/devops-user-service:a1b2c3d
... (same pattern × catalog-service, order-service, notification-service)
```
The `:latest` tag is "whatever main currently is"; the short-SHA tag is for traceability — you can
always point at exactly the commit a given running container was built from.

**`docker-compose.prod.yml`** (repo root) — same postgres/rabbitmq/network/volume shape as
`docker-compose.yml`, but the 5 app services use `image: ${DOCKERHUB_USERNAME}/devops-<service>:${TAG:-latest}`
instead of `build:`. This is the actual "deploy" story for running the published images anywhere,
including for the university defense — pull and run locally:
```bash
export DOCKERHUB_USERNAME=<your-dockerhub-username>
# TAG defaults to "latest" if unset; set it to a short SHA to pin an exact build
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps    # wait for all "healthy"
```
Distinct infra ports from both the manual dev setup and the build-from-source `docker-compose.yml`
(Postgres **5435**, RabbitMQ **5675**/**15675**), but **app service ports (9080-9084) are the same
across all three setups** since they represent "the same services" — this means this stack and
the build-from-source `docker-compose.yml` dev stack cannot both be running at once. Since CD now
keeps this stack running persistently on this machine (see above), the build-from-source dev stack
should be brought down (`docker compose down`, no `-v` needed) before merging anything, rather than
left running alongside it. Tear down with `docker compose -f docker-compose.prod.yml down -v` if
you need to stop the CD-managed deployment itself.

**Not done**: no rollback mechanism, no blue/green or canary deploy. "Deploy" is now a real,
persistent local deployment (see the self-hosted runner section above) rather than a real remote
server — still within the spec's explicitly-permitted "local" alternative to cloud, not a gap.

## Monitoring (phase 6 — done): logs, metrics, and traces

The spec requires all three pillars, not just metrics — this section maps directly to that
requirement for the defense: **metrics** (Micrometer → Prometheus → Grafana), **traces**
(Micrometer Tracing/Brave → Zipkin), **logs** (container stdout → Promtail → Loki → Grafana).
Everything here lives only in `docker-compose.yml` (local dev stack) — deliberately **not** in
`docker-compose.prod.yml`.

### Access

| Tool | URL | Credentials |
|---|---|---|
| Grafana | http://localhost:3001 | admin / admin |
| Prometheus | http://localhost:9091 | none |
| Zipkin | http://localhost:9412 | none |
| Loki | http://localhost:3100 (API only, no UI — use Grafana) | none |

Ports are shifted from each tool's usual default (Grafana 3000→3001, Prometheus 9090→9091, Zipkin
9411→9412) because this machine's unrelated "cinebook" stack already occupies the defaults — see
the port-collision note earlier in this file. Loki's port (3100) wasn't contested, so it kept its
default.

### Metrics — Micrometer → Prometheus → Grafana

All 5 services depend on `micrometer-registry-prometheus` and expose `/actuator/prometheus`
(`management.endpoints.web.exposure.include: health,prometheus` — user-service's `SecurityConfig`
additionally permits `/actuator/prometheus` without a JWT, same reasoning as the `/actuator/health`
permit from the Docker phase). Every service also sets `management.metrics.tags.application:
${spring.application.name}`, so Prometheus/Grafana queries can group by service.

Prometheus (`docker/prometheus/prometheus.yml`) scrapes all 5 on a 10s interval via their
container DNS names. Grafana is provisioned (not clicked together) with Prometheus as a datasource
and a dashboard, **DevOps Microservices Overview**, showing:
- **Request rate per service** — `sum by (application) (rate(http_server_requests_seconds_count[1m]))`
- **Error rate (4xx/5xx) per service** — same, filtered to `status=~"4..|5.."`
- **p50/p95 latency per service** — `histogram_quantile(...)` over `http_server_requests_seconds_bucket`
  (needs `management.metrics.distribution.percentiles-histogram.http.server.requests: true`, set on
  every service)

**Known scope limit**: api-gateway is Spring Cloud Gateway (WebFlux), and its *proxied* routes
don't produce `http_server_requests_seconds` — that metric only covers its own `/actuator/**` and
`/gateway/status` endpoints. Proxied-route timing lives under a different metric name,
`spring_cloud_gateway_requests_seconds`. The dashboard as built covers the 4 business services'
real MVC traffic correctly; extending it to gateway-specific proxied-route metrics would need its
own panel using that metric name — flagging as a known gap rather than silently mismatching what
"per service" shows for the gateway.

### Traces — Micrometer Tracing (Brave) → Zipkin

All 5 services depend on `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave`,
`management.tracing.sampling.probability: 1.0` (100% — fine for a university project, would never
do this in production), and `management.zipkin.tracing.endpoint` pointing at the `zipkin`
container.

**The RabbitMQ trace-propagation question (this needed real investigation, not a guess)**: Spring
AMQP does **not** enable Micrometer observation by default on either `RabbitTemplate` or
`@RabbitListener` containers — confirmed against Spring AMQP's own reference docs and a
still-open spring-projects/spring-amqp issue ("observationEnabled currently defaults to false").
Without enabling it explicitly, order-service's publish and notification-service's consume would
each start their own disconnected trace instead of one continuing the other. Fixed with two
properties:
```yaml
# order-service (producer)
spring.rabbitmq.template.observation-enabled: true
# notification-service (consumer)
spring.rabbitmq.listener.simple.observation-enabled: true
```
Once both are set, trace context propagates through the message headers automatically — no extra
beans needed, Micrometer Tracing's Brave propagator handles it.

**A second, related bug found and fixed while verifying this**: order-service's `RestClient` bean
(used to call catalog-service) was built via the static `RestClient.builder()` factory method
instead of injecting Spring Boot's auto-configured `RestClient.Builder` bean — the auto-configured
builder is what Spring Boot wires observation/tracing instrumentation onto, so the static factory
call meant calls to catalog-service carried no trace context and never joined the caller's trace
at all (confirmed missing from Zipkin before the fix). Switching to the injected builder fixed
that, but surfaced a **third**, well-known gotcha: Spring Boot's auto-configured builder defaults
to `SimpleClientHttpRequestFactory` (`java.net.HttpURLConnection`), which cannot send PATCH
requests (`ProtocolException: Invalid HTTP method: PATCH`) — and order-service's stock-decrement
call is a PATCH. Fixed by explicitly setting `.requestFactory(new JdkClientHttpRequestFactory())`
on the builder in `RestClientConfig`, which keeps the tracing instrumentation from the injected
builder while actually supporting PATCH. See the comments in `order-service/.../RestClientConfig.java`.

**Verified end-to-end on 2026-07-03**: a single `POST /api/orders` through the gateway produced
one 9-span trace in Zipkin, all sharing one trace ID: api-gateway → order-service →
(catalog-service GET, catalog-service PATCH) → order-service → RabbitMQ → notification-service.
Every hop — sync HTTP and async messaging — correctly joined the same trace.

Zipkin is also added as a Grafana datasource (`type: zipkin`, Grafana's built-in core plugin,
Grafana ≥ 12.3.0) so metrics, traces, and logs are all reachable from one Grafana instance even
though traces aren't on the dashboard itself — Zipkin's own UI (http://localhost:9412) is the
better place to explore a specific trace's waterfall view.

### Logs — Promtail → Loki → Grafana

Promtail discovers containers via `docker_sd_configs` against the Docker socket, **filtered to
only containers labeled `devops.logging=true`** (set on all 5 app services in
`docker-compose.yml`) — this machine runs other unrelated Docker projects side by side, and
without this filter Promtail would ship every container's logs on the host into this stack's
Loki, not just ours. Each service also gets a `devops.service` label, relabeled into a
`service_name` Loki label for filtering.

Chose Promtail (a container in the compose stack) over the Docker Loki logging driver
specifically because the logging driver requires a one-time `docker plugin install` on the host
*outside* of docker-compose — Promtail is fully self-contained within `docker-compose.yml`, matching
this phase's "everything in docker-compose.yml" constraint.

The Grafana dashboard's **Logs** panel queries `{service_name=~"$service"}`, where `$service` is a
dashboard template variable populated from Loki's own label values (defaults to "All" via a
multi-select) — this is the "search/filter logs by service name" requirement. Query Loki directly
too if useful: `curl http://localhost:3100/loki/api/v1/label/service_name/values`.

**Verified end-to-end on 2026-07-03**: queried Loki directly for `{service_name="order-service"}`
and `{service_name="notification-service"}` after generating traffic — both returned real log
lines, including Spring Boot's default logging pattern's embedded `[traceId-spanId]` (a nice bonus:
trace ↔ log correlation works for free once tracing is wired up, since Micrometer Tracing adds
that to the MDC/log pattern automatically).

## Static analysis (SonarCloud — done)

Satisfies the spec's "static analysis integrated into CI" requirement. Runs as an extra step in
the **existing** `build-and-test` job in `.github/workflows/ci.yml` — no separate workflow file,
since it needs the same `mvn clean install` that job already does (compiled classes, not just
source, are required for accurate analysis) and duplicating that build for its own workflow would
just double the CI time for no benefit.

**View results**: https://sonarcloud.io/project/overview?id=Ndroa5_dev-ops (organization `ndroa5`,
project key `Ndroa5_dev-ops`).

**How it's wired in**:
- Parent `pom.xml` sets `<sonar.organization>ndroa5</sonar.organization>` in `<properties>`.
- After the existing "Build and test all modules" step, a new step runs
  `mvn --batch-mode org.sonarsource.scanner.maven:sonar-maven-plugin:sonar
  -Dsonar.projectKey=Ndroa5_dev-ops`, authenticated via the `SONAR_TOKEN` repo secret (env var,
  not a CLI arg — keeps it out of process listings/logs).
- `~/.sonar/cache` is cached the same way `actions/setup-java` already caches `~/.m2` (a
  dedicated `actions/cache@v4` step, since `setup-java`'s built-in cache only covers the Maven
  local repo).
- The checkout step for this job now uses `fetch-depth: 0` (full git history, not the default
  shallow clone) — SonarCloud needs this for accurate blame / new-code-period analysis, not
  something that mattered before Sonar was in the picture.

**Multi-module aggregation — no extra per-module config needed**: running `sonar:sonar` once at
the reactor root, after the whole reactor is built, is the standard supported way to analyze a
Maven multi-module project — the plugin walks the parent's `<modules>` list automatically and
aggregates all 5 services into one SonarCloud project. This "just works" here specifically because
every module follows the standard Maven layout (`src/main/java`, `src/test/java`) with nothing
unusual to point Sonar at — no per-module `sonar.sources`/`sonar.tests` overrides were needed.

**Verified 2026-07-05**: confirmed via the SonarCloud API (not just that the CI job went green)
that an analysis actually landed for this project, covering all 5 modules.

## Git workflow (going forward)

Starting from the CI phase: **feature branch → pull request into `main` → CI runs automatically →
merge once green.** Don't commit directly to `main`.

```bash
git checkout main && git pull
git checkout -b feature/my-change
# ... commit work ...
git push -u origin feature/my-change
gh pr create --base main   # or open the PR on github.com
```
CI runs on the branch push and again on the PR. Once `build-and-test` and all 5 `docker-build`
matrix jobs are green, merge the PR (don't force-merge on a red pipeline).

Phases 1-4 (scaffold through Docker) were committed straight to `main` as the initial baseline
before this workflow started — see the phase plan below for what each of those commits covers.

## Phase plan

1. **REST communication** — done. Services scaffolded, order-service ↔ catalog-service wired via
   REST (`RestClient`), gateway routes all traffic.
2. **RabbitMQ** — done (2026-07-03). See "RabbitMQ messaging" section above for exchange/queue
   names, payload shape, and the buyer-identity scope decision (client-supplied `buyerEmail`, no
   JWT validation in order-service).
3. **Tests** — done (2026-07-03), extended (2026-07-06). See "Tests" section above for the
   unit/integration split per service, the Windows Docker/Testcontainers gotcha, and the later
   addition of a real cross-service end-to-end test (`e2e-tests` module) that drives the whole
   order flow through live containers rather than a single service's Spring context.
4. **Docker** — done (2026-07-03). See "Docker" section above for the Dockerfile pattern, Compose
   topology, port-mapping distinction from the manual dev setup, and the three real bugs it caught.
5. **CI/CD** — done (2026-07-03), CD extended to a real persistent deploy (2026-07-07). See "CI"
   and "CD" sections above — CI builds/tests/build-only Docker images on every push and PR; CD
   publishes tagged images to Docker Hub, then (via a self-hosted runner on the dev machine) pulls
   and runs them via `docker-compose.prod.yml`, leaving the stack actually running and verified
   healthy instead of tearing it down after the check.
6. **Monitoring** — done (2026-07-03). See "Monitoring" section above — logs (Promtail/Loki),
   metrics (Prometheus/Grafana), and traces (Zipkin) all wired up and verified end-to-end,
   including two real bugs found while confirming trace propagation (RabbitMQ observation not
   enabled by default, and a PATCH-breaking RestClient request factory regression).
7. **Static analysis** — done (2026-07-05). See "Static analysis" section above — SonarCloud
   analysis runs as part of the existing CI `build-and-test` job, aggregating all 5 modules into
   one project. This was the last phase in the spec.
