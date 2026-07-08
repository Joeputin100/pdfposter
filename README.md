<div align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/hero-dark.jpg">
  <img src="docs/readme-assets/hero-light.jpg" width="916" alt="Folio f.1r of an aged parchment codex, titled POSTER PDF — 'any picture, made monumental — tiled into leaves, printed at home'. Below the masthead, a Leonardo da Vinci-style invention study drawn in sepia iron-gall ink: a dot-matrix printer with a platen cylinder, supply roll of endless paper, and a gear train picked out with red-chalk construction circles. The press is printing a continuous tractor-feed sheet bearing the Mona Lisa, divided into fifteen tiles by dashed red cut lines. Handwritten Italian annotations with leader lines read 'il rullo — the platen that carries the leaf', 'la carta continua — the endless paper', 'rocchetti dentati — the gear train', 'la Gioconda, in fogli XV — the picture, divided into 15 leaves', 'fori di trascinamento', and 'scala — one braccio'; a magnified gear tooth is studied in the corner, and a line of mirrored writing runs along the bottom edge.">
</picture>
</div>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-8a6a3c?labelColor=4a3a26" alt="Platform: Android 8.0 and up">
  <img src="https://img.shields.io/badge/core-free%20%C2%B7%20offline-17808a?labelColor=4a3a26" alt="Core features: free and fully offline">
  <img src="https://img.shields.io/badge/privacy-no%20ads%20%C2%B7%20no%20tracking-a3432a?labelColor=4a3a26" alt="Privacy: no ads, no tracking">
  <img src="https://img.shields.io/badge/built%20with-Kotlin%20%C2%B7%20Compose-8a6a3c?labelColor=4a3a26" alt="Built with Kotlin and Jetpack Compose">
  <a href="https://github.com/Joeputin100/pdfposter/actions/workflows/build-android.yml"><img src="https://github.com/Joeputin100/pdfposter/actions/workflows/build-android.yml/badge.svg" alt="Build Android CI status"></a>
</p>

> **Poster PDF** turns any picture into a print-at-home poster: pick an image, choose a size,
> and the press hands you a tiled PDF — trim guides on every page, an assembly map at the end.

Five hundred years ago, making a picture monumental took a workshop, a wall, and an apprentice
with a grid. Poster PDF keeps the grid and retires the apprentice: the *machine* divides your
image into letter-size leaves, and any household printer becomes a poster press.

It is an Android app written in **Kotlin** with **Jetpack Compose** (Material 3), backed by
**Firebase** for the optional cloud features. The core — tiling, trim guides, the assembly
map, and on-device AI upscaling — is free, unwatermarked, and works with the network
switched off. Website: [posterpdf.web.app](https://posterpdf.web.app) · Film:
[the 40-second product video](https://www.youtube.com/watch?v=hQ0lYU83VVg).

## Study of proportion — how a picture becomes fifteen leaves

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/study-dark.jpg">
  <img src="docs/readme-assets/study-light.jpg" width="916" alt="Parchment plate f.2v: a sepia ink-wash Mona Lisa overlaid with a red-chalk proportion grid of three columns and five rows, each cell lettered A1 through C5 like the pages of a tiled poster, with construction diagonals and a dotted circle about the head. Beside it, four lines of chancery handwriting: 'XV fogli — fifteen letter pages, 3 across, 5 down'; 'ogni foglio porta segni di taglio — every leaf carries trim guides'; 'i lembi si sovrappongono — edges overlap, so scissors may err'; 'l'ultimo foglio è la mappa — the last page is the assembly map'. Below the notes, a small ink sketch of two overlapping pages with a hatched strip labelled 'il lembo — the overlap flap'. The caption reads PLATE II — LA GIOCONDA DIVIDED INTO FIFTEEN LEAVES, 24 × 36 IN.">
</picture>

What the plate records, in plain terms:

- A 24 × 36 in poster on letter paper comes out as **fifteen pages — 3 across, 5 down**.
- **Every page carries trim guides**, so you know exactly where to cut.
- **Edges overlap** by design, so scissors may err without ruining the poster.
- **The last page is the assembly map** — a little diagram of where every leaf goes.

Sizes are free-form — inches or centimetres, up to wall scale. The engine reckons the grain
of your picture (`original dpi → target dpi`) and warns you before a soft print, long before
ink touches paper.

| Poster | Leaves | Paper |
| --- | --- | --- |
| 18 × 24 in | 6 (3 × 2) | Letter, landscape |
| 24 × 36 in | 15 (3 × 5) | Letter, portrait |
| A0 (841 × 1189 mm) | 16 (4 × 4) | A4, portrait |

## The four folios

<table>
  <tr>
    <td width="50%">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/folio-i-dark.jpg">
        <img src="docs/readme-assets/folio-i-light.jpg" alt="Parchment folio card I, THE MACHINE — 'la macchina che divide' — with an ink sketch of a toothed gear: tiled PDF at any size up to wall-scale; trim guides and overlap flaps on every leaf; an assembly map rides on the last page.">
      </picture>
    </td>
    <td width="50%">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/folio-ii-dark.jpg">
        <img src="docs/readme-assets/folio-ii-light.jpg" alt="Parchment folio card II, THE EYE — 'l'occhio che affina' — with an ink sketch of an anatomical eye: on-device AI upscale runs on the phone itself, free and offline; your picture never leaves your hands.">
      </picture>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/folio-iii-dark.jpg">
        <img src="docs/readme-assets/folio-iii-light.jpg" alt="Parchment folio card III, THE SIX ENGINES — 'i sei motori del cielo' — with an ink sketch of six small wheels above a common shaft: six cloud upscalers for heroic enlargements, each priced live from the source, so you see the cost before you commission the work.">
      </picture>
    </td>
    <td width="50%">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/folio-iv-dark.jpg">
        <img src="docs/readme-assets/folio-iv-light.jpg" alt="Parchment folio card IV, THE WORKSHOP — 'la bottega è tua' — with an ink sketch of a drafting compass: no ads, no tracking, no image retention; the core is free, unwatermarked, and works with the network switched off.">
      </picture>
    </td>
  </tr>
</table>

- **Free core** — tiled PDF export, trim guides, assembly page. No watermark.
- **On-device AI upscale** — GPU-accelerated, streams big images without running out of memory.
- **Six cloud engines** — live per-model pricing; unavailable models hide themselves.
- **Ask Gemini** — an in-app advisor that reckons your pixels and suggests the right engine.
- **Private by construction** — no ads, no analytics, nothing stored after the job is done.

## The instrument itself

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/plate-studio-dark.jpg">
  <img src="docs/readme-assets/plate-studio-light.jpg" width="916" alt="Parchment plate f.3r with two phone screenshots in museum mats. Left: the main screen — 'Pick an image for your poster', an Ask Gemini bar, and a four-step 'How to get started' list (pick a high-resolution image, set your final poster dimensions, select paper size and orientation, view or save your print-ready PDF). Right: the poster-size screen with the Mona Lisa chosen, width 24 in and height 36 in linked, 'Original 10 DPI, Target 150 DPI', a pink 'Sharpen for print' suggestion, and Letter, A4 and Legal paper choices. Ink annotations with leader lines read 'the studio, awaiting a picture', 'the reckoning of grain — 10 dpi is made 150', and 'sizes in braccia, inches, or centimetres'. Caption: PLATE III — THE INSTRUMENT, AS IT APPEARS IN THE HAND.">
</picture>

*Plate III — the studio: pick a picture, set the finished size, and watch 10 dpi get reckoned into 150.*

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/plate-engines-dark.jpg">
  <img src="docs/readme-assets/plate-engines-light.jpg" width="916" alt="Parchment plate f.4v with two phone screenshots in museum mats. Left: the upscale picker warning 'This poster will print at low resolution — current 10 DPI at 24 by 36 inches', offering a free on-device upscale (about 40 DPI, offline, works without internet) beside the pixelated original, with cloud engines Recraft Crisp and AuraSR below. Right: the Ask Gemini sheet answering 'For a 24×36 poster at 150 DPI you'd want about 3600×5400 px (19 MP). Your image is 8 MP, so I'd suggest the Topaz Gigapixel model.' Ink annotations read 'the machine warns of a soft print', 'il consigliere — the advisor reckons your pixels', and 'each engine priced before it labours'. Caption: PLATE IV — THE ENGINES, AND THE ADVISOR WHO CHOOSES AMONG THEM.">
</picture>

*Plate IV — the engines: the app warns before a soft print, on-device upscale is free, and Gemini advises which cloud engine fits.*

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/plate-proof-dark.jpg">
  <img src="docs/readme-assets/plate-proof-light.jpg" width="916" alt="Parchment plate f.5r with two phone screenshots in museum mats. Left: the 'Compare AI upscalers' screen — subject chips (Disco chicken, Cat, Gristmill), model tabs (Topaz, Recraft, AuraSR, ESRGAN, CCSR), and an original-versus-upscaled slider across a brilliantly coloured feather picture. Right: the Getting Started page listing what you get for free — no catch (no ads, no watermarks, no reduced functionality), poster generation across multiple pages, all paper sizes, on-device upscale, 30-day cloud storage, history forever — with a fine-print note that cloud AI upscale is opt-in and uses credits. Ink annotations read 'il paragone — before and after, judged side by side', 'the compact — what is free, plainly said', and 'the fine print — cloud engines are opt-in, their price named first'. Caption: PLATE V — THE PROOF, AND THE COMPACT WITH THE READER.">
</picture>

*Plate V — the proof: compare every engine on the same picture, and read exactly what stays free.*

## Install

Poster PDF is in **closed test on Google Play** — the workshop opens to testers first.
The forty-second film below shows the press at work, from picture to pasted wall.

<div align="center">
<a href="https://www.youtube.com/watch?v=hQ0lYU83VVg">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/cta-dark.jpg">
  <img src="docs/readme-assets/cta-light.jpg" width="916" alt="Parchment call-to-action plate reading 'Print it huge.' in large serif capitals with 'huge' in teal chancery script, above a dark 'CLOSED TEST ON Google Play' badge with a teal play triangle and an outlined button reading 'watch the forty-second film'. Click to watch the film on YouTube.">
</picture>
</a>
</div>

- **Website:** [posterpdf.web.app](https://posterpdf.web.app)
- **The 40-second film:** [youtube.com/watch?v=hQ0lYU83VVg](https://www.youtube.com/watch?v=hQ0lYU83VVg)
- **Privacy policy:** [posterpdf.web.app/privacy-policy](https://posterpdf.web.app/privacy-policy)
- **Delete your account:** [posterpdf.web.app/delete-account](https://posterpdf.web.app/delete-account)

---

**Kotlin · Jetpack Compose · Firebase** — built in a small workshop, like all good machines.
The Mona Lisa is public domain; Leonardo was not consulted, but we believe he would have shipped.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme-assets/footer-dark.png">
  <img src="docs/readme-assets/footer-light.png" width="916" alt="Hand-inked footer ornament: a horizontal rule interrupted by a small dotted rosette, with a line of mirrored Leonardo-style writing beneath it reading 'finisce il codice — qui comincia il muro' — the codex ends; here begins the wall.">
</picture>

<p align="center">© 2026 Poster PDF — all rights reserved</p>
