# Implementation Plan: Enhanced Features & OpenGL Preview

## Phase 1: Core UI Fixes & Image Logic
- [ ] Task: Theme & Viewport
    - [ ] Fix the Dark/Light theme issue in `Theme.kt` and `AndroidManifest.xml`.
    - [ ] Update `ImagePickerHeader` to use `ContentScale.Fit` instead of `Crop`.
- [ ] Task: Image Metadata & Sizing
    - [ ] Extract and display image resolution and aspect ratio in the UI.
    - [ ] Implement aspect ratio locking for width/height inputs in `MainViewModel`.
    - [ ] Add a low DPI warning (e.g., < 150 DPI) based on poster dimensions.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Core UI Fixes & Image Logic' (Protocol in workflow.md)

## Phase 2: PDF Options & Persistence
- [ ] Task: Settings & Persistence
    - [ ] Implement DataStore/SharedPreferences to remember all input values.
    - [ ] Add a hamburger menu for global settings (units, default paper).
    - [ ] Create a first-run settings wizard.
    - [ ] Add a "Reset" button to clear saved state.
- [ ] Task: Advanced Selectors & Count
    - [ ] Replace the paper size text input with an interactive dropdown (Letter, A4, Custom).
    - [ ] Implement an outline style selector showing visual line types.
    - [ ] Calculate and display the total pane count dynamically.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: PDF Options & Persistence' (Protocol in workflow.md)

## Phase 3: PDF Generation Updates & System Integration
- [ ] Task: PosterLogic Enhancements
    - [ ] Update `PosterLogic` to handle outline drawing (solid, dotted, dashed) and varying thickness.
    - [ ] Add an assembly instructions page to the PDF output.
- [ ] Task: System Intent Integration
    - [ ] Use `Intent.ACTION_CREATE_DOCUMENT` to let the user save the PDF.
    - [ ] Use `Intent.ACTION_VIEW` and a `FileProvider` to open the generated PDF.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: PDF Generation Updates & System Integration' (Protocol in workflow.md)

## Phase 4: Real-time OpenGL Preview Animation
- [ ] Task: Preview Surface
    - [ ] Create a custom Compose layout or GLSurfaceView for the real-time preview.
- [ ] Task: Animation Logic
    - [ ] Implement the sequence: show original -> draw borders -> add labels -> melt margins -> separate pages onto a wood table background.
    - [ ] Bind preview to `MainViewModel` state changes.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Real-time OpenGL Preview Animation' (Protocol in workflow.md)
