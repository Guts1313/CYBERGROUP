# CYBERGROUP IAM Backend (W1–W4)

Spring Boot 3 + Java 21 demo backend. Phase A scaffold + Phase C auth integration done. Wires to the Keycloak realm from `/keycloak`.

## Prerequisites

- Java 21 (Temurin or any JDK 21)
- Maven 3.9+
- Keycloak running locally (`cd keycloak && docker compose up -d`) so the backend can fetch the JWK set on startup

## Run locally

```
cd backend
mvn spring-boot:run
```

Server listens on `http://localhost:8081` (port 8081 to avoid clashing with Keycloak on 8080).

To point at a non-default Keycloak (e.g. once it migrates to the IdP VLAN):

```
KEYCLOAK_ISSUER_URI=https://keycloak.idp.local/realms/cybergroup mvn spring-boot:run
```

## Endpoints

| Path | Auth | Notes |
|---|---|---|
| `GET /api/public` | none | Unauthenticated probe |
| `GET /api/private` | any authenticated user | Echoes JWT claims |
| `GET /api/private/admin` | role `admin` | |
| `GET /api/private/it` | roles `it_manager`, `admin` | |
| `GET /api/private/hr` | roles `hr_manager`, `admin` | |
| `GET /api/private/dev` | roles `developer`, `it_manager`, `admin` | |
| `GET /actuator/health/**` | none | |
| `GET /actuator/prometheus` | none | Used by O3 |
| `GET /swagger-ui.html` | none | OpenAPI UI |

## Trying it end-to-end

After both `/keycloak` and `/backend` are running:

```bash
# 1. Get a token for a demo user (resource owner password grant — for testing only;
#    real clients use Auth Code + PKCE).
TOKEN=$(curl -s -X POST \
  http://localhost:8080/realms/cybergroup/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=iam-frontend \
  -d username=admin-demo \
  -d password=AdminDemo123!Init \
  | jq -r .access_token)

# Note: this only works after admin-demo has completed TOTP enrolment via the
# Keycloak account console at http://localhost:8080/realms/cybergroup/account/

# 2. Call protected endpoints
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/private
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/private/admin
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/private/dev
```

## Test

```
mvn test       # fast — unit + slice tests only
mvn verify     # full — also spins up real Keycloak via Testcontainers
```

`mvn verify` requires a running Docker daemon (Testcontainers spawns the Keycloak container). On Windows, Docker Desktop must be running.

Tests cover:
- **Unit / slice** — `KeycloakRealmRoleConverter` (claim extraction edge cases), per-endpoint authorization (admin/it/hr/dev) using `spring-security-test`'s mock JWT post-processor (positive + negative per role), anonymous → 401, public → 200.
- **Integration (E4 #59)** — `RbacIntegrationTest` extends `KeycloakIntegrationTestBase`, which spins a real Keycloak 26 container via Testcontainers (singleton across the JVM), imports a test variant of the realm (`src/test/resources/cybergroup-realm-test.json` — DAG enabled, no MFA), and exercises every cell of the W5 access matrix through real JWTs. Plus garbage-token and tampered-signature tests.

## Build

```
mvn clean package
java -jar target/iam-backend-0.1.0-SNAPSHOT.jar
```

## Conventions

- OpenAPI annotations on every endpoint (springdoc-openapi)
- Tests for every new feature (JUnit 5 + MockMvc + spring-security-test)
- OWASP Top 10 awareness applied throughout
- CSRF disabled (stateless API; tokens via `Authorization` header)
- CORS allow-list locked to `http://localhost:5173` for the SPA

## Roadmap inside `/backend`

| Issue | What | Phase |
|---|---|---|
| W1 #25 | Scaffold | A ✓ |
| W2 #14 | OAuth2 resource server config | C ✓ |
| W3 #18 | JWT validation on every endpoint | C ✓ |
| W4 #26 | Per-role authorization (`@PreAuthorize`) | C ✓ |
| W5 #27 | Role/access matrix doc + 5 demo accounts | E |
| B1 #60 | Service-account / M2M client | unassigned |
| B2 #61 | Audit logging in JSON → Loki | unassigned |
| E4 #59 | Testcontainers Keycloak integration tests | E ✓ |
