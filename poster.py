import math
import os
import sys
from PIL import Image
from reportlab.lib.units import inch
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from reportlab.lib.utils import ImageReader

# Paper sizes in inches
PAPER_SIZES = {
    'letter': (8.5, 11),
    'a4': (8.27, 11.69),
    'legal': (8.5, 14),
    '11x17': (11, 17)
}

def draw_ruler(c, x, y, length_pt, total_inches, is_horizontal):
    """Draws a ruler with inch markings corresponding to the actual poster size."""
    c.saveState()
    c.setStrokeColorRGB(0.5, 0.5, 0.5)
    c.setFont("Helvetica", 6)
    
    if is_horizontal:
        c.line(x, y, x + length_pt, y)
        if total_inches > 0:
            for i in range(int(total_inches) + 1):
                pos = x + (i / total_inches) * length_pt
                c.line(pos, y, pos, y - 10)
                c.drawString(pos + 2, y - 10, str(i))
    else:  # Vertical
        c.line(x, y, x, y + length_pt)
        if total_inches > 0:
            for i in range(int(total_inches) + 1):
                pos = y + (i / total_inches) * length_pt
                c.line(x, pos, x - 10, pos)
                c.drawString(x - 20, pos - 2, str(i))
    c.restoreState()

def get_grid_label(row, col):
    """Returns grid coordinate label (e.g., A1, B2)."""
    # Rows: A, B, C...
    row_label = ""
    r = row
    while r >= 0:
        row_label = chr(ord('A') + (r % 26)) + row_label
        r = r // 26 - 1
    # Cols: 1, 2, 3...
    col_label = str(col + 1)
    return f"{row_label}{col_label}"

def calculate_sheet_count(poster_w_pt, poster_h_pt, printable_w_pt, printable_h_pt, overlap_pt):
    """Calculates the number of sheets (tiles) needed for a given poster size."""
    tile_step_x = printable_w_pt - overlap_pt
    tile_step_y = printable_h_pt - overlap_pt

    if poster_w_pt <= printable_w_pt:
        cols = 1
    else:
        cols = math.ceil((poster_w_pt - printable_w_pt) / tile_step_x) + 1
        
    if poster_h_pt <= printable_h_pt:
        rows = 1
    else:
        rows = math.ceil((poster_h_pt - printable_h_pt) / tile_step_y) + 1
        
    return rows * cols, rows, cols

def create_poster(image_path, poster_width_in, output_pdf_path, paper_format='letter', margin_in=0.5, overlap_in=0.25, orientation='auto'):
    """
    Creates a tiled PDF poster from an image.
    """
    
    # Paper Dimensions
    if paper_format not in PAPER_SIZES:
        print(f"Warning: Unknown format '{paper_format}', defaulting to letter.", file=sys.stderr)
        paper_format = 'letter'
        
    PAGE_WIDTH_IN, PAGE_HEIGHT_IN = PAPER_SIZES[paper_format]
    
    PAGE_WIDTH_PT = PAGE_WIDTH_IN * inch
    PAGE_HEIGHT_PT = PAGE_HEIGHT_IN * inch
    MARGIN_PT = margin_in * inch
    OVERLAP_PT = overlap_in * inch

    # Printable area per page
    PRINTABLE_WIDTH_PT = PAGE_WIDTH_PT - 2 * MARGIN_PT
    PRINTABLE_HEIGHT_PT = PAGE_HEIGHT_PT - 2 * MARGIN_PT
    
    if PRINTABLE_WIDTH_PT <= 0 or PRINTABLE_HEIGHT_PT <= 0:
        print("Error: Margins are too large for the selected paper size.", file=sys.stderr)
        sys.exit(1)

    # Step size (how much NEW image data is covered per tile)
    TILE_STEP_X_PT = PRINTABLE_WIDTH_PT - OVERLAP_PT
    TILE_STEP_Y_PT = PRINTABLE_HEIGHT_PT - OVERLAP_PT

    if TILE_STEP_X_PT <= 0 or TILE_STEP_Y_PT <= 0:
        print("Error: Overlap is too large for the printable area.", file=sys.stderr)
        sys.exit(1)

    # Open Image
    try:
        img = Image.open(image_path)
        img_width_px, img_height_px = img.size
    except FileNotFoundError:
        print(f"Error: Image file not found at {image_path}", file=sys.stderr)
        sys.exit(1)

    # Initial Dimensions
    aspect_ratio = img_height_px / img_width_px
    poster_height_in = poster_width_in * aspect_ratio
    
    poster_width_pt = poster_width_in * inch
    poster_height_pt = poster_height_in * inch

    image_source = image_path
    is_rotated = False

    # Determine if rotation is needed based on orientation logic
    should_rotate = False
    
    if orientation == 'auto':
        # Check if rotating saves sheets
        sheets_normal, _, _ = calculate_sheet_count(poster_width_pt, poster_height_pt, PRINTABLE_WIDTH_PT, PRINTABLE_HEIGHT_PT, OVERLAP_PT)
        sheets_rotated, _, _ = calculate_sheet_count(poster_height_pt, poster_width_pt, PRINTABLE_WIDTH_PT, PRINTABLE_HEIGHT_PT, OVERLAP_PT)
        if sheets_rotated < sheets_normal:
            should_rotate = True
            print(f"Optimizing: Rotating poster reduces sheets from {sheets_normal} to {sheets_rotated}.")
    
    elif orientation == 'portrait':
        # Force height >= width
        if poster_width_pt > poster_height_pt:
            should_rotate = True
            print("Forcing Portrait orientation (rotating image).")
            
    elif orientation == 'landscape':
        # Force width >= height
        if poster_height_pt > poster_width_pt:
            should_rotate = True
            print("Forcing Landscape orientation (rotating image).")

    if should_rotate:
        # Rotate Image 90 degrees (Counter-Clockwise)
        img = img.transpose(Image.ROTATE_90)
        image_source = ImageReader(img)
        is_rotated = True
        
        # Swap Dimensions
        poster_width_in, poster_height_in = poster_height_in, poster_width_in
        poster_width_pt, poster_height_pt = poster_height_pt, poster_width_pt
        
        # Update pixel dims for DPI calc
        img_width_px, img_height_px = img_height_px, img_width_px
    
    # Recalculate layout with final dimensions
    total_tiles, num_rows, num_cols = calculate_sheet_count(poster_width_pt, poster_height_pt, PRINTABLE_WIDTH_PT, PRINTABLE_HEIGHT_PT, OVERLAP_PT)
    
    # Low Resolution Warning
    dpi = img_width_px / poster_width_in
    if dpi < 150:
        print(f"WARNING: The calculated resolution is low ({dpi:.0f} DPI). The poster may look pixelated.", file=sys.stderr)
        print("         Consider using a higher resolution image or a smaller poster size.", file=sys.stderr)

    # Create PDF
    c = canvas.Canvas(output_pdf_path, pagesize=(PAGE_WIDTH_PT, PAGE_HEIGHT_PT))

    # --- Cover Sheet ---
    image_filename = os.path.basename(image_path)
    c.setFont("Helvetica-Bold", 16)
    c.drawCentredString(PAGE_WIDTH_PT / 2, PAGE_HEIGHT_PT - 0.75 * inch, "Poster Assembly Guide")
    c.setFont("Helvetica", 12)
    source_text = f"Source: {image_filename}"
    if is_rotated:
        source_text += " (Rotated)"
    c.drawCentredString(PAGE_WIDTH_PT / 2, PAGE_HEIGHT_PT - 1.0 * inch, source_text)

    # Guide scaling
    guide_area_width = PAGE_WIDTH_PT - 2 * inch
    guide_area_height = PAGE_HEIGHT_PT - 4 * inch
    
    scale_w = guide_area_width / poster_width_pt
    scale_h = guide_area_height / poster_height_pt
    scale = min(scale_w, scale_h)

    guide_width = poster_width_pt * scale
    guide_height = poster_height_pt * scale
    
    start_x = (PAGE_WIDTH_PT - guide_width) / 2
    start_y = (PAGE_HEIGHT_PT - guide_height) / 2 - 1 * inch

    # Background
    c.saveState()
    c.setFillAlpha(0.2)
    # Use image_source (path or ImageReader)
    c.drawImage(image_source, start_x, start_y, width=guide_width, height=guide_height, preserveAspectRatio=True, anchor='c')
    c.restoreState()

    # Rulers
    draw_ruler(c, start_x, start_y + guide_height + 0.25 * inch, guide_width, poster_width_in, is_horizontal=True)
    draw_ruler(c, start_x - 0.25 * inch, start_y, guide_height, poster_height_in, is_horizontal=False)

    # Outline
    c.rect(start_x, start_y, guide_width, guide_height)
    
    # Grid
    c.setFont("Helvetica", 8)
    tile_w_vis_scaled = TILE_STEP_X_PT * scale # Visual step
    tile_h_vis_scaled = TILE_STEP_Y_PT * scale
    
    for r in range(num_rows):
        for col in range(num_cols):
            label = get_grid_label(r, col)
            
            # Position relative to Top-Left of Poster
            rel_x = col * tile_w_vis_scaled
            rel_y_top = guide_height - r * tile_h_vis_scaled
            
            draw_x = start_x + rel_x
            draw_y = start_y + max(0, rel_y_top - tile_h_vis_scaled)
            draw_w = min(tile_w_vis_scaled, guide_width - rel_x)
            draw_h = min(rel_y_top, guide_height) - max(0, rel_y_top - tile_h_vis_scaled)

            if draw_w > 0 and draw_h > 0:
                c.rect(draw_x, draw_y, draw_w, draw_h)
                c.drawCentredString(draw_x + draw_w / 2, draw_y + draw_h / 2, label)

    # Info
    c.setFont("Helvetica", 12)
    c.drawCentredString(PAGE_WIDTH_PT / 2, start_y - 0.5 * inch, f"Dimensions: {poster_width_in:.2f}\" x {poster_height_in:.2f}")
    c.drawCentredString(PAGE_WIDTH_PT / 2, start_y - 0.75 * inch, f"Grid: {num_cols} x {num_rows} ({num_rows*num_cols} tiles)")
    c.showPage()


    # --- Tile Pages ---
    for row in range(num_rows):
        for col in range(num_cols):
            # Coordinates
            label = get_grid_label(row, col)
            
            # Calculate where the tile starts in the Image Coordinate System (Top-Left is 0,0)
            img_x_start = col * TILE_STEP_X_PT
            img_y_start = row * TILE_STEP_Y_PT
            
            x_offset = MARGIN_PT - img_x_start
            y_offset = (PAGE_HEIGHT_PT - MARGIN_PT) - poster_height_pt + img_y_start

            c.saveState()
            
            # Clip to Printable Area
            path = c.beginPath()
            path.rect(MARGIN_PT, MARGIN_PT, PRINTABLE_WIDTH_PT, PRINTABLE_HEIGHT_PT)
            c.clipPath(path, stroke=0)
            
            # Draw Image
            c.drawImage(
                image_source,
                x_offset,
                y_offset,
                width=poster_width_pt,
                height=poster_height_pt,
                preserveAspectRatio=True,
                anchor='c'
            )
            c.restoreState()

            # Draw Cut Lines (Outer Boundary of Printable Area)
            c.saveState()
            c.setLineWidth(1)
            c.setDash([4, 4])
            c.rect(MARGIN_PT, MARGIN_PT, PRINTABLE_WIDTH_PT, PRINTABLE_HEIGHT_PT, stroke=1, fill=0)
            
            c.setLineWidth(0.5)
            c.setDash([2, 2])
            
            # Overlap on Right
            if col < num_cols - 1:
                line_x = MARGIN_PT + PRINTABLE_WIDTH_PT - OVERLAP_PT
                c.line(line_x, MARGIN_PT, line_x, MARGIN_PT + PRINTABLE_HEIGHT_PT)
                
            # Overlap on Left
            if col > 0:
                line_x = MARGIN_PT + OVERLAP_PT
                c.line(line_x, MARGIN_PT, line_x, MARGIN_PT + PRINTABLE_HEIGHT_PT)
                
            # Overlap on Bottom
            if row < num_rows - 1:
                line_y = MARGIN_PT + OVERLAP_PT
                c.line(MARGIN_PT, line_y, MARGIN_PT + PRINTABLE_WIDTH_PT, line_y)
                
            # Overlap on Top
            if row > 0:
                line_y = MARGIN_PT + PRINTABLE_HEIGHT_PT - OVERLAP_PT
                c.line(MARGIN_PT, line_y, MARGIN_PT + PRINTABLE_WIDTH_PT, line_y)

            c.restoreState()
            
            # Label
            c.setFont("Helvetica", 10)
            c.drawString(MARGIN_PT, PAGE_HEIGHT_PT - MARGIN_PT + 5, f"Tile {label}  ({row+1},{col+1})")
            
            c.showPage()

    c.save()
    print(f"Created {num_rows * num_cols} tiles.")
    print(f"Poster Dimensions: {poster_width_in:.2f}\" x {poster_height_in:.2f}")

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(
        description="Create a multi-page PDF poster from an image.",
        formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument("image_file", help="Path to the source image file.")
    parser.add_argument("width", type=float, help="Desired width of the final poster in inches.")
    parser.add_argument(
        "-o", "--output",
        default=None,
        help="Path to save the output PDF file. If not provided, saves in the source folder."
    )
    parser.add_argument(
        "--format",
        choices=['letter', 'a4', 'legal', '11x17'],
        default='letter',
        help="Paper format (default: letter)"
    )
    parser.add_argument(
        "--margin",
        type=float,
        default=0.5,
        help="Page margins in inches (default: 0.5)"
    )
    parser.add_argument(
        "--overlap",
        type=float,
        default=0.25,
        help="Overlap between tiles in inches (default: 0.25)"
    )
    parser.add_argument(
        "--orientation",
        choices=['auto', 'portrait', 'landscape'],
        default='auto',
        help="Force poster orientation (default: auto, optimizes for fewer sheets)"
    )

    args = parser.parse_args()

    output_path = args.output
    if output_path is None:
        try:
            # Note: We open image here just for naming; the main function opens it again.
            # This is fine.
            img = Image.open(args.image_file)
            w_px, h_px = img.size
            aspect = h_px / w_px
            poster_height = args.width * aspect
            
            base_name = os.path.splitext(os.path.basename(args.image_file))[0]
            dir_name = os.path.dirname(os.path.abspath(args.image_file))
            filename = f"poster-{base_name}-{args.width:.1f}x{poster_height:.1f}.pdf"
            output_path = os.path.join(dir_name, filename)
        except Exception as e:
            print(f"Error determining output path: {e}", file=sys.stderr)
            sys.exit(1)

    create_poster(
        args.image_file, 
        args.width, 
        output_path, 
        paper_format=args.format,
        margin_in=args.margin,
        overlap_in=args.overlap,
        orientation=args.orientation
    )
    print(f"Poster saved to {output_path}")