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

## Deploy from GitHub (no local ssh needed)

Actions → **Deploy backend** → Run workflow. It tests, then runs `deploy.sh` from a GitHub
runner: backup → migrations → restart → `/health`. It then prints the applied migrations and the
active prompt versions, can reset the prompts to the built-in ones (checkbox), and renders every
prompt for the mock personas.

One-time setup: create a deploy key and add it as repository secrets
(Settings → Secrets and variables → Actions):

```bash
ssh-keygen -t ed25519 -N "" -C companion-deploy -f companion_deploy
ssh-copy-id -i companion_deploy.pub root@94.183.236.169        # or append the .pub to /root/.ssh/authorized_keys
# secrets: DEPLOY_SSH_KEY = contents of companion_deploy, DEPLOY_HOST = 94.183.236.169
# optional DEPLOY_KNOWN_HOSTS = output of: ssh-keyscan -t ed25519 94.183.236.169
```

## Updating a running server (release checklist)

`deploy.sh` is safe to re-run: it applies only migrations missing from `schema_migrations`, each
in its own transaction. All migrations only **add** tables/columns (`IF NOT EXISTS`) — an older
app build keeps working against a newer server, so the server can go out before the APK.

1. `bash deploy/deploy.sh SERVER` (or the GitHub workflow). If migrations are pending, it first dumps the DB to
   `/var/backups/companion/pre-deploy-*.sql.gz`. Watch the `applying 0xx_…` lines and `healthy`.
2. Check what is applied:
   `ssh root@SERVER 'set -a; . /opt/companion/.env; psql "$DATABASE_URL" -c "table schema_migrations"'`
3. Built-in prompts only apply when no DB version is active (`prompt.sh list` → `active_version: 0`).
   If an older version is active, `prompt.sh reset` (per prompt) or push a new one.
4. Smoke: `curl https://…/api/v1/metrics`; open the app → forecast, «Разбор вчера», «Итоги недели».
5. Publish the APK after the server is up.

Rollback: the previous binary is not kept. Check out the previous tag and run `deploy.sh`; new tables
stay and are harmless. Restore the dump only if data itself is broken.

## Prompts

Three prompts are versioned in the DB: `morning_system` (default), `day_review_system` and
`weekly_system` (pick one with `PROMPT=…`). They are edited through a **local-only** admin port
(127.0.0.1:9090, never proxied); `deploy/prompt.sh` runs curl on the server over ssh:

```bash
bash deploy/prompt.sh SERVER list                                  # versions + which is active
bash deploy/prompt.sh SERVER preview USER_ID draft.md              # see the rendered prompt (free)
bash deploy/prompt.sh SERVER preview USER_ID draft.md --llm        # + one real model answer
bash deploy/prompt.sh SERVER push! draft.md "мягче тон по утрам"   # new version, active in ≤30 s
bash deploy/prompt.sh SERVER activate 7                            # roll back / switch by id
bash deploy/prompt.sh SERVER reset                                 # back to the built-in prompt
```

### Testing a prompt on mock personas

`backend/internal/mock/personas.json` holds 6 people. Each has a week of data ending yesterday and
one situation to test:

| Persona | Situation |
|---|---|
| `mock_week` | the documented mock week |
| `mock_calls` | morning of calls |
| `mock_nightowl` | night owl without a watch |
| `mock_calm` | calm day |
| `mock_remote` | Telegram used as a work app |
| `mock_newbie` | day 0 |

They are excluded from metrics, call_log counts and the scheduler.

```bash
bash deploy/prompt.sh SERVER seed                       # (re)create them; dates shift to "yesterday"
bash deploy/prompt.sh SERVER eval                       # free: which signals each persona gets
bash deploy/prompt.sh SERVER eval --llm                 # active prompt, 6 model calls (under 1 ₽)
bash deploy/prompt.sh SERVER eval draft.md --llm        # the draft on the same people, compare
PROMPT=weekly_system bash deploy/prompt.sh SERVER eval --llm --prompt   # also print what the model got
bash deploy/prompt.sh SERVER purge                      # remove them
```

The report shows the answer, its length, a safety flag and «на самом деле»: what really happened
that day, which the model never sees. Re-seed each day you test, because the dates are relative.
Edit personas in `gen_personas.py` next to the JSON and re-run it.

### Did the change help?

The app asks «Совпало с днём?» under the forecast (after 14:00) and «Похоже на правду?» under reviews.
Each verdict stores the prompt version it rated:

```bash
bash deploy/prompt.sh SERVER accuracy 14    # hits / misses / % per prompt and version, 14 days
```

Public metrics show `forecast_accuracy_pct` and `forecast_feedback_rate_pct` (30 days). Compare
versions after 20+ verdicts each; below that the percentage is noise.

Start from the files in `backend/internal/llm/prompts/`. Each change is announced in Telegram
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
