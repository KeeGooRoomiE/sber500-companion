# Deployment Guide

## Stack
- **Server**: Ubuntu 22.04/24.04 VPS (1 vCPU / 1 GB minimum)
- **Reverse proxy**: Caddy (auto TLS via Let's Encrypt)
- **App**: Go binary managed by systemd
- **DB**: PostgreSQL (local), nightly `pg_dump` to `/var/backups/companion`

## From zero to running

1. Point DNS `api.companion.keegooroomie.ru` (A record) to the server IP — the APK release build
   and `web/metrics.html` both use this host.
2. On the server, as root:
   ```bash
   scp deploy/setup.sh root@SERVER:/root/ && ssh root@SERVER bash /root/setup.sh api.companion.keegooroomie.ru
   ```
   Installs Postgres + Caddy, generates the DB password, writes `/opt/companion/.env`, firewall, backups.
3. Put the LLM key into `/opt/companion/.env` (`LLM_API_KEY=…`), optionally your own user id into `DEV_USER_IDS`.
4. From the repo root on your machine (needs Go 1.23):
   ```bash
   bash deploy/deploy.sh SERVER
   ```
   Builds, copies binary + migrations + systemd unit, applies **new** migrations (tracked in
   `schema_migrations`), restarts, checks `/health` (returns 200 only if the DB answers).
5. Check: `curl https://api.companion.keegooroomie.ru/api/v1/metrics`

Re-deploy = step 4 again.

## Files

| File | Purpose |
|---|---|
| `setup.sh` | One-time provisioning (safe to re-run; keeps existing `.env`) |
| `deploy.sh` | Build + copy + migrate + restart + health check |
| `companion.service` | systemd unit (installed by `deploy.sh`) |
| `Caddyfile` | Reference config; `setup.sh` writes the same one for your domain |

## Prompts

The morning system prompt is versioned in the DB and edited through a **local-only** admin port
(127.0.0.1:9090, never proxied). `deploy/prompt.sh` runs curl on the server over ssh:

```bash
bash deploy/prompt.sh SERVER list                                  # versions + which is active
bash deploy/prompt.sh SERVER preview USER_ID draft.md              # see the rendered prompt (free)
bash deploy/prompt.sh SERVER preview USER_ID draft.md --llm        # + one real model answer
bash deploy/prompt.sh SERVER push! draft.md "мягче тон по утрам"   # new version, active in ≤30 s
bash deploy/prompt.sh SERVER activate 7                            # roll back / switch by id
bash deploy/prompt.sh SERVER reset                                 # back to the built-in prompt
```

Start from `backend/internal/llm/prompts/morning_system.md`. Each change is announced in Telegram
if `TG_BOT_TOKEN` / `TG_CHAT_ID` are set in `.env`. Answers with links or phone numbers are never shown.

## Environment variables

See `backend/.env.example`. The real file lives only on the server at `/opt/companion/.env` (mode 600).

## Logs

```bash
journalctl -u companion -f              # app logs (JSON)
tail -f /var/log/caddy/companion.log    # access logs
```

## Load test (required for MVP criteria)

```bash
# go install github.com/rakyll/hey@latest
hey -n 1000 -c 50 https://api.companion.keegooroomie.ru/health
```
