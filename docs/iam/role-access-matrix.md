# Role / access matrix (W5 #27)

Client-facing artefact showing what each of the five Keycloak realm roles can do, with five demo accounts to walk through. This document is the authoritative source for the role/access matrix — the K2 realm config and the W4 `@PreAuthorize` rules in `PrivateController` should match what's below.

## The five roles

| Role | Realm role name | Intent (per the network diagram) |
|---|---|---|
| Admin | `admin` | Full administrative access; management consoles, SIEM, can do everything |
| IT Manager | `it_manager` | IT operations and infrastructure management |
| HR Manager | `hr_manager` | HR systems access |
| Developer | `developer` | Repos and internal APIs |
| Normal worker | `normal` | Standard access — workstation, email |

## Access matrix

Maps each role to each protected resource currently exposed by the demo backend (`PrivateController` in `/backend`).

| Resource | `admin` | `it_manager` | `hr_manager` | `developer` | `normal` |
|---|---|---|---|---|---|
| `GET /api/private` (any authenticated) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /api/private/admin` | ✅ | ❌ | ❌ | ❌ | ❌ |
| `GET /api/private/it` | ✅ | ✅ | ❌ | ❌ | ❌ |
| `GET /api/private/hr` | ✅ | ❌ | ✅ | ❌ | ❌ |
| `GET /api/private/dev` | ✅ | ✅ | ❌ | ✅ | ❌ |
| `GET /api/public` (anonymous) | ✅ | ✅ | ✅ | ✅ | ✅ (no token needed) |

**Justifications:**

- **Admin** is intentionally a superset — every protected route includes `admin` in its `@PreAuthorize` so the role works as a break-glass.
- **IT Manager** gets `/dev` because IT operations frequently overlaps with developer needs (CI/CD, infra repos). Easy to revoke if it turns out to be too broad.
- **HR Manager** is narrow on purpose — only HR-tagged resources. No dev access.
- **Developer** gets only `/dev`. Cannot read HR or admin resources.
- **Normal** can authenticate but cannot reach any role-scoped resource. They see only the unauthenticated probe and the "any authenticated" endpoint.

## Demo accounts

Five accounts are seeded by `keycloak/realm-import/cybergroup-realm.json` — one per role. Each is forced to enrol TOTP on first login (per K3 #15).

| Username | Role | Initial password | Email |
|---|---|---|---|
| `admin-demo`  | `admin`      | `AdminDemo123!Init`  | `admin-demo@cybergroup.local`  |
| `itmgr-demo`  | `it_manager` | `ITMgrDemo123!Init`  | `itmgr-demo@cybergroup.local`  |
| `hrmgr-demo`  | `hr_manager` | `HRMgrDemo123!Init`  | `hrmgr-demo@cybergroup.local`  |
| `dev-demo`    | `developer`  | `DevDemo123!Init`    | `dev-demo@cybergroup.local`    |
| `normal-demo` | `normal`     | `NormalDemo123!Init` | `normal-demo@cybergroup.local` |

> **⚠ Initial passwords are throwaway and documented for the demo only. Rotate them before exposing the system to anyone outside the team.**

## End-to-end walkthrough (per role)

Prerequisites: `cd keycloak && docker compose up -d` and `cd backend && mvn spring-boot:run`. Both running locally.

### Step 1 — first-time TOTP enrolment (one-off per account)

For each demo user:

1. Browse to `http://localhost:8080/realms/cybergroup/account/`
2. Sign in with the username + initial password from the table above
3. Keycloak forces TOTP enrolment — scan the QR with any TOTP app (Google Authenticator, Authy, FreeOTP, Microsoft Authenticator)
4. Enter the 6-digit code to confirm; account is now MFA-enabled

### Step 2 — verify access per role

For each role, get a token and hit each endpoint. The pattern uses the resource owner password grant (`grant_type=password`) which works for testing only — real clients use Auth Code + PKCE via the `iam-frontend` client.

```bash
# Replace USERNAME/PASSWORD/TOTP_CODE for each role
TOKEN=$(curl -s -X POST \
  http://localhost:8080/realms/cybergroup/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=iam-frontend \
  -d username=USERNAME \
  -d password=PASSWORD \
  -d totp=TOTP_CODE \
  | jq -r .access_token)

for path in /api/private /api/private/admin /api/private/it /api/private/hr /api/private/dev; do
  status=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8081$path")
  echo "$path -> $status"
done
```

Expected per role:

| User | `/private` | `/admin` | `/it` | `/hr` | `/dev` |
|---|---|---|---|---|---|
| `admin-demo` | 200 | 200 | 200 | 200 | 200 |
| `itmgr-demo` | 200 | 403 | 200 | 403 | 200 |
| `hrmgr-demo` | 200 | 403 | 403 | 200 | 403 |
| `dev-demo` | 200 | 403 | 403 | 403 | 200 |
| `normal-demo` | 200 | 403 | 403 | 403 | 403 |

(`401` instead of `403` means the token is missing/invalid; `403` means the token is valid but the role isn't allowed.)

## How this matrix is enforced

| Layer | Where | What |
|---|---|---|
| Identity | `keycloak/realm-import/cybergroup-realm.json` (K2 #16) | Roles created, users assigned |
| Token | Keycloak issues JWTs with `realm_access.roles` | Set by Keycloak on every login |
| Backend | `KeycloakRealmRoleConverter` (W3/W4) | Maps `realm_access.roles` → `ROLE_<role>` Spring authorities |
| Endpoint | `PrivateController.@PreAuthorize` (W4) | `hasRole`/`hasAnyRole` checked on every method |
| Test | `PrivateControllerTest` | Positive + negative assertion per role per endpoint |

## Future scope (not in W5)

- The C2 #34 "client-facing role overview" presents the same matrix in non-technical language for the customer.
- The frontend (F-block) renders per-role UI based on the same JWT claims (F3 #53).
- E2E coverage of the matrix lives in E3 #58.
