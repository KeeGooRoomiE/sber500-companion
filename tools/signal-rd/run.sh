#!/bin/bash
# signal-rd: R&D кандидатных поведенческих сигналов на РЕАЛЬНЫХ данных пользователей.
#
#   bash tools/signal-rd/run.sh fetch [N=5]                     забрать данные N самых "богатых" юзеров
#   bash tools/signal-rd/run.sh fetch --min-days 15 --min-checkins 3 --out data/users_wide.json  широкий отбор по порогу
#   bash tools/signal-rd/run.sh brainstorm --model M [--users-file users_wide.json] [--tag wide] [--limit K]
#   bash tools/signal-rd/run.sh validate --model M --from FILE [--users-file users_wide.json]
#
# Ключ и адрес прокси берутся из backend/.env. Строго последовательно (не параллелим модели/юзеров).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
export LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)"
[ -n "$LLM_API_KEY" ] || { echo "LLM_API_KEY не найден в backend/.env"; exit 1; }

case "${1:-}" in
  fetch)      shift; python3 "$HERE/fetch.py" "$@" ;;
  brainstorm) shift; python3 -u "$HERE/brainstorm.py" "$@" ;;
  validate)   shift; python3 -u "$HERE/validate.py" "$@" ;;
  *) sed -n '2,6p' "$0" ;;
esac
