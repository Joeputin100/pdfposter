#!/usr/bin/env bash
# RC59 — post a Release Notes update to the community board covering
# the in-flight bug-fix RCs since the original RC44 seed.
#
# Modeled on seed-community.sh (same auth path, same payload shape).
# Idempotency: each run creates a new post — if you need to refresh
# the text, soft-delete the previous one in-app first, then re-run.

set -euo pipefail

PROJECT=static-webbing-461904-c4
DB="(default)"
URL_BASE="https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/${DB}/documents/community/posts/items"

TOKEN=$(gcloud auth print-access-token)
NOW=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)

json_escape() {
    python3 -c "import json,sys;print(json.dumps(sys.stdin.read()), end='')"
}

post() {
    local topic=$1 title=$2 body=$3
    local body_json title_json
    body_json=$(printf '%s' "$body" | json_escape)
    title_json=$(printf '%s' "$title" | json_escape)
    local payload
    payload=$(cat <<EOF
{
  "fields": {
    "uid":         { "stringValue": "pdfposter-team" },
    "authorName":  { "stringValue": "PDF Poster Team" },
    "topic":       { "stringValue": "${topic}" },
    "title":       { "stringValue": ${title_json} },
    "body":        { "stringValue": ${body_json} },
    "createdAt":   { "timestampValue": "${NOW}" }
  }
}
EOF
)
    local response
    response=$(curl -sS -X POST "${URL_BASE}" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "${payload}")
    local id
    id=$(printf '%s' "${response}" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('name','??').rsplit('/',1)[-1])" 2>/dev/null || echo "??")
    echo "  [${topic}] ${id} — ${title}"
}

echo "Posting Release Notes update to project ${PROJECT}..."

post release_notes \
"What's new in v1.0-rc59" \
"[b]A round-up of the bug-fix builds shipped over the last few days.[/b] If you've been on an earlier RC, the highlights below are the user-visible changes you'll see when you update.

[b]Top bar (RC55–RC56)[/b]
• Profile photo no longer squishes to an ellipse on narrow portrait screens — wordmark made smaller, avatar locked to a true 36dp circle.
• [i]Login / Sign Up[/i] button shows its full label again — the credit chip is now hidden when signed-out so the button has room.
• Cancelling Google Sign-In is silent. No more [i]\"Sign-in failed: 12501:\"[/i] toast next to a [i]\"Signed in\"[/i] message after a successful retry.

[b]Image picker + Free upscale (RC55, RC57)[/b]
• Picking a new source image after a relaunch actually replaces the viewport — previously the URI looked identical to the OS so the preview kept the old bitmap.
• Free upscale no longer crashes when run right after swapping the source image. Stale resume state from a prior upscale is now cleared on import, and the upscaler validates tile counts before resuming.
• Source image survives process death. The OS killing the app in the background no longer drops the picture.

[b]Construction preview (RC58–RC59)[/b]
• Margin guide lines only render on edges that face a neighboring page. Outer edges of the poster — where nothing gets taped — are clean.
• Single-page posters skip the [i]closing the gaps[/i] and [i]taping seams[/i] phases. The cycle is 38 seconds instead of 47 with no visual stalls.
• Landscape posters print as a portrait stack, the stack lifts out of the tray, and then the whole stack rotates 90° together. Pages no longer spin out one at a time.
• Printer body sits closer to the top edge of the table during printing.

[b]Model selection cards (RC57)[/b]
• Top-row cards no longer clip behind the divider when tapped. The grid is on a higher z-index, so the selection scale-up renders on top.

[b]Other in-flight items (RC55)[/b]
• Cancelling an upscale now correctly dismisses the notification.

Thanks for the detailed bug reports — most of these started as a single screenshot or log line you sent in. Keep them coming."

echo "Done."
