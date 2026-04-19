# Implementation Plan: Enhanced Features & OpenGL Preview

## Phase 1: Core UI Fixes & Image Logic
- [x] Task: Theme & Viewport
    - [x] Fix the Dark/Light theme issue in `Theme.kt` and `AndroidManifest.xml`.
    - [x] Update `ImagePickerHeader` to use `ContentScale.Fit` instead of `Crop`.
- [x] Task: Image Metadata & Sizing
    - [x] Extract and display image resolution and aspect ratio in the UI.
    - [x] Implement aspect ratio locking for width/height inputs in `MainViewModel`.
    - [x] Add a low DPI warning (e.g., < 150 DPI) based on poster dimensions.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Core UI Fixes & Image Logic' (Protocol in workflow.md)

## Phase 2: PDF Options & Persistence
- [x] Task: Settings & Persistence
    - [x] Implement DataStore/SharedPreferences to remember all input values.
    - [x] Add a hamburger menu for global settings (units, default paper).
    - [x] Create a first-run settings wizard.
    - [x] Add a "Reset" button to clear saved state.
- [x] Task: Advanced Selectors & Count
    - [x] Replace the paper size text input with an interactive dropdown (Letter, A4, Custom).
    - [x] Implement an outline style selector showing visual line types.
    - [x] Calculate and display the total pane count dynamically.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: PDF Options & Persistence' (Protocol in workflow.md)

## Phase 3: PDF Generation Updates & System Integration
- [x] Task: PosterLogic Enhancements
    - [x] Update `PosterLogic` to handle outline drawing (solid, dotted, dashed) and varying thickness.
    - [x] Add an assembly instructions page to the PDF output.
- [x] Task: System Intent Integration
    - [x] Use `Intent.ACTION_CREATE_DOCUMENT` to let the user save the PDF.
    - [x] Use `Intent.ACTION_VIEW` and a `FileProvider` to open the generated PDF.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: PDF Generation Updates & System Integration' (Protocol in workflow.md)

## Phase 4: Real-time OpenGL Preview Animation
- [x] Task: Preview Surface
    - [x] Create a custom Compose layout or GLSurfaceView for the real-time preview.
- [~] Task: Animation Logic
    - [~] Implement the sequence: show original -> draw borders -> add labels -> melt margins -> separate pages onto a wood table background.
    - [x] Bind preview to `MainViewModel` state changes.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Real-time OpenGL Preview Animation' (Protocol in workflow.md)
