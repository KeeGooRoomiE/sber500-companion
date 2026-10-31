#!/bin/bash
# edge-case-rd: задача #6 — вырожденные/экстремальные входы для day_review_system.
#
#   bash tools/edge-case-rd/run.sh run   --model M [--max-tokens 600]
#   bash tools/edge-case-rd/run.sh judge --judge M --from FILE
#
# 7 синтетических кейсов (cases.py), один на ось робастности. Ключ/адрес прокси из
# backend/.env. Строго последовательно.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
export LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)"
[ -n "$LLM_API_KEY" ] || { echo "LLM_API_KEY не найден в backend/.env"; exit 1; }

case "${1:-}" in
  run)   shift; python3 -u "$HERE/run_edge.py" "$@" ;;
  judge) shift; python3 -u "$HERE/judge_edge.py" "$@" ;;
  *) sed -n '2,6p' "$0" ;;
esac
