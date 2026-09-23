# CLAUDE.md — sber500-companion

Project-specific instructions for Claude Code sessions. Supplements the global `~/.claude/CLAUDE.md`.

## Project in one line

Android companion app (Kotlin/Compose) + Go backend + GitHub Pages landing.  
Participant ID **29754**, solo developer **Александр** (keegooroomie).  
Competition: Sber500 x DISRUPT — target nomination **"User Experience"** (5M₽).

## Critical dates

| Date | Event |
|------|-------|
| 2026-10-06 | MVP v0 must be publicly deployed |
| 2026-10-15 | Stage 1 deadline |

## Stack summary

| Layer | Tech |
|-------|------|
| Android | Kotlin 2.0.21, Compose BOM 2024.12.01, Hilt 2.51.1, WorkManager, Health Connect, Room, Glance |
| Backend | Go 1.22+, chi router, pgx/v5, slog, sashabaranov/go-openai |
| DB | PostgreSQL 15 |
| LLM | cloud.ru Foundation Models (OpenAI-compatible), 50k₽ budget |
| Infra | Ubuntu 22.04, Caddy (auto TLS), systemd, cloudcore.ru VPS |
| CI/CD | GitHub Actions — APK artifact + Pages deploy + TG bot notifications |
| Analytics | AppMetrica SDK (Android) |

## Repo structure

```
android/          Kotlin/Compose app
backend/          Go API server
web/              GitHub Pages landing (static)
deploy/           Caddyfile, systemd unit, setup.sh, deploy.sh
internal-docs/    Gitignored — interviews, dev notes, CI runbook
.claude/          Gitignored — CONTEXT.md, compacts/
```

## Secrets (never in repo)

| Secret | Where stored |
|--------|-------------|
| TG_BOT_TOKEN | GitHub Actions secret |
| TG_CHAT_ID | GitHub Actions secret |
| LLM_API_KEY | Server `/opt/companion/.env` |
| DATABASE_URL | Server `/opt/companion/.env` |

## Commit rules

- No Claude co-author trailer, no "Generated with Claude Code" footer
- Attribution set to `""` globally — never override

## Key identities / accounts

- GitHub: `KeeGooRoomiE` / `keegooroomie`
- Competition access: `wow-distrupt` account added as collaborator
- TG bot chat_id: `398066304`

## Anti-fraud compliance (competition requirement)

`call_log` table must log every LLM/tool call with:
- `user_id`, `ts`, `session_id`, `call_type`, `component`, `trigger`, `user_visible`, `result`, `latency_ms`
- Dev users filtered by `DEV_USER_IDS` env var
- Background calls (`trigger=scheduled`) must be logged separately

## Before reading architecture

Read `internal-docs/dev/ARCHITECTURE.md` — do NOT derive from code alone, data-flow decisions are non-obvious.

## CI known issues

See `internal-docs/dev/CI_CD.md` for history of build failures and fixes.
