# CYBERGROUP IAM Backend (W1)

Spring Boot 3 + Java 21 demo backend. Phase A scaffold — auth (JWT validation, RBAC) lands in W2/W3/W4.

## Prerequisites

- Java 21 (Temurin or any JDK 21)
- Maven 3.9+

## Run locally

```
cd backend
mvn spring-boot:run
```

Server listens on `http://localhost:8081` (port 8081 to avoid clashing with Keycloak on 8080).

## Endpoints

| Path | What |
|---|---|
| `GET /api/public` | Unauthenticated probe |
| `GET /api/private` | Placeholder (will require JWT in W3) |
| `GET /actuator/health` | Liveness/readiness |
| `GET /actuator/prometheus` | Metrics scrape (used by O3) |
| `GET /swagger-ui.html` | OpenAPI UI |
| `GET /v3/api-docs` | OpenAPI JSON |

## Test

```
mvn test
```

## Build

```
mvn clean package
java -jar target/iam-backend-0.1.0-SNAPSHOT.jar
```

## Conventions

- OpenAPI annotations on every endpoint (springdoc-openapi)
- One test per controller minimum (JUnit 5 + MockMvc)
- OWASP Top 10 awareness applied to validation, error handling, and (in W2/W3/W4) auth

## Roadmap inside `/backend`

| Issue | What | Phase |
|---|---|---|
| W1 #25 | This scaffold | A |
| W2 #14 | OAuth2 resource server config | C |
| W3 #18 | JWT validation on every endpoint | C |
| W4 #26 | Per-role authorization (`@PreAuthorize`) | C |
| W5 #27 | Role/access matrix doc + 5 demo accounts | E |
| B1 #60 | Service-account / M2M client | unassigned |
| B2 #61 | Audit logging in JSON → Loki | unassigned |
| E4 #59 | Testcontainers Keycloak integration tests | unassigned |
