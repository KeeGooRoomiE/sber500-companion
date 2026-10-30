#!/bin/bash
# Страж бюджета ключа. Работает независимо от Claude.
#   bash tools/budget-guard/run.sh start    запустить в фоне (CUTOFF=50000 WARN=45000 HOURS=12 переопределяются)
#   bash tools/budget-guard/run.sh status   последние строки лога
#   bash tools/budget-guard/run.sh stop
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
case "${1:-}" in
  start) pgrep -f "budget-guard/guard.py" >/dev/null && { echo "уже запущен"; exit 0; }
         rm -f "$HERE/data/CUTOFF.flag"
         LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)" nohup caffeinate -i python3 -u "$HERE/guard.py" >> "$HERE/data/guard.log" 2>&1 &
         echo "запущен (PID $!)" ;;
  status) pgrep -f "budget-guard/guard.py" >/dev/null && echo "РАБОТАЕТ" || echo "не запущен"; [ -f "$HERE/data/CUTOFF.flag" ] && echo "!!! CUTOFF: $(cat "$HERE/data/CUTOFF.flag")"; tail -5 "$HERE/data/guard.log" 2>/dev/null ;;
  stop) pkill -f "budget-guard/guard.py" && echo "остановлен" || echo "не был запущен" ;;
  *) sed -n '2,5p' "$0" ;;
esac
