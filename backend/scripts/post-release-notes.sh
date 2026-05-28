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
"What's new in v1.0-rc60 — Google Imagen joins the upscaler lineup" \
"[b]The big news this build:[/b] Google Imagen 4 is now an upscale option in the model picker, sitting between AuraSR and CCSR in the lineup.

[b]Why Imagen?[/b]
• [b]Pure-Google stack.[/b] Your image stays inside our Google Cloud project end-to-end — no third-party routing. Vertex AI on the same project our backend already runs on.
• [b]Photo-faithful super-resolution.[/b] Same precise-no-hallucination behavior as CCSR. We invoke Imagen with empty prompt + no editing parameters so the model upscales rather than reimagines.
• [b]2×, 3×, or 4× scale.[/b] DPI-aware picker selects the smallest scale that hits your target poster resolution, same logic as Topaz and CCSR.
• [b]Predictable flat pricing.[/b] ~3 credits per call regardless of poster size. Lands in the same price bracket as AuraSR and CCSR for typical outputs; cheaper than Topaz across the board.
• [b]Safety filters with refund protection.[/b] Google's enterprise safety classifier can occasionally false-positive on unusual poster graphics. When that happens you get a full credit refund automatically — your wallet is protected by Firestore-trigger refunds, same pattern as FAL failures.

[b]Limits to know about[/b]
• 17 megapixel output cap (the API's ceiling). Larger jobs are rejected before credits are charged with a friendly hint.
• Imagen 4 upscale is in preview status on Google's side; API behavior is stable as of ship but we've coded for graceful failure if Google evolves the schema.

Try it on the upscale picker on any low-DPI source image. The new card has 'Made by Google' baked into the marketing copy and ships translated in all 9 locales.

[b]Previous build highlights[/b] (RC55–RC59):
• Top bar: PFP no longer squishes to ellipse in portrait, Login button stays on one line, Google Sign-In cancel no longer shows a fake error.
• Image swap on relaunch actually replaces the viewport (RC57).
• Construction preview: outer-edge margin guides removed, single-page posters skip Tightening/Taping, landscape stack rotates as a unit (RC58–RC59).
• Model selection cards no longer clip behind the divider when tapped (RC57).

Thanks for the steady stream of bug reports — keep them coming."

echo "Done."
