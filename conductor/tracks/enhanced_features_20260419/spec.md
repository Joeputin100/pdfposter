# Specification: Enhanced Features & OpenGL Preview

## Overview
This track delivers a massive update to the PDF Poster app, addressing user feedback regarding theme integration, UI enhancements, advanced PDF generation options (outlines, labels, low DPI warnings), system integration (saving/viewing PDFs), settings persistence, and a high-end OpenGL-backed preview animation.

## Requirements

### UI Polish & Core Image Logic
- **Theme:** Ensure the app correctly applies light/dark themes according to system settings.
- **Viewport:** Display the entire selected image without cropping.
- **Image Info:** Show the aspect ratio and resolution of the selected image.
- **Aspect Ratio Lock:** Lock the aspect ratio when adjusting poster width and height.
- **Low DPI Warning:** Display a warning with suggestions if the calculated print resolution is too low.
- **Pane Count:** Display the calculated number of panes/tiles required for the poster.

### Advanced PDF Options
- **Paper Size Selector:** Replace the text input with a dropdown containing common sizes (Letter, A4, A3) and a "Custom" option that prompts for dimensions.
- **Outlines:** Add options to draw outlines on panes, including a visual selector for line styles (solid, dotted, dashed) and thickness (thin, medium, heavy).
- **Labeling:** Provide an option to label panes (e.g., A1, B2) on the generated PDF.
- **Assembly Instructions:** Add a dedicated page for assembly instructions to the generated PDF.

### System Integration & Persistence
- **Settings/Wizard:** Implement a hamburger menu for settings (units, default paper size) and a first-run wizard.
- **Persistence:** Remember all settings changes, providing a "Reset" button to revert to defaults.
- **PDF Handling:** Allow users to view the generated PDF using the system default viewer and save it using the system chooser.

### OpenGL Preview Animation
- **Real-time Preview:** Update a visual preview as settings (paper size, poster size, margins, overlaps, outlines) change.
- **Animation:** Use advanced OpenGL (or Canvas with RenderNode) animations to show the original image, draw borders, add labels, animate margins/overlaps "melting" from the sides, and visually separate the pages onto a "wood table" background.

## Out of Scope
- Direct image editing (cropping, color correction) within the app.
