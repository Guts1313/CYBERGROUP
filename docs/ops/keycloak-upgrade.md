# Keycloak upgrade procedure (O4 #50)

In-place upgrade of the Keycloak container in the Compose stack. Runbook validated against minor version bumps; major versions follow the same shape but require reading the upstream release notes for breaking changes (see step 1).

## Pre-upgrade checks

Before touching anything in production:

1. **Read the release notes** for every version between current and target.
   - https://www.keycloak.org/docs/latest/release_notes/
   - Look for: deprecated themes, removed SPI APIs, DB migration warnings, breaking config changes.
2. **Confirm current state is healthy.**
   ```
   docker exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
       --server http://localhost:8080 --realm master \
       --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
   curl -fsS http://localhost:9000/health/ready
   ```
   Both must succeed before proceeding.
3. **Take a fresh backup of both layers.**
   ```
   ./scripts/backup.sh                           # Postgres dump (K1 acceptance)
   ./scripts/realm-export.sh                     # Realm export (O1 #47)
   ```
   Verify the dump and export landed (file sizes > 0, expected file count).
4. **Note the current image tag** so rollback is unambiguous.
   ```
   docker inspect keycloak --format '{{.Config.Image}}'
   # → quay.io/keycloak/keycloak:26.0
   ```

## Upgrade

5. **Update the image tag** in `keycloak/compose.yml`:
   ```diff
   - image: quay.io/keycloak/keycloak:26.0
   + image: quay.io/keycloak/keycloak:26.1
   ```
6. **Pull the new image** (don't rely on `up -d` to do it on first run — explicit is faster to reason about):
   ```
   cd keycloak
   docker compose pull keycloak
   ```
7. **Re-create the Keycloak container** (Postgres is left untouched):
   ```
   docker compose up -d keycloak
   ```
   Keycloak runs any required DB migrations on boot. First-boot logs will show the migration steps.

## Post-upgrade verification

8. **Health check.**
   ```
   curl -fsS http://localhost:9000/health/ready
   ```
   Should return `{"status":"UP"}` within ~60s. If it 503s, check logs:
   ```
   docker compose logs --tail=200 keycloak
   ```
9. **Login smoke test.**
   - Browse to `http://localhost:8080/admin/` → log in as bootstrap admin.
   - Browse to `http://localhost:8080/realms/cybergroup/account/` → log in as `admin-demo` with TOTP.
10. **Token + role smoke test** (validates the auth path the backend depends on):
    ```
    TOKEN=$(curl -s -X POST \
      http://localhost:8080/realms/cybergroup/protocol/openid-connect/token \
      -d grant_type=password -d client_id=iam-frontend \
      -d username=admin-demo -d password=AdminDemo123!Init -d totp=<code> \
      | jq -r .access_token)
    curl -fsS -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/private/admin
    # → {"scope":"admin"}
    ```
11. **Backend regression check.**
    ```
    cd ../backend && mvn -B verify
    ```

## Rollback (if any of 8–11 fails)

12. **Revert the image tag** in `keycloak/compose.yml` to the version captured in step 4.
13. **Pull the old image and recreate the container:**
    ```
    cd keycloak
    docker compose pull keycloak
    docker compose up -d keycloak
    ```
    If Keycloak ran an irreversible DB migration on the failed upgrade, downgrading the image alone will not work — proceed to step 14.
14. **Restore from backup if downgrade alone is insufficient:**
    ```
    docker compose down
    docker volume rm cybergroup-iam_keycloak-pgdata
    docker compose up -d postgres
    # wait for postgres healthy
    ./scripts/restore.sh ./backups/keycloak-pg-<timestamp>.sql.gz
    docker compose up -d keycloak
    ```
15. **Re-run health + smoke tests (8–11)** to confirm rollback success.

## Cadence

Keycloak ships approximately quarterly. Plan an upgrade window each quarter, on a low-traffic day (school project: any day; production: per the org's change-management policy).

## Known footguns

- **Don't `docker compose up -d` without explicit `pull` first** — it may use a cached old image with the new tag mismatched.
- **Don't skip the realm export.** The Postgres dump is sufficient for cold restore, but the realm JSON is much faster to inspect/diff and is the source of truth for config-as-code.
- **`start-dev` mode** (used in the local Compose stack) auto-runs migrations and accepts HTTP. **`start` mode** (production) refuses to boot without HTTPS — make sure TLS certs are mounted before switching modes during the IdP-VLAN migration.
