#!/bin/bash
# Deploy script: build binary locally, push to server
# Usage: bash deploy/deploy.sh SERVER_HOST
set -euo pipefail

SERVER="${1:?Usage: $0 SERVER_HOST}"
APP_DIR="/opt/companion"

echo "==> Building binary"
(cd backend && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o ../build/server ./cmd/server)

echo "==> Running migrations"
# Assumes psql is accessible from local or via SSH tunnel
# ssh "$SERVER" "psql \$DATABASE_URL < /tmp/companion_migrations.sql"

echo "==> Copying binary to server"
ssh "root@$SERVER" "mkdir -p $APP_DIR"
scp build/server "root@$SERVER:$APP_DIR/server"
scp backend/migrations/*.sql "root@$SERVER:/tmp/"

echo "==> Applying migrations on server"
ssh "root@$SERVER" "
    source $APP_DIR/.env 2>/dev/null || true
    for f in /tmp/*.sql; do
        psql \"\$DATABASE_URL\" < \"\$f\" && rm \"\$f\"
    done
"

echo "==> Restarting service"
ssh "root@$SERVER" "systemctl restart companion && systemctl status companion --no-pager"

echo "==> Deploy complete"
