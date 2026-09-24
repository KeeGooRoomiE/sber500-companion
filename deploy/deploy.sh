#!/bin/bash
# Build locally, ship to the server, apply new migrations, restart, check health.
# Usage (from the repo root): bash deploy/deploy.sh SERVER_HOST
set -euo pipefail

SERVER="${1:?Usage: $0 SERVER_HOST}"
APP_DIR="/opt/companion"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Building binary (linux/amd64)"
mkdir -p "$ROOT/build"
(cd "$ROOT/backend" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o "$ROOT/build/server" ./cmd/server)

echo "==> Copying binary, migrations and systemd unit"
ssh "root@$SERVER" "mkdir -p $APP_DIR/migrations"
scp "$ROOT/build/server" "root@$SERVER:$APP_DIR/server.new"
scp "$ROOT"/backend/migrations/*.sql "root@$SERVER:$APP_DIR/migrations/"
scp "$ROOT/deploy/companion.service" "root@$SERVER:/etc/systemd/system/companion.service"

echo "==> Applying new migrations"
# Each file runs once, in order, in its own transaction; applied names go to schema_migrations.
ssh "root@$SERVER" bash -s <<'REMOTE'
set -euo pipefail
set -a; . /opt/companion/.env; set +a
psql "$DATABASE_URL" -qv ON_ERROR_STOP=1 -c \
  "CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW())"
for f in $(ls /opt/companion/migrations/*.sql | sort); do
    name="$(basename "$f")"
    if [ -z "$(psql "$DATABASE_URL" -tAc "SELECT 1 FROM schema_migrations WHERE name = '$name'")" ]; then
        echo "    applying $name"
        psql "$DATABASE_URL" -qv ON_ERROR_STOP=1 -1 -f "$f"
        psql "$DATABASE_URL" -qc "INSERT INTO schema_migrations (name) VALUES ('$name')"
    fi
done
REMOTE

echo "==> Swapping binary and restarting"
ssh "root@$SERVER" "
    set -e
    mv $APP_DIR/server.new $APP_DIR/server
    chown companion:companion $APP_DIR/server
    chmod 755 $APP_DIR/server
    systemctl daemon-reload
    systemctl enable companion >/dev/null
    systemctl restart companion
"

echo "==> Health check"
for i in 1 2 3 4 5; do
    if ssh "root@$SERVER" "curl -fsS -o /dev/null http://localhost:8080/health"; then
        echo "    healthy"
        echo "==> Deploy complete"
        exit 0
    fi
    sleep 2
done
echo "!! /health did not return 200 — last logs:"
ssh "root@$SERVER" "journalctl -u companion -n 30 --no-pager"
exit 1
