# Specification: UX Refinements & Advanced Polish

## Overview
This track addresses comprehensive user feedback regarding the Poster PDF application. It focuses on refining the user experience (wizard, navigation, themes), fixing logical bugs (aspect ratio lock, preview animations), enhancing PDF output (instructions page diagrams), and streamlining the core workflow (generating on-demand).

## Requirements

### UX and Settings
- **First-Run Wizard:** Actually apply and save user preferences chosen during the wizard.
- **Navigation:** Pressing the hardware/software Back button while the settings hamburger menu is open must close the menu, not exit the app.
- **Settings Persistence:** Ensure the "Reset to Defaults" button correctly resets both the ViewModel state and the DataStore.

### Image Logic and Geometry
- **Aspect Ratio Display:** Format as `X.X:Y.Y` (e.g., `1.0:1.0`) with a Tooltip explaining the value.
- **Aspect Ratio Lock:** Fix the calculation bug where linking the aspect ratio produces incorrect width/height values.
- **Paper Selection:** Include Legal (8.5x14) and Tabloid (11x17). Implement an Orientation selector (Best Fit, Portrait, Landscape) that adjusts the paper dimensions and visually represents the paper.

### Visual Polish and Preview
- **Line Styles:** Increase the visual size and stroke width of the line style icons for better visibility.
- **Theme:** Ensure the Dark Theme is correctly applied system-wide without being overridden by legacy configurations.
- **Live Assembly Preview:** Fix the OpenGL/Canvas animation loop (currently static). Draw the actual selected image (or a thumbnail) onto the preview rectangles instead of just white blanks.

### Streamlined Generation and PDF Output
- **One-Step Generation:** Remove the explicit "Generate" button. The "View" and "Save As" buttons must trigger the generation process, display a loading indicator, and then fulfill their respective actions.
- **PDF Instructions:** The generated assembly instructions page must include a diagram of the panels labeled according to the grid, along with a thumbnail of the original image.

## Out of Scope
- Advanced image editing (brightness, contrast, rotation).
