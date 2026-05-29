# UX Edge-Case Findings (rc77 capture → fix-RC input)

**Date:** 2026-05-29 · **Scope:** audit of the rc77 edge-case captures (emulator `screenshots.yml` matrix + the real arcfox/moto-razr+ flip via FTL). Capture + audit only — **fixes land in a follow-up RC** (per the UX-edge spec). Companion: [predictive-back-audit.md](predictive-back-audit.md) (verdict: predictive-back SAFE).

## Coverage
| Config | What it stresses | Status |
|---|---|---|
| baseline (API 33 emu) | reference | reference only |
| **font360** (360 dp width + 200% font) | small screen + max accessibility font | ✅ **fully audited (5/5 screens)** |
| cutout (display-notch overlay) | notch / edge insets (proxy for curved/edge displays) | ⏳ spot-check pending |
| **flip** (arcfox / moto razr+, API 34) | odd aspect ratio / foldable | ✅ main audited (clean); other 4 pending |

## Findings (font360 — 360 dp + 200% font)

| # | Screen(s) | Issue | Severity | Recommended fix |
|---|---|---|---|---|
| 1 | main, model_picker, compare (any screen with the top bar) | **"Login / Sign Up" truncates → "Login / Sig"** — cut mid-word, no ellipsis | High (looks broken, on the most-seen surface) | Design choice — see options below |
| 2 | settings | **Nav drawer is not opaque** — the main screen bleeds through behind the settings content; **and** the top item ("Default Paper Size") is clipped under the status bar | High (unreadable overlap) | Set an opaque `drawerContainerColor` on the `ModalDrawerSheet` (currently transparent/translucent); add a status-bar inset/padding to the drawer's top |
| 3 | model_picker (the low-DPI upgrade modal) | **Model-card descriptions clip at the card bottom** ("Visible pixelation at…", "…works" cut off) — fixed-height cards don't grow at max font | Medium | Let the cards size to content (wrap-content height / min-height instead of fixed), or make the card body scroll, or trim the copy |
| — | compare, getting_started | No issue — adaptive/scrollable layouts absorb 200% font gracefully | — | — |

**Pattern:** breakage is confined to **fixed-dimension chrome** (the top-bar button, the model cards). Scrollable/flex screens are fine. The fix RC should target those containers specifically, not a global type change.

## Issue 1 — top-bar button truncation: design options
- **A. Two-line wrap** inside the pill — simplest; keeps the full "Login / Sign Up" label. (Recommended.)
- **B. Shorten the label** to "Sign in" (or "Log in") — fits one line at 200% font, fewer words to localize.
- **C. Icon-only (account/person glyph)** when the available width is below a threshold — most compact, but less discoverable for a primary CTA.
- **D. Let the "PosterPDF" title shrink/scale** to free width for the button — preserves both but risks the title looking cramped.

## flip (arcfox) — main screen
Clean: full top bar (not truncated at normal font), Gemini row, pick-image card, all 4 getting-started steps, correct top + bottom (gesture-nav) insets. No odd-AR breakage observed on main.

## Not yet deep-audited (next pass)
cutout set (5), flip non-main (settings/model_picker/compare/getting_started), baseline (reference). The cutout config is the proxy for curved/edge "wrap-around" displays; the app already handles edge-to-edge + a status-bar inset (RC36-1), so issues there are less likely but unconfirmed.

## Related release note
The new cloud-gate + long-job modal strings were AI-translated to 9 locales (rc78); a **native-speaker QA pass** on those is advisable before public release (esp. the polite/gentle cloud-gate tone).
