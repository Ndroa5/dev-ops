# devops-microservices

[![CI](https://github.com/Ndroa5/dev-ops/actions/workflows/ci.yml/badge.svg)](https://github.com/Ndroa5/dev-ops/actions/workflows/ci.yml)

University DevOps course project: a 5-service Spring Boot microservices architecture
(api-gateway, user-service, catalog-service, order-service, notification-service) built as a
Maven multi-module project, with RabbitMQ messaging between order-service and
notification-service, automated tests (JUnit 5 + Mockito + Testcontainers), and Docker packaging
(Dockerfile per service + a `docker-compose.yml` for the whole stack).

See [CLAUDE.md](CLAUDE.md) for the full architecture overview, port mappings, build/run
instructions (both the manual dev setup and Docker Compose), and the phase-by-phase project log.

## Quick start

```bash
# Build everything and run the full test suite
mvn clean install

# Or run the whole stack in Docker
docker compose build
docker compose up -d
```

## Git workflow

Feature branches → pull request into `main` → CI runs automatically → merge once green. See
CLAUDE.md's "CI/CD" section for details.
