#!/bin/bash
# model-compare: задача #2 — latency/cost/quality по моделям, рекомендация прод-модели.
#
#   bash tools/model-compare/run.sh bench --model M [--n 9] [--max-tokens 260]
#
# Требует tools/followup-rd/data/real_prompts.json (fetch_real_prompt.py — уже собран).
# Ключ и адрес прокси берутся из backend/.env. Строго последовательно, без ретраев
# (честная latency, не маскированная fallback'ом).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
export LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)"
[ -n "$LLM_API_KEY" ] || { echo "LLM_API_KEY не найден в backend/.env"; exit 1; }

case "${1:-}" in
  bench) shift; python3 -u "$HERE/bench.py" "$@" ;;
  *) sed -n '2,5p' "$0" ;;
esac
