# Implementation Plan: Final UI/UX Refinements

## Phase 1: Core Logic, Theme, & Navigation
- [ ] Task: Fix Settings & Navigation
    - [ ] Update `FirstRunWizard` to use a ViewModel to actively apply and save changes.
    - [ ] Implement `BackHandler` in `MainScreen` to cleanly close the drawer.
    - [ ] Add "Default Paper Size" selector to the settings drawer.
- [ ] Task: Fix Theme & Aspect Ratio Logic
    - [ ] Update `GlassCard` and theme setups to ensure Dark Mode works correctly.
    - [ ] Fix the `MainViewModel` aspect ratio lock logic so it respects the loaded image's true aspect ratio, not the default poster dimensions.
    - [ ] Ensure Metric/Inches toggle updates the UI labels dynamically.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Core Logic, Theme, & Navigation' (Protocol in workflow.md)

## Phase 2: UI Polish & Onboarding
- [ ] Task: Enhanced UI Elements
    - [ ] Convert "Change Image" text to a prominent `FilledTonalButton`.
    - [ ] Move Orientation chips below the "Orientation" label for better spacing.
    - [ ] Increase size and stroke of `LineStyleIcon` elements.
    - [ ] Implement `AlertDialog` tooltips for Aspect Ratio, Margin, and Overlap info icons.
- [ ] Task: Core Page Onboarding
    - [ ] Add an empty-state onboarding view ("How to get started") when no image is selected.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: UI Polish & Onboarding' (Protocol in workflow.md)

## Phase 3: Advanced PDF & Share Features
- [ ] Task: Refine PDF Instructions
    - [ ] Update `PosterLogic.addInstructionsPage` to draw the image thumbnail under the grid.
    - [ ] Increase grid label sizes and adjust spacing to avoid overlapping text.
- [ ] Task: Unified Workflow & Share
    - [ ] Remove the standalone "Generate" button.
    - [ ] Ensure "View" and "Save" trigger generation automatically. Add a "Share" button (`Intent.ACTION_SEND`).
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Advanced PDF & Share Features' (Protocol in workflow.md)

## Phase 4: Live Preview Animation
- [ ] Task: Complete OpenGL Assembly Preview
    - [ ] Update `PosterPreview.kt` to draw the actual sliced `previewBitmap` on each separating tile.
    - [ ] Include animated margins, borders, and labels during the phase transitions.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Live Preview Animation' (Protocol in workflow.md)
