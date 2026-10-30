#!/bin/bash
# Исследование иконки и дизайна силами моделей. Запускается с вашего компьютера, без Claude.
#
#   bash tools/design-research/run.sh selftest         проверка инструментов и моделей (дёшево, ~2 мин)
#   bash tools/design-research/run.sh start [icon|design|both]   запустить в фоне (по умолчанию both)
#   bash tools/design-research/run.sh status           что сделано, лог, текущий спенд прогона
#   bash tools/design-research/run.sh ask "задание" [--model M] [--ctx icon] [--steps N]   точечное поручение модели
#   bash tools/design-research/run.sh stop             остановить (start продолжит с места остановки)
#   bash tools/design-research/run.sh open [icon|design]   открыть gallery.html и REPORT.md
#
# Ключ и адрес прокси берутся из backend/.env. Лимит спенда на прогон: SPEND_CAP (₽, по умолчанию 15000).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DATA="$HERE/data"; mkdir -p "$DATA"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
export LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)"
[ -n "$LLM_API_KEY" ] || { echo "LLM_API_KEY не найден в backend/.env"; exit 1; }

case "${1:-}" in
  selftest) python3 -u "$HERE/research.py" selftest ;;
  start)
    what="${2:-both}"
    if pgrep -f "design-research/research.py" >/dev/null; then echo "уже запущен (run.sh status)"; exit 0; fi
    case "$what" in
      icon|design) cmd="python3 -u '$HERE/research.py' $what" ;;
      both) cmd="python3 -u '$HERE/research.py' icon; python3 -u '$HERE/research.py' design" ;;
      *) echo "что запускать: icon | design | both"; exit 1 ;;
    esac
    nohup caffeinate -i bash -c "$cmd" >> "$DATA/run.log" 2>&1 &
    echo "запущен (PID $!). Прогресс: bash tools/design-research/run.sh status" ;;
  status)
    pgrep -f "design-research/research.py" >/dev/null && echo "статус: РАБОТАЕТ" || echo "статус: не запущен"
    for m in icon design; do
      d="$DATA/$m"; [ -d "$d" ] || continue
      echo "[$m] агентов-отчётов: $(ls "$d"/agent_*.md 2>/dev/null | wc -l | tr -d ' ') | синтез: $([ -f "$d/synth.json" ] && echo да || echo нет) | картинок: $(ls "$d"/gen/*.png 2>/dev/null | grep -vc '\.s[0-9]*\.png' || true) | оценок: $(cat "$d"/panel_*.jsonl 2>/dev/null | wc -l | tr -d ' ') | готово: $([ -f "$d/DONE" ] && echo да || echo нет)"
    done
    echo "--- лог ---"; tail -6 "$DATA/run.log" 2>/dev/null || true ;;
  ask) shift; python3 -u "$HERE/ask.py" "$@" ;;
  stop) pkill -f "design-research/research.py" && echo "остановлен" || echo "не был запущен" ;;
  open)
    m="${2:-icon}"; d="$DATA/$m"
    [ -f "$d/gallery.html" ] || { echo "$m ещё не готов"; exit 1; }
    open "$d/gallery.html"; open "$d/REPORT.md" ;;
  *) sed -n '2,11p' "$0" ;;
esac
