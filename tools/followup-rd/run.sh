#!/bin/bash
# followup-rd: задача #7 — длинные цепочки follow-up, скоринг связности/дрейфа.
#
#   bash tools/followup-rd/run.sh fetch                          забрать реальные day_review (5 юзеров из signal-rd)
#   bash tools/followup-rd/run.sh chain --model M [--max-tokens N]  прогнать цепочку (1 модель, последовательно)
#   bash tools/followup-rd/run.sh drift --judge M --from FILE    judge по всей цепочке: советы/галлюцинации/противоречия/дрейф
#
# Требует tools/signal-rd/data/users.json (python3 tools/signal-rd/fetch.py 5).
# Ключ и адрес прокси берутся из backend/.env. Строго последовательно.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
export LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)"
[ -n "$LLM_API_KEY" ] || { echo "LLM_API_KEY не найден в backend/.env"; exit 1; }

case "${1:-}" in
  fetch) shift; python3 "$HERE/fetch.py" "$@" ;;
  chain) shift; python3 -u "$HERE/chain.py" "$@" ;;
  drift) shift; python3 -u "$HERE/judge_drift.py" "$@" ;;
  *) sed -n '2,7p' "$0" ;;
esac
