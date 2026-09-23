#!/bin/bash
# Initial server setup for Companion backend
# Run as root on a fresh Ubuntu 22.04 VPS
# Usage: bash setup.sh YOUR_DOMAIN
set -euo pipefail

DOMAIN="${1:?Usage: $0 YOUR_DOMAIN}"
APP_USER="companion"
APP_DIR="/opt/companion"

echo "==> Installing system packages"
apt-get update -q
apt-get install -y -q \
    postgresql postgresql-contrib \
    docker.io docker-compose-plugin \
    curl wget git ufw

echo "==> Installing Caddy"
apt-get install -y -q debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
apt-get update -q
apt-get install -y -q caddy

echo "==> Creating app user"
id -u "$APP_USER" &>/dev/null || useradd --system --shell /bin/false --home "$APP_DIR" "$APP_USER"

echo "==> Creating directories"
mkdir -p "$APP_DIR" /var/log/companion /var/log/caddy
chown "$APP_USER:$APP_USER" "$APP_DIR" /var/log/companion

echo "==> Configuring PostgreSQL"
systemctl start postgresql
systemctl enable postgresql
sudo -u postgres psql -c "CREATE USER companion WITH PASSWORD 'CHANGE_ME_IN_ENV';" 2>/dev/null || true
sudo -u postgres psql -c "CREATE DATABASE companion OWNER companion;" 2>/dev/null || true

echo "==> Configuring firewall"
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "==> Deploying Caddyfile"
mkdir -p /etc/caddy
cat > /etc/caddy/Caddyfile <<EOF
${DOMAIN} {
    reverse_proxy localhost:8080
    encode gzip
    header { X-Content-Type-Options nosniff; X-Frame-Options DENY; -Server }
    log { output file /var/log/caddy/companion.log { roll_size 10mb; roll_keep 5 }; format json }
}
EOF

echo "==> NOTE: Place .env at $APP_DIR/.env before starting service"
echo "    Template: backend/.env.example"
echo ""
echo "==> Reload Caddy and install service AFTER copying binary:"
echo "    sudo systemctl reload caddy"
echo "    sudo cp deploy/companion.service /etc/systemd/system/"
echo "    sudo systemctl daemon-reload"
echo "    sudo systemctl enable --now companion"
echo ""
echo "==> DONE. Next: copy binary, .env, run migrations, start service."
