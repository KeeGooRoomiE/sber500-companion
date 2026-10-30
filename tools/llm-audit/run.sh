#!/bin/bash
# Аудит качества LLM-выходов. Запускается с вашего компьютера, без Claude.
#
#   bash tools/llm-audit/run.sh export    выгрузить выходы+факты с сервера (ssh companion) в data/
#   bash tools/llm-audit/run.sh start     запустить аудит волнами в фоне (докачивает с места остановки)
#   bash tools/llm-audit/run.sh status    прогресс и последние строки лога
#   bash tools/llm-audit/run.sh summary   сводка: категории, динамика по неделям, согласие judge
#   bash tools/llm-audit/run.sh stop      остановить (ничего не теряется, start продолжит)
#
# Ключ и адрес прокси берутся из backend/.env (в репозиторий не попадают).
# Параметры: WORKERS (по умолчанию 4), WAVE (по 10 текстов на категорию за волну), JUDGES.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DATA="$HERE/data"; mkdir -p "$DATA"

env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }

case "${1:-}" in
  export)
    scp -q -o ConnectTimeout=20 "$HERE/export.sql" companion:/tmp/export.sql
    ssh -o ConnectTimeout=30 companion 'set -a; . /opt/companion/.env; set +a; psql "$DATABASE_URL" -q -f /tmp/export.sql && wc -l /tmp/audit_export.jsonl'
    scp -q -o ConnectTimeout=30 companion:/tmp/audit_export.jsonl "$DATA/audit_input.jsonl"
    ssh companion 'rm -f /tmp/audit_export.jsonl /tmp/export.sql'
    echo "выгружено: $(wc -l < "$DATA/audit_input.jsonl") текстов -> $DATA/audit_input.jsonl"
    ;;
  start)
    [ -f "$DATA/audit_input.jsonl" ] || { echo "нет data/audit_input.jsonl — сначала: run.sh export"; exit 1; }
    if pgrep -f "audit_waves.py" >/dev/null; then echo "уже запущен (run.sh status)"; exit 0; fi
    export LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)"
    [ -n "$LLM_API_KEY" ] || { echo "LLM_API_KEY не найден в backend/.env"; exit 1; }
    : "${WORKERS:=4}" "${WAVE:=10}"; export WORKERS WAVE
    # caffeinate не даёт маку уснуть, пока идёт аудит
    nohup caffeinate -i bash -c "for p in 1 2 3 4 5 6; do python3 -u '$HERE/audit_waves.py'; done" >> "$DATA/waves.log" 2>&1 &
    echo "запущен (PID $!). Прогресс: bash tools/llm-audit/run.sh status"
    ;;
  status)
    if pgrep -f "audit_waves.py" >/dev/null; then echo "статус: РАБОТАЕТ"; else echo "статус: не запущен"; fi
    python3 - "$DATA" <<'PY'
import json, sys, collections
d = sys.argv[1]
try: rows = [json.loads(l) for l in open(d + "/audit_results.jsonl", encoding="utf-8")]
except FileNotFoundError: print("результатов пока нет"); raise SystemExit
ok = [r for r in rows if not r.get("err")]
pairs = {(r["id"], r["judge"]) for r in ok}
texts = collections.Counter(r["id"] for r in ok)
print(f"готово оценок: {len(pairs)} | текстов с двумя оценками: {sum(1 for v in texts.values() if v == 2)} | сетевых ошибок в логе: {len(rows) - len(ok)}")
PY
    echo "--- лог ---"; tail -4 "$DATA/waves.log" 2>/dev/null || true
    ;;
  summary) python3 "$HERE/summarize.py" ;;
  stop) pkill -f "audit_waves.py" && echo "остановлен" || echo "не был запущен" ;;
  *) sed -n '2,12p' "$0" ;;
esac
