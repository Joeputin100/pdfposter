#!/usr/bin/env python3
"""
RC60 — bake Imagen 4 upscale demo assets for the in-app comparison screen.

Runs Vertex AI imagen-4.0-upscale-preview on the 4 canonical demo source
images and writes the outputs as lossless PNG to:
  app/src/main/res/raw/{cat_shimmer,disco_chicken,earth,gristmill}_imagen.png

The output is downsampled to 2× source dimensions to match the existing
asset budget (PNG files in res/raw shouldn't bloat the APK; the screen-
side rendering only needs ~2× source res to show clear upscale fidelity).

Run with:
    cd backend/scripts
    python3 bake-imagen-demo.py

Auth: uses `gcloud auth print-access-token` for the OAuth2 bearer.
Caller must have roles/aiplatform.user (or roles/editor) on
static-webbing-461904-c4.

Cost: ~$0.12 total (4 calls × $0.03/call estimate). Cheap.
"""

from __future__ import annotations
import base64
import json
import subprocess
import sys
from io import BytesIO
from pathlib import Path

import requests
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent.parent
RAW_DIR = ROOT / "app/src/main/res/raw"
SUBJECTS = ["cat_shimmer", "disco_chicken", "earth", "gristmill"]

PROJECT = "static-webbing-461904-c4"
LOCATION = "us-central1"
MODEL = "imagen-4.0-upscale-preview"
ENDPOINT = (
    f"https://{LOCATION}-aiplatform.googleapis.com/v1"
    f"/projects/{PROJECT}/locations/{LOCATION}"
    f"/publishers/google/models/{MODEL}:predict"
)


def gcloud_access_token() -> str:
    out = subprocess.run(
        ["gcloud", "auth", "print-access-token"],
        capture_output=True, text=True, check=True,
    )
    return out.stdout.strip()


def read_source_as_base64(subject: str) -> tuple[str, tuple[int, int]]:
    """Read res/raw/<subject>_source.png and return its base64 + (w,h)."""
    path = RAW_DIR / f"{subject}_source.png"
    img = Image.open(path)
    size = img.size  # (w, h)
    buf = BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii"), size


def call_imagen(b64: str, scale: str = "x4") -> bytes:
    """Returns the upscaled PNG bytes via Imagen 4 upscale preview."""
    token = gcloud_access_token()
    body = {
        "instances": [
            {"prompt": "", "image": {"bytesBase64Encoded": b64}},
        ],
        "parameters": {
            "sampleCount": 1,
            "mode": "upscale",
            # PNG output is lossless; Vertex rejects compressionQuality on PNG
            # ("PNG does not accept compressionQuality" — empirically observed
            # 2026-05-25). compressionQuality only applies to JPEG.
            "outputOptions": {"mimeType": "image/png"},
            "upscaleConfig": {"upscaleFactor": scale},
        },
    }
    resp = requests.post(
        ENDPOINT,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
        },
        data=json.dumps(body),
        timeout=180,
    )
    if not resp.ok:
        # Surface the API's error body so we know what went wrong.
        raise RuntimeError(
            f"HTTP {resp.status_code} from Vertex Imagen: {resp.text[:600]}"
        )
    j = resp.json()
    preds = j.get("predictions") or []
    if not preds:
        raise RuntimeError(f"No predictions in response: {j}")
    first = preds[0]
    if "bytesBase64Encoded" not in first:
        raise RuntimeError(f"No bytesBase64Encoded in prediction: {first}")
    return base64.b64decode(first["bytesBase64Encoded"])


def downsample_to_2x_source(png_bytes: bytes, source_size: tuple[int, int]) -> bytes:
    """Resize the upscaled PNG down to 2× the source dimensions. Lossless PNG."""
    img = Image.open(BytesIO(png_bytes))
    target = (source_size[0] * 2, source_size[1] * 2)
    if img.size != target:
        img = img.resize(target, Image.Resampling.LANCZOS)
    buf = BytesIO()
    img.save(buf, format="PNG", optimize=True, compress_level=6)
    return buf.getvalue()


def main() -> int:
    print(f"Baking Imagen 4 demo assets to {RAW_DIR}...")
    failures = []
    for subject in SUBJECTS:
        out_path = RAW_DIR / f"{subject}_imagen.png"
        try:
            b64, size = read_source_as_base64(subject)
            print(f"  [{subject}] source={size[0]}x{size[1]} — calling Imagen 4...")
            raw = call_imagen(b64, scale="x4")
            final = downsample_to_2x_source(raw, size)
            out_path.write_bytes(final)
            print(f"  [{subject}] wrote {out_path.name} ({len(final)} bytes)")
        except Exception as e:
            print(f"  [{subject}] FAILED: {e}", file=sys.stderr)
            failures.append((subject, str(e)))
    if failures:
        print(f"\n{len(failures)}/{len(SUBJECTS)} subjects failed:", file=sys.stderr)
        for subj, msg in failures:
            print(f"  {subj}: {msg}", file=sys.stderr)
        print(
            "\nNote: UpscaleComparisonScreen has a 'fall back to Topaz's asset' "
            "path for missing files, so a partial bake is acceptable.",
            file=sys.stderr,
        )
        return 1
    print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
