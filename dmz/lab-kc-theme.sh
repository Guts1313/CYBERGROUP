#!/usr/bin/env bash
# Run ON the Keycloak host (cybergroup-backend). Mounts the CYBERGROUP login theme
# into Keycloak (recreate, v2, data preserved) and sets the realm loginTheme.
# Prereq: grab the files first:
#   git checkout origin/feat/d1-d3-dmz-nginx-edge -- dmz/ keycloak/themes/
if [ "$(id -u)" -ne 0 ]; then echo "(elevating with sudo...)"; exec sudo bash "$0" "$@"; fi
set -e
PDIR="$(docker inspect keycloak --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' 2>/dev/null)"
[ -z "$PDIR" ] && PDIR="/home/student/CYBERGROUP"
cd "$PDIR"

if [ ! -f keycloak/themes/cybergroup/login/theme.properties ]; then
  echo "Theme files missing. Run first:  git checkout origin/feat/d1-d3-dmz-nginx-edge -- keycloak/themes/"; exit 1
fi

echo "=== recreate keycloak with the theme mounted (v2, data preserved) ==="
docker compose -p cybergroup -f compose.yml -f dmz/compose.dmz.yml up -d --no-deps keycloak

echo "=== wait for keycloak ready ==="
for i in $(seq 1 40); do
  sleep 2
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/realms/cybergroup/.well-known/openid-configuration)" = "200" ] && { echo "ready (~$((i*2))s)"; break; }
done

echo "=== set realm loginTheme=cybergroup ==="
ADMIN="$(grep -E '^KEYCLOAK_ADMIN=' .env | cut -d= -f2- | tr -d '\r')"
PASS="$(grep -E '^KEYCLOAK_ADMIN_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')"
docker exec keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$ADMIN" --password "$PASS"
docker exec keycloak /opt/keycloak/bin/kcadm.sh update realms/cybergroup -s loginTheme=cybergroup

echo "DONE. Log out (or use a private window) and open https://192.168.189.16/ -> Login - you should see the CYBERGROUP theme."
