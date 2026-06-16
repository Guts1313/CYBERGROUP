#!/usr/bin/env bash
# Run ON the Keycloak host (cybergroup-backend). Grants the realm-management roles
# view-users + view-clients to the 'admin' realm role, so an admin's bearer token can
# read active sessions + login events through Keycloak's Admin REST API. The backend's
# /api/admin/sessions + /api/admin/events endpoints (the admin dashboard panels) relay
# that token — no service account or client secret is stored anywhere.
#
# Roles are composited onto the 'admin' role, so every admin user inherits them.
# Existing sessions must re-login to pick the new roles up in their token.
if [ "$(id -u)" -ne 0 ]; then echo "(elevating with sudo...)"; exec sudo bash "$0" "$@"; fi
set -e
PDIR="$(docker inspect keycloak --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' 2>/dev/null)"
[ -z "$PDIR" ] && PDIR="/home/student/CYBERGROUP"
ADMIN="$(grep -E '^KEYCLOAK_ADMIN=' "$PDIR/.env" | cut -d= -f2- | tr -d '\r')"
PASS="$(grep -E '^KEYCLOAK_ADMIN_PASSWORD=' "$PDIR/.env" | cut -d= -f2- | tr -d '\r')"

kc() { docker exec keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

echo "=== authenticate ==="
kc config credentials --server http://localhost:8080 --realm master --user "$ADMIN" --password "$PASS"

echo "=== grant realm-management view-users + view-clients + view-events to the 'admin' role ==="
kc add-roles -r cybergroup --rname admin \
  --cclientid realm-management --rolename view-users --rolename view-clients --rolename view-events

echo "DONE. Re-login as admin-test so the new roles land in the token, then open the demo"
echo "console — the 'Active sessions' and 'Login events' panels populate for the admin."
