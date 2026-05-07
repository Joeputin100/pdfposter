#!/usr/bin/env python3
"""
RC46 — bake comparison demo assets via real FAL upscale.

Background: res/raw/<subject>_<model>.{jpg,webp} were JPEG-compressed
re-encodes that violated the project's lossless-only rule for these
assets, and 6 combos had silently fallen back to "no upscale at all"
(see commit 8a4819d for the runtime fallback). This script rebakes ALL
20 combinations from the 4 source images by querying FAL at scale 4 for
each of {Topaz, Recraft, AuraSR, ESRGAN, CCSR}, then downsamples each
output to 2× source dimensions (matches the existing asset budget
convention) and saves as PNG for lossless storage.

Run with:
    cd backend/scripts
    python3 bake-comparison-assets.py

Reads FAL_KEY from gcloud Secret Manager (uses the gcloud CLI). Source
images are uploaded to FAL storage so we don't need a public URL.

Cost: ~$1 across all 20 jobs (Topaz dominates; gristmill at 4× is the
priciest single call at ~$0.45). Idempotent: existing PNGs at the
target paths get overwritten.
"""

from __future__ import annotations
import base64
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from io import BytesIO
from pathlib import Path

import requests
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent.parent
RAW_DIR = ROOT / "app/src/main/res/raw"

SUBJECTS = ["cat_shimmer", "disco_chicken", "earth", "gristmill"]
MODELS = {
    # name → (endpoint, body-builder)
    "topaz": (
        "fal-ai/topaz/upscale/image",
        lambda url: {"image_url": url, "upscale_factor": 4, "output_format": "png"},
    ),
    "recraft": (
        "fal-ai/recraft/upscale/crisp",
        lambda url: {"image_url": url, "output_format": "png"},
    ),
    "aurasr": (
        "fal-ai/aura-sr",
        lambda url: {"image_url": url, "upscaling_factor": 4, "output_format": "png"},
    ),
    "esrgan": (
        "fal-ai/esrgan",
        lambda url: {
            "image_url": url, "scale": 4,
            "model": "RealESRGAN_x4plus", "output_format": "png",
        },
    ),
    "ccsr": (
        "fal-ai/ccsr",
        lambda url: {"image_url": url, "scale": 4, "output_format": "png"},
    ),
}


def get_fal_key() -> str:
    out = subprocess.run(
        ["gcloud", "secrets", "versions", "access", "latest", "--secret=FAL_KEY"],
        capture_output=True, text=True, check=True,
    )
    return out.stdout.strip()


def upload_to_fal_storage(path: Path, fal_key: str) -> str:
    """Upload via FAL storage API (returns a fal.media URL).
    Endpoint: POST https://rest.alpha.fal.ai/storage/upload/initiate
    Two-step: initiate → upload to signed URL → return public URL.
    """
    # initiate
    init = requests.post(
        "https://rest.alpha.fal.ai/storage/upload/initiate",
        headers={"Authorization": f"Key {fal_key}", "Content-Type": "application/json"},
        json={
            "file_name": path.name,
            "content_type": "image/jpeg" if path.suffix == ".jpg" else "image/webp",
        },
        timeout=30,
    )
    init.raise_for_status()
    j = init.json()
    upload_url = j["upload_url"]
    file_url = j["file_url"]
    # upload bytes
    with open(path, "rb") as f:
        put = requests.put(
            upload_url, data=f.read(),
            headers={"Content-Type": "image/jpeg" if path.suffix == ".jpg" else "image/webp"},
            timeout=60,
        )
        put.raise_for_status()
    return file_url


def submit_and_wait(endpoint: str, body: dict, fal_key: str, label: str) -> bytes:
    """Submit to queue.fal.run, poll until COMPLETED, return result PNG bytes."""
    queue_url = f"https://queue.fal.run/{endpoint}"
    sub = requests.post(
        queue_url,
        headers={"Authorization": f"Key {fal_key}", "Content-Type": "application/json"},
        json=body, timeout=60,
    )
    sub.raise_for_status()
    submit = sub.json()
    request_id = submit["request_id"]
    status_url = submit.get("status_url") or f"{queue_url}/requests/{request_id}/status"
    response_url = submit.get("response_url") or f"{queue_url}/requests/{request_id}"

    deadline = time.monotonic() + 300  # 5min cap per job
    while time.monotonic() < deadline:
        s = requests.get(
            status_url, headers={"Authorization": f"Key {fal_key}"}, timeout=30,
        )
        s.raise_for_status()
        status = s.json().get("status")
        if status == "COMPLETED":
            break
        if status in ("FAILED", "CANCELLED"):
            raise RuntimeError(f"{label}: FAL job {status}: {s.text[:200]}")
        time.sleep(2)
    else:
        raise TimeoutError(f"{label}: timed out after 300s")

    r = requests.get(
        response_url, headers={"Authorization": f"Key {fal_key}"}, timeout=60,
    )
    r.raise_for_status()
    payload = r.json()
    # FAL returns various shapes depending on the model; common keys:
    # { "image": { "url": "..." } } or { "images": [{"url": ...}] }
    img_url = (
        (payload.get("image") or {}).get("url")
        or (payload.get("images") or [{}])[0].get("url")
        or payload.get("output_url")
    )
    if not img_url:
        raise RuntimeError(f"{label}: no output image in response: {payload}")
    img_resp = requests.get(img_url, timeout=120)
    img_resp.raise_for_status()
    return img_resp.content


def downsample_to_2x_source(output_bytes: bytes, source: Image.Image) -> Image.Image:
    """Decode FAL output and downsample to 2× source dimensions (preserves
    aspect ratio; uses Lanczos for sharp downscale)."""
    out = Image.open(BytesIO(output_bytes))
    if out.mode == "RGBA":
        out = out.convert("RGB")
    target_w = source.size[0] * 2
    target_h = source.size[1] * 2
    if out.size == (target_w, target_h):
        return out
    return out.resize((target_w, target_h), Image.Resampling.LANCZOS)


def bake_one(subject: str, model_name: str, source_url: str, fal_key: str) -> tuple[str, str, bytes | None, str]:
    label = f"{subject}/{model_name}"
    endpoint, body_fn = MODELS[model_name]
    body = body_fn(source_url)
    try:
        png_bytes = submit_and_wait(endpoint, body, fal_key, label)
        return subject, model_name, png_bytes, "ok"
    except Exception as e:
        return subject, model_name, None, f"FAIL: {e}"


def main():
    if not RAW_DIR.is_dir():
        print(f"ERR: raw dir not found: {RAW_DIR}", file=sys.stderr)
        sys.exit(1)
    fal_key = get_fal_key()
    print(f"FAL_KEY loaded ({len(fal_key)} chars)")
    print()

    # Upload each subject's source to FAL storage
    print("=== uploading sources to FAL storage ===")
    source_urls: dict[str, str] = {}
    sources: dict[str, Image.Image] = {}
    for subject in SUBJECTS:
        src_path = RAW_DIR / f"{subject}_source.jpg"
        if not src_path.is_file():
            print(f"  ! missing source: {src_path}")
            continue
        url = upload_to_fal_storage(src_path, fal_key)
        source_urls[subject] = url
        sources[subject] = Image.open(src_path)
        print(f"  {subject}: {url}")
    print()

    # Submit all 20 jobs in parallel (FAL handles concurrency fine)
    print("=== submitting 20 FAL jobs in parallel ===")
    results: dict[tuple[str, str], bytes] = {}
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = []
        for subject, src_url in source_urls.items():
            for model_name in MODELS:
                futures.append(ex.submit(bake_one, subject, model_name, src_url, fal_key))
        for fut in as_completed(futures):
            subj, mdl, data, status = fut.result()
            tag = f"{subj}/{mdl}"
            if status == "ok" and data is not None:
                results[(subj, mdl)] = data
                print(f"  ✓ {tag} ({len(data) / 1024:.0f} KB)")
            else:
                failures.append(f"{tag}: {status}")
                print(f"  ✗ {tag}: {status}")
    print()

    if failures:
        print("=== FAILURES ===")
        for f in failures:
            print(f"  {f}")

    # Downsample + save as PNG, delete old jpg/webp
    print("=== writing PNG outputs (2× source dims, lossless) ===")
    for (subject, model_name), png_bytes in results.items():
        out = downsample_to_2x_source(png_bytes, sources[subject])
        # Save as PNG (lossless)
        out_path = RAW_DIR / f"{subject}_{model_name}.png"
        out.save(out_path, "PNG", optimize=True)
        size_kb = out_path.stat().st_size / 1024
        print(f"  {subject}_{model_name}.png  {out.size[0]}×{out.size[1]}  {size_kb:.0f} KB")
        # Delete the old lossy version (if a different extension exists)
        for ext in (".jpg", ".webp"):
            old = RAW_DIR / f"{subject}_{model_name}{ext}"
            if old.is_file():
                old.unlink()

    # Also re-save sources as PNG (they were lossy JPEG too)
    print()
    print("=== re-saving source images as PNG ===")
    for subject, im in sources.items():
        out_path = RAW_DIR / f"{subject}_source.png"
        if im.mode == "RGBA":
            im = im.convert("RGB")
        im.save(out_path, "PNG", optimize=True)
        size_kb = out_path.stat().st_size / 1024
        print(f"  {subject}_source.png  {im.size[0]}×{im.size[1]}  {size_kb:.0f} KB")
        old_jpg = RAW_DIR / f"{subject}_source.jpg"
        if old_jpg.is_file():
            old_jpg.unlink()

    print()
    print(f"Done. {len(results)} upscaled assets baked; {len(sources)} sources re-saved as PNG.")


if __name__ == "__main__":
    main()
