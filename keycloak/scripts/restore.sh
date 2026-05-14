#!/usr/bin/env bash
# K1 restore drill helper.
# Usage: ./scripts/restore.sh ./backups/keycloak-pg-<timestamp>.sql.gz
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <backup-file.sql.gz>" >&2
    exit 1
fi

BACKUP_FILE="$1"
if [[ ! -f "$BACKUP_FILE" ]]; then
    echo "Backup file not found: $BACKUP_FILE" >&2
    exit 1
fi

echo "About to restore $BACKUP_FILE into keycloak DB. Press Ctrl-C to abort, Enter to continue."
read -r

gunzip -c "$BACKUP_FILE" \
    | docker exec -i keycloak-postgres \
        psql -U "${POSTGRES_USER:-keycloak}" keycloak

echo "Restore complete. Restart Keycloak to pick up the change: docker compose restart keycloak"
