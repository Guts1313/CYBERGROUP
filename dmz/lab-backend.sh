#!/usr/bin/env bash
# Run ON the Services VM (n2-test-svc, 10.0.30.10), from anywhere in the repo.
# Builds the iam-backend image from ./backend and runs it, pointed at Keycloak
# for JWKS (cross-VLAN to the IdP) with the front-door issuer.
if [ "$(id -u)" -ne 0 ]; then echo "(elevating with sudo...)"; exec sudo bash "$0" "$@"; fi
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"   # repo root
cd "$DIR"

echo "=== [1/3] build iam-backend image (Maven, a few min over 443) ==="
docker build -t cybergroup/iam-backend:0.1.0 backend/

echo "=== [2/3] run iam-backend ==="
docker rm -f iam-backend 2>/dev/null || true
docker run -d --name iam-backend --restart unless-stopped -p 8081:8081 \
  -e KEYCLOAK_ISSUER_URI=https://192.168.189.16/realms/cybergroup \
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://10.0.20.10:8080/realms/cybergroup/protocol/openid-connect/certs \
  cybergroup/iam-backend:0.1.0
sleep 6

echo "=== [3/3] verify ==="
docker ps --filter name=iam-backend --format "{{.Names}} {{.Status}} {{.Ports}}"
curl -s -o /dev/null -w "api/public -> %{http_code}  (expect 200)\n" http://localhost:8081/api/public
echo "DONE. Backend is up. Token validation (JWKS -> Keycloak) needs firewall rule #8 = Step 3."
