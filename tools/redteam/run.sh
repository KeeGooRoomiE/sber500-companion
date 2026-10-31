#!/bin/bash
# Красная команда: инъекции, кризисные фразы, PII, раздутые поля. Без Claude.
#   bash tools/redteam/run.sh [all|1|2]    1 = матрица нагрузок, 2 = цена раздутого поля tags
# Нужны данные prompt-lab (bash tools/prompt-lab/run.sh fetch morning_system; ... fetch day_review_system).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
env_get() { grep "^$1=" "$ROOT/backend/.env" | head -1 | cut -d= -f2- | tr -d "\"' \r"; }
mkdir -p "$HERE/data"
LLM_API_KEY="$(env_get LLM_API_KEY)" LLM_BASE_URL="$(env_get LLM_BASE_URL)" python3 -u "$HERE/redteam.py" "${1:-all}"
