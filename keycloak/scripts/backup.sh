#!/usr/bin/env bash
# K1 backup drill helper. Production automation = O2 #48.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$BACKUP_DIR/keycloak-pg-$TIMESTAMP.sql.gz"

docker exec keycloak-postgres \
    pg_dump -U "${POSTGRES_USER:-keycloak}" keycloak \
    | gzip > "$OUT"

echo "Backup written: $OUT ($(du -h "$OUT" | cut -f1))"
