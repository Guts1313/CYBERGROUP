#!/usr/bin/env bash
# O1 #47 — re-import drill helper.
# Restores a previously-exported realm into a scratch Keycloak+Postgres stack
# and verifies the realm exists. Used to prove backups actually work.
#
# Usage:
#   ./realm-reimport-drill.sh ./backups/realm-exports/<timestamp>
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <export-dir>" >&2
    exit 1
fi

SRC="$1"
if [[ ! -d "${SRC}" ]]; then
    echo "ERROR: export directory not found: ${SRC}" >&2
    exit 1
fi

PROJECT="kc-reimport-drill"
NETWORK="${PROJECT}_net"

cleanup() {
    docker compose -p "${PROJECT}" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Disposable Postgres + Keycloak with the export bind-mounted for import.
docker network create "${NETWORK}" >/dev/null 2>&1 || true

docker run -d --rm --name "${PROJECT}-pg" --network "${NETWORK}" \
    -e POSTGRES_USER=keycloak \
    -e POSTGRES_PASSWORD=drill \
    -e POSTGRES_DB=keycloak \
    postgres:16-alpine >/dev/null

# Wait for Postgres
for _ in {1..30}; do
    docker exec "${PROJECT}-pg" pg_isready -U keycloak -d keycloak >/dev/null 2>&1 && break
    sleep 1
done

docker run --rm --network "${NETWORK}" \
    -e KC_DB=postgres \
    -e KC_DB_URL=jdbc:postgresql://${PROJECT}-pg/keycloak \
    -e KC_DB_USERNAME=keycloak \
    -e KC_DB_PASSWORD=drill \
    -v "$(realpath "${SRC}")":/opt/keycloak/data/import:ro \
    quay.io/keycloak/keycloak:26.0 \
    import --dir /opt/keycloak/data/import

echo "Re-import drill succeeded."
docker stop "${PROJECT}-pg" >/dev/null 2>&1 || true
docker network rm "${NETWORK}" >/dev/null 2>&1 || true
trap - EXIT
