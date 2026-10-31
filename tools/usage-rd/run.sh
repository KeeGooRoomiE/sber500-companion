#!/bin/bash
# usage-rd: исследования поведения пользователей по реальным данным (id хэшируются на сервере). Без Claude.
#   bash tools/usage-rd/run.sh export          выгрузить данные с сервера (ssh companion) в data/
#   bash tools/usage-rd/run.sh repeat          пункт 2: шаблонность между днями
#   bash tools/usage-rd/run.sh antifraud       пункт 5: репетиция антифрод-аудита (SQL-часть запускается на сервере: antifraud.sql)
#   bash tools/usage-rd/run.sh heavy           пункты 4 и 4.1: тяжёлые пользователи и долгожители
#   bash tools/usage-rd/run.sh timing          пункт 3: когда открывают и отмечают день (оценка часового пояса)
# (остальные скрипты добавляются по мере исследований; данные в data/ в git не попадают)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; mkdir -p "$HERE/data"
case "${1:-}" in
  export)
    scp -q -o ConnectTimeout=20 "$HERE/export.sql" companion:/tmp/ur_export.sql
    ssh -o ConnectTimeout=40 companion 'set -a; . /opt/companion/.env; set +a; psql "$DATABASE_URL" -q -f /tmp/ur_export.sql'
    for t in users morning calls reviews explore checkins activity daily; do scp -q -o ConnectTimeout=30 "companion:/tmp/ur_$t.jsonl" "$HERE/data/$t.jsonl"; done
    ssh companion 'rm -f /tmp/ur_*.jsonl /tmp/ur_export.sql'
    wc -l "$HERE"/data/*.jsonl ;;
  repeat) python3 "$HERE/repeat.py" ;;
  antifraud) python3 "$HERE/antifraud.py" ;;
  heavy) python3 "$HERE/heavy.py"; python3 "$HERE/longhist.py" ;;
  timing) python3 "$HERE/timing.py"; python3 "$HERE/timing2.py" ;;
  *) sed -n '2,6p' "$0" ;;
esac
