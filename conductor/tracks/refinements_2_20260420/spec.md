# Specification: Final UI/UX Refinements

## Overview
This track addresses the final round of user feedback to ensure the Poster PDF application is completely polished. The fixes span UI updates, core logic adjustments, preview animation improvements, and ensuring consistent app behaviors (e.g., Back button, Dark Mode).

## Requirements

### UI Adjustments
1. **Change Image Button:** The "Change Image" overlay on the selected image must look like a standard `Button` (e.g., Elevated or FilledTonal) instead of just text on a glass background.
2. **Aspect Ratio Tooltip:** The Info icon next to the Aspect Ratio needs to be functional. Clicking it should show a dialog or tooltip explaining aspect ratio. The format should be `X.X:Y.Y` (e.g., `1.0:1.0`).
3. **Orientation Layout:** The Orientation buttons (Best Fit, Portrait, Landscape) need more space and should be moved below the "Orientation" text rather than next to it.
4. **Line Styles:** Line style icons in the advanced options must be larger and easier to see.
5. **Core Page Onboarding:** The main page, when no image is selected, needs explanatory text and infographics (e.g., "How to get started... 1. Select an image 2. Configure... 3. Generate...").
6. **Margin/Overlap Help:** Add info buttons next to Margin and Overlap fields explaining what they are and giving recommended printer settings.

### Core Logic & System Integration
7. **Aspect Ratio Lock Bug:** Fix the bug where the aspect ratio lock enforces a 2:3 ratio even when a 1:1 image is selected. It must lock to the actual image aspect ratio using `Locale.US`.
8. **Units Toggle Bug:** Changing units to Metric in the wizard/settings should update the labels (e.g., "Width (cm)") on the core page.
9. **Settings Defaults & Back Button:** 
   - Add a default paper size selector to the settings menu.
   - The wizard must actually apply and save the preferences chosen.
   - Pressing the hardware Back button while the settings hamburger is open should hide the panel, not exit the app (`BackHandler`).
10. **Dark Theme:** Ensure the Dark Theme applies correctly when the system is in dark mode (check `GlassCard` colors and Material 3 theme setups).

### PDF Generation & Preview
11. **Live Assembly Preview:** Fix the OpenGL/Canvas animation. Show the actual selected picture cut into tiles, including margins, overlap, labels, and cut borders.
12. **PDF Instructions & Workflow:**
    - The generated instructions page must show a low-opacity preview image under much larger grid text labels, preventing overlap with the instructions text.
    - Remove the standalone "Generate" button. Instead, generate the PDF on the fly when "View PDF" or "Save As..." is pressed. Provide a "Share to..." button.
