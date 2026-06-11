#!/usr/bin/env bash
# Run ON the Keycloak host (cybergroup-backend). Points the realm + iam-frontend
# client at the nginx front door (https://192.168.189.16). The realm's old
# 'Frontend URL' was baked to the pre-re-IP 10.0.20.102, which forces every
# Keycloak URL to a host that no longer exists -> CORS/CSP errors behind the proxy.
if [ "$(id -u)" -ne 0 ]; then echo "(elevating with sudo...)"; exec sudo bash "$0" "$@"; fi
set -e
FD="https://192.168.189.16"
PDIR="$(docker inspect keycloak --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' 2>/dev/null)"
[ -z "$PDIR" ] && PDIR="/home/student/CYBERGROUP"
ADMIN="$(grep -E '^KEYCLOAK_ADMIN=' "$PDIR/.env" | cut -d= -f2- | tr -d '\r')"
PASS="$(grep -E '^KEYCLOAK_ADMIN_PASSWORD=' "$PDIR/.env" | cut -d= -f2- | tr -d '\r')"

kc() { docker exec keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

echo "=== authenticate ==="
kc config credentials --server http://localhost:8080 --realm master --user "$ADMIN" --password "$PASS"

echo "=== set realm Frontend URL -> $FD ==="
kc update realms/cybergroup -s "attributes.frontendUrl=$FD"

echo "=== point iam-frontend client at the front door ==="
CID="$(kc get clients -r cybergroup -q clientId=iam-frontend --fields id --format csv --noquotes | tr -d '\r ')"
kc update "clients/$CID" -r cybergroup \
  -s 'redirectUris=["http://localhost:5173/*","https://localhost/*","https://192.168.189.16/*"]' \
  -s 'webOrigins=["http://localhost:5173","https://localhost","https://192.168.189.16"]' \
  -s 'attributes={"pkce.code.challenge.method":"S256","post.logout.redirect.uris":"+"}'

echo "DONE. Hard-refresh https://192.168.189.16/ (demo console) and /admin/ - the 10.0.20.102 errors should be gone."
