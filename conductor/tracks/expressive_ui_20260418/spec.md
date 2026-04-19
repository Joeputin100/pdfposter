# Specification: Expressive Material 3 Glassmorphism UI

## Overview
This track focuses on replacing the legacy XML-based UI with a highly polished, expressive Jetpack Compose interface. The design will heavily lean into "Glassmorphism," utilizing OpenGL-backed blur effects (`RenderEffect.createBlurEffect()` or Compose `Modifier.blur()`), advanced fluid animations, and a rich Material 3 color palette.

## Requirements
- **Framework:** Migrate to Jetpack Compose.
- **Design System:** Material Design 3 (Expressive).
- **Core Aesthetic:** Glassmorphism. UI layers (modals, sheets, floating cards) should feature a frosted glass appearance blurring the underlying content.
- **Motion:** High-quality UI animations (shared element transitions, spring-based physics, smooth scaling) to create a premium feel.
- **Features:**
  - Image picker for selecting the source poster image.
  - Interactive configuration forms (poster dimensions, paper size, margins, overlap) using glass-styled inputs.
  - Integration with the `PosterLogic` PDF generation.

## Out of Scope
- Full 3D rendering of the poster preview.
- Non-UI backend logic changes (unless strictly required for UI binding).
