#!/usr/bin/env bash
# CYBERGROUP - deploy the DMZ nginx edge on n2-test-dmz.
# Run as:  sudo bash dmz/lab-deploy.sh
# Re-IPs eth0 -> 10.0.10.10, makes a self-signed cert, runs nginx (Keycloak-only proxy).

# auto-elevate so 'bash dmz/lab-deploy.sh' works even without sudo
if [ "$(id -u)" -ne 0 ]; then echo "(elevating with sudo...)"; exec sudo bash "$0" "$@"; fi
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"   # .../dmz

if ! docker --version >/dev/null 2>&1; then
  echo "Docker not installed - run 'sudo bash dmz/lab-setup.sh' first."; exit 1
fi

echo "=== [1/4] re-IP eth0 -> 10.0.10.10 (NetworkManager) ==="
nmcli con mod "Wired connection 1" ipv4.method manual ipv4.addresses 10.0.10.10/24 ipv4.gateway 10.0.10.1 ipv4.dns 10.0.10.1
nmcli con up "Wired connection 1"
sleep 3
ip -br a | grep -E "eth0"

echo "=== [2/4] self-signed cert ==="
mkdir -p "$DIR/certs"
if [ ! -f "$DIR/certs/edge.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$DIR/certs/edge.key" -out "$DIR/certs/edge.crt" -days 365 \
    -subj "/CN=cybergroup-dmz" -addext "subjectAltName=IP:10.0.10.10"
fi
ls -l "$DIR/certs"

echo "=== [3/4] run nginx (Keycloak-only proxy -> 10.0.20.10:8080) ==="
docker rm -f nginx-edge >/dev/null 2>&1 || true
docker run -d --name nginx-edge --restart unless-stopped \
  -p 443:443 -p 80:80 \
  -v "$DIR/nginx.lab.conf:/etc/nginx/nginx.conf:ro" \
  -v "$DIR/certs:/etc/nginx/certs:ro" \
  nginx:1.27-alpine
sleep 3

echo "=== [4/4] verify (Keycloak proxy needs firewall rule #6 - that's Step 3) ==="
docker exec nginx-edge nginx -t
docker ps --filter name=nginx-edge --format "{{.Names}} {{.Status}} {{.Ports}}"
curl -k -s -o /dev/null -w "nginx :443 landing   -> %{http_code}  (expect 200)\n" https://127.0.0.1/
curl -k -s -o /dev/null -w "nginx -> keycloak     -> %{http_code}  (expect 000/502 until rule #6 is enabled)\n" --max-time 6 https://127.0.0.1/realms/cybergroup/.well-known/openid-configuration
echo "=== DONE ==="
