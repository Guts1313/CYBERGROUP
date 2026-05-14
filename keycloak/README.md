# CYBERGROUP Keycloak (K1–K5 + M1)

Keycloak 26 + Postgres 16 via Docker Compose. Phase B complete — realm has 5 roles, 2 OIDC clients, password policy, token/session lifetimes, TOTP MFA enforced, and 5 demo users. Runs on a temp Docker host until CurlyRed's IdP VLAN is up (then it migrates per K1's "Done when").

Stack locked by `Research_Deployment_Architecture` §2.2 / §3.2 / §4.2: Docker Compose, Keycloak 26.0, Postgres 16, Nginx in front (D1).

## Prerequisites

- Docker 24+ and Docker Compose v2
- Free ports `8080` (Keycloak HTTP) and `9000` (management — `/health` + `/metrics`)

## Run locally

```
cd keycloak
cp .env.example .env
# Edit .env: set strong values for POSTGRES_PASSWORD and KEYCLOAK_ADMIN_PASSWORD
docker compose up -d
```

First boot is ~30–40 seconds (Postgres bootstrap + Keycloak realm migration). Confirm it's ready:

```
curl -fsS http://localhost:9000/health/ready
# → {"status":"UP","checks":[...]}
```

Admin console: `http://localhost:8080/admin/`

## Realm

The `cybergroup` realm auto-imports on first boot from `realm-import/cybergroup-realm.json`. Current contents (Phase A + B):

**Realm baseline (K1, M1):**
- Brute-force protection on (5 failures → 15-min wait)
- Event + admin event logging enabled (M1 #28)
- Self-registration disabled

**Password policy (K5 #22):**
- Length ≥ 12, with at least 1 digit / lower / upper / special
- Cannot equal username or email
- 5-password history
- Force change after 180 days

**Token / session lifetimes (K5 #22):**
- Access token: 15 min
- SSO session idle: 30 min · max: 10 h
- Refresh token rotation enabled (`refreshTokenMaxReuse: 0`, `revokeRefreshToken: true`)
- Offline session: 30 days idle, 60 days max

**TOTP MFA (K3 #15):**
- HMAC-SHA256, 6 digits, 30-sec period
- All 5 demo users have `CONFIGURE_TOTP` required action — they're forced to enrol on first login
- After enrolment, the default browser flow's conditional OTP step prompts for the code

**Roles (K2 #16):** `admin`, `it_manager`, `hr_manager`, `developer`, `normal` — full access matrix lives in C2 #34 (when written) and W5 #27.

**OIDC clients (K4 #13):**
| `clientId` | Type | Used by | Notes |
|---|---|---|---|
| `iam-backend` | Confidential, **bearer-only** | Spring Boot backend | Validates JWTs only — no login flow |
| `iam-frontend` | Public, Auth Code + PKCE (S256) | TypeScript SPA | Redirect URI `http://localhost:5173/*` for local dev |

**Demo users (one per role):**
| Username | Role | Initial password |
|---|---|---|
| `admin-demo`  | admin       | `AdminDemo123!Init` |
| `itmgr-demo`  | it_manager  | `ITMgrDemo123!Init` |
| `hrmgr-demo`  | hr_manager  | `HRMgrDemo123!Init` |
| `dev-demo`    | developer   | `DevDemo123!Init` |
| `normal-demo` | normal      | `NormalDemo123!Init` |

**⚠ Initial passwords are documentation-only — change them or rotate via Keycloak admin before exposing the demo. All demo users must enrol TOTP on first login.**

## Wiping + reimporting the realm

The realm only auto-imports on a **fresh** Postgres volume. To re-import after editing `cybergroup-realm.json`:

```
docker compose down -v        # drops the keycloak-pgdata volume
docker compose up -d           # fresh Postgres → Keycloak imports the JSON again
```

For non-destructive incremental updates, use `kc.sh import` inside the container or the Admin REST API (covered later by O1 #47).

## Backup / restore drill (K1 acceptance)

```
chmod +x scripts/backup.sh scripts/restore.sh
./scripts/backup.sh
./scripts/restore.sh ./backups/keycloak-pg-<timestamp>.sql.gz
```

Production automation lives in O1 #47 (realm export) and O2 #48 (pg_dump + WAL).

## Migration to IdP VLAN (after CurlyRed's N1 sync point)

1. `docker compose down` (volumes preserved — DB state stays)
2. Move host to IdP VLAN with the static IP CurlyRed shares
3. Update `KC_HOSTNAME` in `.env` to the agreed hostname
4. Switch `command:` from `start-dev` to `start` (production mode — requires HTTPS)
5. Provide TLS cert via env or volume mount
6. `docker compose up -d`
7. Post sync point #2 reply: "Keycloak running at <https-url> on <ip>. Ready for ACLs."

## Roadmap inside `/keycloak`

| Issue | What | Phase |
|---|---|---|
| K1 #12 | This scaffold | A |
| M1 #28 | Event logging | A (covered by realm-import above) |
| K2 #16 | 5 realm roles | B ✓ |
| K5 #22 | Password / session policy | B ✓ |
| K4 #13 | OIDC clients (`iam-backend`, `iam-frontend`) | B ✓ |
| K3 #15 | TOTP MFA flows | B ✓ |
| O1 #47 | Realm export automation | E |
| O2 #48 | pg_dump + WAL | E |
| O3 #49 | `/metrics` → Prometheus | E (CurlyRed) |
| O4 #50 | Upgrade runbook | E |
