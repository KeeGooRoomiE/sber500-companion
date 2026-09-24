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
# Prompt name defaults to morning_system; override with PROMPT=… .
set -euo pipefail

SERVER="${1:?Usage: $0 SERVER list|push|push!|activate|reset|preview …}"
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
    *)
        echo "unknown command: $CMD" >&2; exit 2 ;;
esac
