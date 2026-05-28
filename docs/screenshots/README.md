# Screenshot capture script

This directory holds the screenshots referenced from the top-level
README + the Play Store listing.

## What to capture

The eight screenshots below tell the app's story end-to-end. Use a
modern phone (Pixel 7+, Galaxy S22+) in **portrait** mode unless
otherwise noted, and capture at the device's native resolution (no
manual cropping). Then drop the PNGs into this directory with the
filenames listed.

| File | What's on screen | How to capture |
|------|------------------|----------------|
| `01-main-empty.png` | Main screen, no image picked, settings drawer closed | Boot the app fresh, dismiss the splash, screenshot the empty state |
| `02-image-picked.png` | Same screen with an image loaded — should show the live preview, DPI chip, sharpen-for-print CTA | Pick a 12 MP photo of something colorful (a logo or product shot is ideal) |
| `03-construction-cycle.png` | Mid-animation frame from the assembly cycle, ideally during the Taping phase with one tape strip applied | Tap the preview to open the construction-cycle viewer and screenshot during the tape phase |
| `04-low-dpi-modal.png` | The low-DPI upgrade modal showing the 6 model cards (NONE / FREE / RECRAFT / ESRGAN / AURASR / TOPAZ) | Pick a low-resolution photo (≤1 MP), then tap "Sharpen for print" |
| `05-compare-models.png` | The comparison demo screen with the slider midway and the gristmill subject + Recraft model selected | Open the modal → "Help me decide" → pick Gristmill + Recraft → drag the slider to roughly the middle |
| `06-settings-drawer.png` | The drawer open showing all sections (account, units, language, debug logging, etc.) | From any screen, swipe in from the left edge or tap the menu button |
| `07-history.png` | The history screen with at least 3 cloud-stored posters listed | Generate 3-4 posters, sign in to enable cloud sync, open History from the drawer |
| `08-final-pdf-preview.png` *(landscape)* | The PDF viewer showing the assembly guide page (rulers + grid + diagram) | Generate a poster, tap View, screenshot the assembly-guide page |

## Hero image (Play Store + GitHub Social Preview)

The hero is **1280×640 PNG**, used as the GitHub Social Preview image
and as the Play Store feature graphic. It should be a hero shot of the
**Reveal phase of the assembly cycle**, with all four thumbtacks pinned
and the construction-paper background visible. File:
`hero-1280x640.png`.

This requires either a screen recording sliced at the right frame or a
manually-staged Compose preview rendered at 1280×640.

## Animated GIF

The 5-second clip `assembly-cycle-loop.gif` (≤5 MB, 30 fps) is the most
photogenic asset — it shows the 30-second assembly cycle accelerated 6×
into a single loop. Capture via `adb shell screenrecord` for ≥30 s,
then convert with ffmpeg:

```sh
ffmpeg -i raw.mp4 -vf "fps=30,scale=540:-1:flags=lanczos,setpts=PTS/6" \
  -loop 0 assembly-cycle-loop.gif
```

## Naming + sizes

- All PNGs lossless (no JPEG artifacts).
- Don't include device chrome (status bar / nav bar can stay; physical
  device frame: don't add).
- Use the device's natural pixel density — don't downscale before
  saving. The Play Store listing will resize as needed.

## Update flow

When the UI changes meaningfully, regenerate the affected screenshot.
The README references each by relative path, so swapping a PNG in
place updates both the GitHub readme + anywhere else they appear.
