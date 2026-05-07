#!/usr/bin/env python3
"""
RC46 — retry the 7 failed bakes from bake-comparison-assets.py with:
  - 600s timeout (CCSR is slow; gristmill/aurasr too)
  - Recraft pre-downscale (gristmill source 2048×1365 hits Recraft's
    input cap → 422; downscale to 1024 max dim before submitting)
  - Single-job retry on IncompleteRead transients

Targets only:
  cat_shimmer/ccsr
  disco_chicken/ccsr
  disco_chicken/aurasr
  earth/ccsr
  gristmill/ccsr
  gristmill/aurasr
  gristmill/recraft
"""

from __future__ import annotations
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

JOBS = [
    ("cat_shimmer", "ccsr"),
    ("disco_chicken", "ccsr"),
    ("disco_chicken", "aurasr"),
    ("earth", "ccsr"),
    ("gristmill", "ccsr"),
    ("gristmill", "aurasr"),
    ("gristmill", "recraft"),
]

MODELS = {
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
        lambda url: {"image_url": url, "scale": 4, "model": "RealESRGAN_x4plus", "output_format": "png"},
    ),
    "ccsr": (
        "fal-ai/ccsr",
        lambda url: {"image_url": url, "scale": 4, "output_format": "png"},
    ),
}

# Recraft has an input-size cap (~1024×1024). Larger inputs return 422.
RECRAFT_MAX_INPUT_DIM = 1024

# Timeout per job (seconds). CCSR + aurasr at high MP need >5min.
JOB_TIMEOUT = 600


def get_fal_key() -> str:
    out = subprocess.run(
        ["gcloud", "secrets", "versions", "access", "latest", "--secret=FAL_KEY"],
        capture_output=True, text=True, check=True,
    )
    return out.stdout.strip()


def upload_to_fal_storage(path_or_bytes, name_hint: str, fal_key: str, content_type: str = "image/png") -> str:
    init = requests.post(
        "https://rest.alpha.fal.ai/storage/upload/initiate",
        headers={"Authorization": f"Key {fal_key}", "Content-Type": "application/json"},
        json={"file_name": name_hint, "content_type": content_type},
        timeout=30,
    )
    init.raise_for_status()
    j = init.json()
    upload_url = j["upload_url"]
    file_url = j["file_url"]
    if isinstance(path_or_bytes, Path):
        with open(path_or_bytes, "rb") as f:
            data = f.read()
    else:
        data = path_or_bytes
    put = requests.put(
        upload_url, data=data,
        headers={"Content-Type": content_type},
        timeout=120,
    )
    put.raise_for_status()
    return file_url


def submit_and_wait(endpoint: str, body: dict, fal_key: str, label: str, timeout_s: int) -> bytes:
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

    deadline = time.monotonic() + timeout_s
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
        time.sleep(3)
    else:
        raise TimeoutError(f"{label}: timed out after {timeout_s}s")

    # download with retry on IncompleteRead
    last_err = None
    for attempt in range(3):
        try:
            r = requests.get(
                response_url, headers={"Authorization": f"Key {fal_key}"}, timeout=60,
            )
            r.raise_for_status()
            payload = r.json()
            img_url = (
                (payload.get("image") or {}).get("url")
                or (payload.get("images") or [{}])[0].get("url")
                or payload.get("output_url")
            )
            if not img_url:
                raise RuntimeError(f"{label}: no output image: {payload}")
            img_resp = requests.get(img_url, timeout=180)
            img_resp.raise_for_status()
            return img_resp.content
        except (requests.exceptions.ChunkedEncodingError,
                requests.exceptions.ConnectionError) as e:
            last_err = e
            time.sleep(2 ** attempt)
    raise RuntimeError(f"{label}: download retries exhausted: {last_err}")


def downsample_to_2x_source(output_bytes: bytes, source: Image.Image) -> Image.Image:
    out = Image.open(BytesIO(output_bytes))
    if out.mode == "RGBA":
        out = out.convert("RGB")
    target_w = source.size[0] * 2
    target_h = source.size[1] * 2
    if out.size == (target_w, target_h):
        return out
    return out.resize((target_w, target_h), Image.Resampling.LANCZOS)


def make_recraft_input(source_path: Path, fal_key: str) -> str:
    """Recraft's crisp upscaler caps input at ~1024 max dim; larger inputs
    return 422. Pre-downscale via Lanczos, upload as PNG, return URL."""
    im = Image.open(source_path)
    if im.mode == "RGBA":
        im = im.convert("RGB")
    if max(im.size) > RECRAFT_MAX_INPUT_DIM:
        ratio = RECRAFT_MAX_INPUT_DIM / max(im.size)
        new_size = (int(im.size[0] * ratio), int(im.size[1] * ratio))
        im = im.resize(new_size, Image.Resampling.LANCZOS)
    buf = BytesIO()
    im.save(buf, "PNG", optimize=False)
    return upload_to_fal_storage(buf.getvalue(), source_path.stem + "_for_recraft.png", fal_key)


def bake_one(subject: str, model_name: str, fal_key: str) -> tuple[str, str, bytes | None, str]:
    label = f"{subject}/{model_name}"
    src_path = RAW_DIR / f"{subject}_source.png"
    if not src_path.is_file():
        # Fall back to .jpg if PNG hasn't been written yet (first-pass run)
        src_path = RAW_DIR / f"{subject}_source.jpg"
    try:
        # Special-case Recraft for oversized input
        if model_name == "recraft":
            src_url = make_recraft_input(src_path, fal_key)
        else:
            src_url = upload_to_fal_storage(
                src_path, src_path.name, fal_key,
                "image/png" if src_path.suffix == ".png" else "image/jpeg",
            )
        endpoint, body_fn = MODELS[model_name]
        body = body_fn(src_url)
        png_bytes = submit_and_wait(endpoint, body, fal_key, label, JOB_TIMEOUT)
        return subject, model_name, png_bytes, "ok"
    except Exception as e:
        return subject, model_name, None, f"FAIL: {e}"


def main():
    fal_key = get_fal_key()
    print(f"FAL_KEY loaded ({len(fal_key)} chars). Retrying {len(JOBS)} jobs at {JOB_TIMEOUT}s timeout...")
    print()

    sources: dict[str, Image.Image] = {}
    for subject in {s for s, _ in JOBS}:
        src_path = RAW_DIR / f"{subject}_source.png"
        if not src_path.is_file():
            src_path = RAW_DIR / f"{subject}_source.jpg"
        sources[subject] = Image.open(src_path)

    results: dict[tuple[str, str], bytes] = {}
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=4) as ex:
        futures = [ex.submit(bake_one, s, m, fal_key) for s, m in JOBS]
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
        print("=== STILL FAILING ===")
        for f in failures:
            print(f"  {f}")
        print()

    print("=== writing PNG outputs (2× source dims, lossless) ===")
    for (subject, model_name), png_bytes in results.items():
        out = downsample_to_2x_source(png_bytes, sources[subject])
        out_path = RAW_DIR / f"{subject}_{model_name}.png"
        out.save(out_path, "PNG", optimize=True)
        size_kb = out_path.stat().st_size / 1024
        print(f"  {subject}_{model_name}.png  {out.size[0]}×{out.size[1]}  {size_kb:.0f} KB")
        # Delete any leftover lossy version
        for ext in (".jpg", ".webp"):
            old = RAW_DIR / f"{subject}_{model_name}{ext}"
            if old.is_file():
                old.unlink()

    print()
    print(f"Done. {len(results)} retried; {len(failures)} still failing.")


if __name__ == "__main__":
    main()
