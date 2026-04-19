# Implementation Plan: UX Refinements & Advanced Polish

## Phase 1: UX and Settings
- [ ] Task: Navigation & Wizard
    - [ ] Implement `BackHandler` in `MainScreen` to close the `drawerState`.
    - [ ] Update `FirstRunWizard` to provide interactive inputs that save to `MainViewModel`.
    - [ ] Fix `resetToDefaults()` in `MainViewModel` to aggressively clear state.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: UX and Settings' (Protocol in workflow.md)

## Phase 2: Image Logic and Geometry
- [ ] Task: Aspect Ratio Fixes
    - [ ] Format aspect ratio string as `X.X:Y.Y` with a `TooltipBox` on hover/long-press.
    - [ ] Debug and fix the `isAspectRatioLocked` math in `updatePosterWidth` and `updatePosterHeight`.
- [ ] Task: Enhanced Paper Selection
    - [ ] Add `Legal` and `Tabloid` to `PaperSizeSelector`.
    - [ ] Add an `Orientation` dropdown (Best Fit, Portrait, Landscape) to `MainViewModel`.
    - [ ] Update `getPaneCount` and `generatePoster` to respect Orientation.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Image Logic and Geometry' (Protocol in workflow.md)

## Phase 3: Visual Polish and Preview
- [ ] Task: UI Adjustments
    - [ ] Increase `LineStyleIcon` Canvas size and stroke.
    - [ ] Verify `Theme.kt` and `themes.xml` for complete Dark Mode compliance.
- [ ] Task: Animated Canvas Preview
    - [ ] Refactor `animateFloatAsState` to `rememberInfiniteTransition` in `PosterPreview`.
    - [ ] Pass the `selectedImageUri` to `PosterPreview` and use `AsyncImagePainter` or `ImageBitmap` to draw the image onto the tiles.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Visual Polish and Preview' (Protocol in workflow.md)

## Phase 4: Streamlined Generation and PDF Output
- [ ] Task: Workflow Consolidation
    - [ ] Remove the "Generate" button.
    - [ ] Update `View` and `Save As` buttons to `await` generation, showing `CircularProgressIndicator` during processing.
- [ ] Task: PDF Instructions Diagram
    - [ ] Update `PosterLogic.addInstructionsPage` to draw a miniature grid of the tiles using `PDPageContentStream`.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Streamlined Generation and PDF Output' (Protocol in workflow.md)
