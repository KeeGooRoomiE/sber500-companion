#!/bin/bash
# prompt-lab: итерации промтов на реальных входах. Без Claude.
#   bash tools/prompt-lab/run.sh fetch [prompt]          забрать входы персон с сервера (ssh companion)
#   bash tools/prompt-lab/run.sh lab <аргументы lab.py>  прогон вариантов (--variant, --models, --samples…), см. README
# Ключ и адрес прокси берутся из backend/.env.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
case "${1:-}" in
  fetch) shift; python3 "$HERE/fetch.py" "$@" ;;
  lab)   shift; LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)" python3 -u "$HERE/lab.py" "$@" ;;
  *) sed -n '2,6p' "$0" ;;
esac
