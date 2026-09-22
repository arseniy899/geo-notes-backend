#!/usr/bin/env bash
# Nightly PostgreSQL backup → Hetzner Storage Box.
# Install: see deploy/README.md ("Backups"). Runs on the host as the deploy user via cron.
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/whenhere}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/whenhere}"
KEEP_LOCAL_DAYS="${KEEP_LOCAL_DAYS:-7}"
# e.g. u123456@u123456.your-storagebox.de  (SSH on port 23)
STORAGE_BOX="${STORAGE_BOX:?set STORAGE_BOX=uXXXXXX@uXXXXXX.your-storagebox.de}"
REMOTE_DIR="${REMOTE_DIR:-whenhere-db}"

cd "$APP_DIR"
# shellcheck disable=SC1091
source .env
mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$BACKUP_DIR/geonotes-$stamp.dump"

# Custom-format dump (compressed, restorable with pg_restore). Taken from inside the db container.
docker compose exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner > "$file.partial"
mv "$file.partial" "$file"

# Keep a short local history, mirror the directory to the Storage Box (remote keeps what rsync sends;
# prune remote with the Storage Box snapshot/retention settings or a periodic `rsync --delete`).
find "$BACKUP_DIR" -name 'geonotes-*.dump' -mtime +"$KEEP_LOCAL_DAYS" -delete
rsync -a --delete -e "ssh -p 23 -o BatchMode=yes" "$BACKUP_DIR/" "$STORAGE_BOX:$REMOTE_DIR/"

echo "backup ok: $file"
