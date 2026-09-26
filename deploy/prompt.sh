#!/bin/bash
# Manage LLM prompts on the server through the local-only admin port.
# Runs curl ON the server over ssh, so the admin port never has to be exposed.
#
#   bash deploy/prompt.sh SERVER list
#   bash deploy/prompt.sh SERVER push   FILE "what changed"   # new version, NOT active
#   bash deploy/prompt.sh SERVER push!  FILE "what changed"   # new version, activated
#   bash deploy/prompt.sh SERVER activate ID                  # switch / roll back
#   bash deploy/prompt.sh SERVER reset                        # back to the built-in prompt
#   bash deploy/prompt.sh SERVER preview USER_ID [FILE] [--llm]  # render (and optionally run once)
#
#   Test personas (backend/internal/mock) — a week of data per situation, ending yesterday:
#   bash deploy/prompt.sh SERVER seed                         # (re)create mock_* users
#   bash deploy/prompt.sh SERVER eval [FILE] [--llm] [--prompt]  # run the prompt over every persona
#   bash deploy/prompt.sh SERVER purge                        # remove mock_* users
#   bash deploy/prompt.sh SERVER accuracy [DAYS]              # «Совпало / Не совсем» by prompt version
#
# Prompt name defaults to morning_system; override with PROMPT=day_review_system / weekly_system.
# Typical loop for a prompt change:
#   seed → eval draft.md --llm → compare with eval --llm (active) → push! → accuracy after a few days
set -euo pipefail

SERVER="${1:?Usage: $0 SERVER list|push|push!|activate|reset|preview|seed|eval|purge|accuracy …}"
CMD="${2:?command}"
NAME="${PROMPT:-morning_system}"

call() { # METHOD PATH [JSON]
    local method="$1" path="$2" body="${3:-}"
    printf '%s' "$body" | ssh "root@$SERVER" "
        set -a; . /opt/companion/.env; set +a
        curl -sS -X $method \"http://\${ADMIN_ADDR:-127.0.0.1:9090}$path\" \
             -H \"Authorization: Bearer \$ADMIN_TOKEN\" -H 'Content-Type: application/json' --data-binary @-"
    echo
}

case "$CMD" in
    list)
        call GET "/admin/prompts/$NAME" ;;
    push|push!)
        FILE="${3:?prompt file}"; NOTE="${4:-}"
        ACTIVATE=false; [ "$CMD" = "push!" ] && ACTIVATE=true
        BODY=$(python3 -c 'import json,sys; print(json.dumps({"body": open(sys.argv[1]).read(), "note": sys.argv[2], "activate": sys.argv[3] == "true"}))' "$FILE" "$NOTE" "$ACTIVATE")
        call POST "/admin/prompts/$NAME" "$BODY" ;;
    activate)
        call POST "/admin/prompts/$NAME/activate/${3:?version id}" ;;
    reset)
        call POST "/admin/prompts/$NAME/reset" ;;
    preview)
        USER_ID="${3:?user id}"; FILE=""; LLM=false
        for a in "${@:4}"; do [ "$a" = "--llm" ] && LLM=true || FILE="$a"; done
        BODY=$(python3 -c 'import json,sys; b=open(sys.argv[2]).read() if sys.argv[2] else ""; print(json.dumps({"user_id": sys.argv[1], "body": b, "call_llm": sys.argv[3] == "true"}))' "$USER_ID" "$FILE" "$LLM")
        call POST "/admin/prompts/$NAME/preview" "$BODY" ;;
    seed)
        call POST "/admin/mock/seed" ;;
    purge)
        call POST "/admin/mock/purge" ;;
    accuracy)
        call GET "/admin/accuracy?days=${3:-30}" ;;
    eval)
        FILE=""; LLM=false; WITH=false
        for a in "${@:3}"; do
            case "$a" in --llm) LLM=true ;; --prompt) WITH=true ;; *) FILE="$a" ;; esac
        done
        BODY=$(python3 -c 'import json,sys; b=open(sys.argv[1]).read() if sys.argv[1] else ""; print(json.dumps({"body": b, "call_llm": sys.argv[2] == "true", "with_prompt": sys.argv[3] == "true"}))' "$FILE" "$LLM" "$WITH")
        # Readable report: situation → signals → answer (length, safety) → what really happened
        call POST "/admin/prompts/$NAME/eval" "$BODY" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except ValueError:
    print(raw); sys.exit(1)
if "results" not in d:
    print(raw); sys.exit(1)
print("prompt:", d["prompt"], "(draft)" if d["draft"] else "(active)")
for r in d["results"]:
    print("\n== %s — %s" % (r["user_id"], r.get("title", "")))
    if r.get("error"):
        print("   ОШИБКА:", r["error"]); continue
    for s in r.get("signals") or []:
        print("   •", s["title"] + ":", s["detail"])
    if r.get("user"):
        print("   --- prompt ---\n   " + r["user"].replace("\n", "\n   "))
    if r.get("output"):
        flag = "" if r.get("safe") else "  ⚠ UNSAFE"
        print("   → %s\n     [%d симв., %d ток., %d мс]%s" % (r["output"], r.get("chars", 0), r.get("tokens", 0), r.get("latency_ms", 0), flag))
    if r.get("truth"):
        print("   на самом деле:", r["truth"])
' ;;
    *)
        echo "unknown command: $CMD" >&2; exit 2 ;;
esac
