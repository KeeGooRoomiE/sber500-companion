#!/bin/bash
# Send a push notification to phones, by hand, through the local-only admin port.
# Runs curl ON the server over ssh, so the admin port never has to be exposed.
#
#   bash deploy/push.sh SERVER who                          # who can be reached right now
#   bash deploy/push.sh SERVER dry   "Title" "Body"         # resolve the audience, send nothing
#   bash deploy/push.sh SERVER all   "Title" "Body"         # send to everyone with a token
#   bash deploy/push.sh SERVER user  u_abc "Title" "Body"   # send to one person
#
# TYPE sets what the app does with it (default custom):
#   morning     — show as the forecast and mark the day delivered (no second copy later)
#   day_review  — nudge to open «Разбор дня»
#   update      — a new APK is out
#   custom      — plain notification
#
#   TYPE=day_review bash deploy/push.sh SERVER all "Разбор дня готов" "Загляни — посмотрим, как прошёл день."
#
# Always dry-run first: this reaches every phone at once and cannot be recalled.
set -euo pipefail

SERVER="${1:?Usage: $0 SERVER who|dry|all|user …}"
CMD="${2:?command}"
TYPE="${TYPE:-custom}"

call() { # JSON
    printf '%s' "$1" | ssh "root@$SERVER" "
        set -a; . /opt/companion/.env; set +a
        curl -sS -X POST \"http://\${ADMIN_ADDR:-127.0.0.1:9090}/admin/push\" \
             -H \"Authorization: Bearer \$ADMIN_TOKEN\" -H 'Content-Type: application/json' --data-binary @-"
    echo
}

# json_body TITLE BODY DRY [USER_ID]
json_body() {
    TITLE="$1" BODY="$2" DRY="$3" UID_ARG="${4:-}" TYPE="$TYPE" python3 -c '
import json, os
msg = {
    "title": os.environ["TITLE"],
    "body": os.environ["BODY"],
    "type": os.environ["TYPE"],
    "dry_run": os.environ["DRY"] == "1",
}
if os.environ.get("UID_ARG"):
    msg["user_ids"] = [os.environ["UID_ARG"]]
print(json.dumps(msg, ensure_ascii=False))'
}

case "$CMD" in
    who)
        call "$(json_body "probe" "probe" 1)" ;;
    dry)
        call "$(json_body "${3:?title}" "${4:?body}" 1)" ;;
    all)
        TITLE="${3:?title}"; BODY="${4:?body}"
        echo "About to send «$TITLE» to EVERY phone with a token (type=$TYPE)."
        read -r -p "Type SEND to confirm: " ok
        [ "$ok" = "SEND" ] || { echo "aborted"; exit 1; }
        call "$(json_body "$TITLE" "$BODY" 0)" ;;
    user)
        call "$(json_body "${4:?title}" "${5:?body}" 0 "${3:?user id}")" ;;
    *)
        echo "unknown command: $CMD" >&2; exit 1 ;;
esac
