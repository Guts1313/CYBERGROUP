#!/usr/bin/env bash
# M4 #30 — prep the running realm for headless RBAC testing.
#
# WARNING: this lowers security for testing convenience. It:
#   - enables directAccessGrantsEnabled on iam-frontend (password grant)
#   - clears CONFIGURE_TOTP required action on every demo user
# Run scripts/test-teardown.sh to restore production-like config.
# The realm only auto-imports on a fresh Postgres volume, so a
# `docker compose down -v && up -d` is the nuclear reset.
set -euo pipefail
export MSYS_NO_PATHCONV=1

KC="${KEYCLOAK_CONTAINER:-keycloak}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD must be set}"
REALM="${REALM:-cybergroup}"

echo "=== kcadm login ==="
docker exec "$KC" /opt/keycloak/bin/kcadm.sh config credentials \
    --server http://localhost:8080 --realm master \
    --user "$ADMIN_USER" --password "$ADMIN_PASS"

echo "=== enable directAccessGrantsEnabled on iam-frontend ==="
CLIENT_UUID=$(docker exec "$KC" /opt/keycloak/bin/kcadm.sh get clients \
    -r "$REALM" --query clientId=iam-frontend --fields id --format csv --noquotes \
    | tr -d '\r' | tail -1)
echo "  iam-frontend uuid: $CLIENT_UUID"
docker exec "$KC" /opt/keycloak/bin/kcadm.sh update "clients/$CLIENT_UUID" \
    -r "$REALM" -s directAccessGrantsEnabled=true

echo "=== clear requiredActions on demo users ==="
for u in admin-demo itmgr-demo hrmgr-demo dev-demo normal-demo; do
    UID2=$(docker exec "$KC" /opt/keycloak/bin/kcadm.sh get users \
        -r "$REALM" --query "username=$u" --fields id --format csv --noquotes \
        | tr -d '\r' | tail -1)
    docker exec "$KC" /opt/keycloak/bin/kcadm.sh update "users/$UID2" \
        -r "$REALM" -s 'requiredActions=[]'
    echo "  $u → cleared (uuid=$UID2)"
done

echo "=== prep done ==="
