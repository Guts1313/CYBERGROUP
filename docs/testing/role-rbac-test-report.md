# Role / RBAC test plan + report (M4 #30)

End-to-end test of the RBAC + JWT enforcement that ties together everything from Phase B + C: Keycloak issues a token with `realm_access.roles`, the backend's `KeycloakRealmRoleConverter` maps that into Spring authorities, and `@PreAuthorize` on each endpoint allows or denies based on role. Test runner and helpers live under `docs/testing/scripts/`.

## What this validates

| Concern | Expectation | Covered by |
|---|---|---|
| Public endpoints accept unauthenticated requests | `200` with no token on `/api/public` | Test 1 |
| Protected endpoints reject missing tokens | `401` with no `Authorization` header | Test 2 |
| Protected endpoints reject invalid tokens | `401` with a garbage Bearer string | Test 3 |
| Per-role authorization matches the W5 access matrix | 5 roles × 5 protected endpoints = 25 cells, each returning `200` or `403` per the matrix | Test 4 |

## How to run

Prerequisites: Docker (compose v2), JDK 21+ on the host or in a container, the realm running per `keycloak/README.md`.

```
# 1. Stand up Keycloak (Phase A+B)
cd keycloak
cp .env.example .env   # set strong passwords first
docker compose up -d

# 2. Build + run the backend (Phase A+C). Either:
#    a) Local JDK 21:
cd ../backend
KEYCLOAK_ISSUER_URI=http://localhost:8080/realms/cybergroup mvn spring-boot:run
#    b) Docker maven for the build, then run with any JDK 21 on the host:
docker run --rm -v "$PWD/backend":/app -w /app -v "$HOME/.m2":/root/.m2 \
    maven:3.9-eclipse-temurin-21 mvn -B package -DskipTests
KEYCLOAK_ISSUER_URI=http://localhost:8080/realms/cybergroup \
    java -jar backend/target/iam-backend-0.1.0-SNAPSHOT.jar

# 3. Prep realm for headless testing (one-off per fresh stack)
bash docs/testing/scripts/test-prep.sh

# 4. Run the test suite
bash docs/testing/scripts/run-rbac-tests.sh

# 5. Reset realm to production-like config
bash docs/testing/scripts/test-teardown.sh
```

Re-running the suite without re-prep is fine; the test prep is idempotent.

## What the prep + teardown do

The realm-import ships with **MFA required for every demo user** (`requiredActions: ["CONFIGURE_TOTP"]`) and the `iam-frontend` client has **directAccessGrantsEnabled: false**. That's the production-like configuration — but it makes purely-headless testing impossible without scripting an interactive TOTP enrolment.

**`test-prep.sh`** flips both to a test-friendly state:
- enables `directAccessGrantsEnabled` on `iam-frontend` (so `grant_type=password` works in curl)
- clears `requiredActions=[]` on every demo user (so they don't get blocked on first login asking for TOTP)

**`test-teardown.sh`** restores both to production-like state. Run after the test pass.

> ⚠ The prep makes the realm less secure on purpose, for testing only. Never leave a system in the prepped state in front of real users. The K3 production config (TOTP required for all roles) is still the source of truth in `realm-import/cybergroup-realm.json` and re-imports on a fresh Postgres volume.

## Latest execution result

Run on **2026-05-14** against backend commit on `main` (Phase A + B + C + E part 1 merged).

```
==========================================
TEST 1: public endpoint reachable anonymously
==========================================
  PASS  GET /api/public (no auth)  (expected=200, actual=200)

==========================================
TEST 2: protected endpoint rejects no-token
==========================================
  PASS  GET /api/private (no token)  (expected=401, actual=401)
  PASS  GET /api/private/admin (no token)  (expected=401, actual=401)
  PASS  GET /api/private/it (no token)  (expected=401, actual=401)
  PASS  GET /api/private/hr (no token)  (expected=401, actual=401)
  PASS  GET /api/private/dev (no token)  (expected=401, actual=401)

==========================================
TEST 3: protected endpoint rejects bad token
==========================================
  PASS  GET /api/private (garbage token)  (expected=401, actual=401)

==========================================
TEST 4: per-role access matrix
==========================================

--- admin-demo ---       realm_access.roles = [admin]
  PASS  GET /api/private          (expected=200, actual=200)
  PASS  GET /api/private/admin    (expected=200, actual=200)
  PASS  GET /api/private/it       (expected=200, actual=200)
  PASS  GET /api/private/hr       (expected=200, actual=200)
  PASS  GET /api/private/dev      (expected=200, actual=200)

--- itmgr-demo ---       realm_access.roles = [it_manager]
  PASS  GET /api/private          (expected=200, actual=200)
  PASS  GET /api/private/admin    (expected=403, actual=403)
  PASS  GET /api/private/it       (expected=200, actual=200)
  PASS  GET /api/private/hr       (expected=403, actual=403)
  PASS  GET /api/private/dev      (expected=200, actual=200)

--- hrmgr-demo ---       realm_access.roles = [hr_manager]
  PASS  GET /api/private          (expected=200, actual=200)
  PASS  GET /api/private/admin    (expected=403, actual=403)
  PASS  GET /api/private/it       (expected=403, actual=403)
  PASS  GET /api/private/hr       (expected=200, actual=200)
  PASS  GET /api/private/dev      (expected=403, actual=403)

--- dev-demo ---         realm_access.roles = [developer]
  PASS  GET /api/private          (expected=200, actual=200)
  PASS  GET /api/private/admin    (expected=403, actual=403)
  PASS  GET /api/private/it       (expected=403, actual=403)
  PASS  GET /api/private/hr       (expected=403, actual=403)
  PASS  GET /api/private/dev      (expected=200, actual=200)

--- normal-demo ---      realm_access.roles = [normal]
  PASS  GET /api/private          (expected=200, actual=200)
  PASS  GET /api/private/admin    (expected=403, actual=403)
  PASS  GET /api/private/it       (expected=403, actual=403)
  PASS  GET /api/private/hr       (expected=403, actual=403)
  PASS  GET /api/private/dev      (expected=403, actual=403)

==========================================
RESULTS: pass=32  fail=0
==========================================
```

**Result: 32 of 32 tests pass.** Every cell of the W5 access matrix is enforced as designed.

## Mapping to existing artefacts

| Layer | Where defined | Where enforced | Where tested |
|---|---|---|---|
| Roles | K2 → `keycloak/realm-import/cybergroup-realm.json` | Keycloak token issuance | `run-rbac-tests.sh` decodes JWT and prints roles |
| JWT validation | W2 + W3 → `backend/src/main/java/com/cybergroup/iam/config/SecurityConfig.java` | Spring Security `oauth2ResourceServer` | Tests 2 + 3 (no token / bad token → 401) |
| Role → authority mapping | W4 → `KeycloakRealmRoleConverter` | `JwtAuthenticationConverter` | Test 4 cell-by-cell |
| Endpoint authorization | W4 → `PrivateController.@PreAuthorize` | `@EnableMethodSecurity` | Test 4 cell-by-cell |
| Access matrix | W5 → `docs/iam/role-access-matrix.md` | (documentation) | EXPECTED matrix in `run-rbac-tests.sh` literally mirrors W5 |

## What's still untested at this point

- **MFA challenge end-to-end.** The K3 TOTP requirement is bypassed by `test-prep.sh` to make headless testing possible. Verifying the actual TOTP step needs a browser session (or a test that programmatically reads the QR secret from Keycloak and computes the TOTP code) — left for E2 #57 in the F-block / E-block.
- **Cross-realm or cross-issuer attacks.** Should add a test that signs a token with a different key and asserts 401. Reasonable add for the E4 #59 Testcontainers integration tests.
- **Token expiry.** Should add a test that captures a token, waits for expiry (or fast-forwards via clock skew), and asserts 401. Same place as above.
- **Refresh token rotation.** K5 #22 enables it; not exercised by this suite. E4 territory.

These are intentional scope choices for M4 — the suite covers the matrix. Token-internals coverage belongs in E4.
