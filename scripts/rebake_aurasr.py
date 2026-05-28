#!/usr/bin/env python3
"""Rebake AuraSR comparison demo assets with lossless WebP output.

Why: AuraSR's FAL endpoint defaults to JPEG, and we previously stored the
output as JPEG too — double-compression caused visible blurring on hard
edges. This script re-runs each comparison subject through fal-ai/aura-sr
with output_format=png, then encodes locally as WebP-lossless (smaller than
PNG, still byte-identical decode). The old `*_aurasr.jpg` is deleted so aapt
doesn't see duplicate raw resource names.

Usage:
    FAL_KEY=<your_key> python3 scripts/rebake_aurasr.py

Subjects rebaked: cat_shimmer, disco_chicken, gristmill, earth.
Source files: app/src/main/res/raw/<subject>_source.jpg
Output files: app/src/main/res/raw/<subject>_aurasr.webp (jpg deleted).
"""

import base64
import io
import os
import sys
import time
from pathlib import Path

import requests
from PIL import Image

# Pillow 10 moved LANCZOS to Image.Resampling; alias for backward compatibility.
LANCZOS = getattr(Image, "Resampling", Image).LANCZOS  # type: ignore[attr-defined]

FAL_KEY = os.environ.get("FAL_KEY")
if not FAL_KEY:
    print("FAL_KEY env var required", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = REPO_ROOT / "app" / "src" / "main" / "res" / "raw"
SUBJECTS = ["cat_shimmer", "disco_chicken", "gristmill", "earth"]

FAL_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/aura-sr"
FAL_QUEUE_STATUS = "https://queue.fal.run/fal-ai/aura-sr/requests/{}/status"
FAL_QUEUE_RESULT = "https://queue.fal.run/fal-ai/aura-sr/requests/{}"


def upload_to_fal(local_path: Path) -> str:
    """Upload a local image to FAL's storage and return the public URL."""
    with local_path.open("rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    data_url = f"data:image/jpeg;base64,{b64}"
    return data_url  # FAL accepts data URLs directly for image_url


def submit_aurasr(image_url: str) -> str:
    body = {
        "image_url": image_url,
        "upscaling_factor": 4,
        "output_format": "png",
    }
    r = requests.post(
        FAL_QUEUE_SUBMIT,
        headers={"Authorization": f"Key {FAL_KEY}", "Content-Type": "application/json"},
        json=body,
        timeout=30,
    )
    r.raise_for_status()
    return r.json()["request_id"]


def poll_until_done(request_id: str, timeout_s: int = 600) -> dict:
    start = time.time()
    while time.time() - start < timeout_s:
        r = requests.get(
            FAL_QUEUE_STATUS.format(request_id),
            headers={"Authorization": f"Key {FAL_KEY}"},
            timeout=15,
        )
        r.raise_for_status()
        status = r.json().get("status")
        if status == "COMPLETED":
            r2 = requests.get(
                FAL_QUEUE_RESULT.format(request_id),
                headers={"Authorization": f"Key {FAL_KEY}"},
                timeout=30,
            )
            r2.raise_for_status()
            return r2.json()
        if status in ("FAILED", "CANCELLED"):
            raise RuntimeError(f"FAL job {request_id} ended with status {status}")
        time.sleep(5)
    raise TimeoutError(f"FAL job {request_id} did not finish in {timeout_s}s")


def rebake(subject: str) -> None:
    src = RAW_DIR / f"{subject}_source.jpg"
    dst = RAW_DIR / f"{subject}_aurasr.webp"
    legacy_jpg = RAW_DIR / f"{subject}_aurasr.jpg"
    if not src.exists():
        print(f"[skip] {src} not found", file=sys.stderr)
        return

    print(f"[{subject}] uploading {src.stat().st_size // 1024} KB source")
    image_url = upload_to_fal(src)

    print(f"[{subject}] submitting to fal-ai/aura-sr (output_format=png)")
    request_id = submit_aurasr(image_url)

    print(f"[{subject}] polling request {request_id}")
    result = poll_until_done(request_id)

    upscaled_url = result["image"]["url"]
    print(f"[{subject}] downloading {upscaled_url}")
    img_bytes = requests.get(upscaled_url, timeout=120).content

    img = Image.open(io.BytesIO(img_bytes))
    long_side = max(img.size)
    if long_side > 2048:
        scale = 2048 / long_side
        new_size = (int(img.size[0] * scale), int(img.size[1] * scale))
        img = img.resize(new_size, LANCZOS)
    img.convert("RGB").save(dst, format="WEBP", lossless=True, quality=100)
    if legacy_jpg.exists():
        legacy_jpg.unlink()
        print(f"[{subject}] removed legacy {legacy_jpg.name}")
    print(f"[{subject}] wrote {dst} ({dst.stat().st_size // 1024} KB)")


def main() -> int:
    for subject in SUBJECTS:
        try:
            rebake(subject)
        except Exception as e:
            print(f"[{subject}] FAILED: {e}", file=sys.stderr)
            return 1
    print("\nAll AuraSR comparison assets rebaked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
