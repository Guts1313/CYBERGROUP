#!/usr/bin/env bash
# O1 #47 — nightly Keycloak realm export.
#
# Exports the cybergroup realm from a running Keycloak container into a
# timestamped directory under EXPORT_DIR (default ./backups/realm-exports).
# Designed to be cron-driven nightly. Re-imports are tested via
# scripts/realm-reimport-drill.sh on a scratch instance.
#
# Note: kc.sh export does NOT include credential hashes by default
# (use --users different mode for that). This is the right tradeoff for
# a config-as-code backup.
set -euo pipefail

CONTAINER="${KEYCLOAK_CONTAINER:-keycloak}"
REALM="${REALM:-cybergroup}"
EXPORT_DIR="${EXPORT_DIR:-./backups/realm-exports}"

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "ERROR: container '${CONTAINER}' is not running" >&2
    exit 1
fi

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT_DIR="${EXPORT_DIR}/${TIMESTAMP}"
mkdir -p "${OUT_DIR}"

# Export inside the container, then copy out.
docker exec "${CONTAINER}" \
    /opt/keycloak/bin/kc.sh export \
    --dir /tmp/realm-export \
    --realm "${REALM}" \
    --users realm_file

docker cp "${CONTAINER}":/tmp/realm-export/. "${OUT_DIR}/"
docker exec "${CONTAINER}" rm -rf /tmp/realm-export

# Symlink "latest" for easy access.
ln -sfn "${TIMESTAMP}" "${EXPORT_DIR}/latest"

# Keep last 30 exports, drop older ones.
find "${EXPORT_DIR}" -mindepth 1 -maxdepth 1 -type d -mtime +30 -exec rm -rf {} +

echo "Realm export written: ${OUT_DIR}"
ls -la "${OUT_DIR}"
