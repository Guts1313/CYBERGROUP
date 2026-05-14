# CYBERGROUP Keycloak (K1)

Keycloak 26 + Postgres 16 via Docker Compose. Phase A scaffold — runs on a temp Docker host until CurlyRed's IdP VLAN is up (then it migrates per K1's "Done when").

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

The `cybergroup` realm auto-imports on first boot from `realm-import/cybergroup-realm.json`. K1 ships an empty realm with:

- Brute-force protection on (5 failures → lockout, 15-min wait)
- Event logging enabled (covers M1 #28)
- Admin events + details enabled
- Self-registration disabled

K2 #16 adds the 5 roles, K4 #13 adds OIDC clients, K3 #15 adds MFA, K5 #22 tightens the password/session policy. Each lands as edits to the realm JSON.

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
| K2 #16 | 5 realm roles | B |
| K5 #22 | Password / session policy | B |
| K4 #13 | OIDC clients | B |
| K3 #15 | TOTP MFA flows | B |
| O1 #47 | Realm export automation | E |
| O2 #48 | pg_dump + WAL | E |
| O3 #49 | `/metrics` → Prometheus | E (CurlyRed) |
| O4 #50 | Upgrade runbook | E |
