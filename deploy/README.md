# Deployment Guide

## Stack
- **Server**: Ubuntu 22.04 VPS (1 vCPU / 1 GB minimum)
- **Reverse proxy**: Caddy (auto TLS via Let's Encrypt)
- **App**: Go binary managed by systemd
- **DB**: PostgreSQL 16

## Quick start (fresh VPS)

```bash
# 1. Run setup script as root
bash deploy/setup.sh your.domain.com

# 2. Create env file on server
scp backend/.env.example root@SERVER:/opt/companion/.env
# Then edit /opt/companion/.env with real values

# 3. Deploy
bash deploy/deploy.sh SERVER_IP
```

## Files

| File | Purpose |
|---|---|
| `Caddyfile` | Caddy config template (replace YOUR_DOMAIN) |
| `companion.service` | systemd unit for the Go binary |
| `setup.sh` | One-time server provisioning |
| `deploy.sh` | Build + copy + restart |

## Environment variables

See `backend/.env.example` — copy to `/opt/companion/.env` on server, fill in values.
The `.env` file is never committed to the repository.

## Logs

```bash
journalctl -u companion -f       # app logs
tail -f /var/log/caddy/companion.log  # access logs
```

## Load test (required for MVP criteria)

```bash
# Install hey: go install github.com/rakyll/hey@latest
hey -n 1000 -c 50 https://your.domain.com/health
```
