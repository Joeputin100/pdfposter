
import math
import os
import sys
from PIL import Image
from reportlab.lib.units import inch
from reportlab.pdfgen import canvas
from reportlab.lib import colors

def draw_ruler(c, x, y, length_pt, total_inches, is_horizontal):
    """Draws a ruler with inch markings corresponding to the actual poster size."""
    c.saveState()
    c.setStrokeColorRGB(0.5, 0.5, 0.5)
    c.setFont("Helvetica", 6)
    
    if is_horizontal:
        c.line(x, y, x + length_pt, y)
        for i in range(int(total_inches) + 1):
            pos = x + (i / total_inches) * length_pt
            c.line(pos, y, pos, y - 10)
            c.drawString(pos + 2, y - 10, str(i))
    else:  # Vertical
        c.line(x, y, x, y + length_pt)
        for i in range(int(total_inches) + 1):
            pos = y + (i / total_inches) * length_pt
            c.line(x, pos, x - 10, pos)
            c.drawString(x - 20, pos - 2, str(i))
    c.restoreState()


def create_poster(image_path, poster_width_in, output_pdf_path):
    """
    Creates a tiled PDF poster from an image.

    Args:
        image_path (str): Path to the input image.
        poster_width_in (float): Desired width of the poster in inches.
        output_pdf_path (str): Path to save the output PDF.
    """
    # Constants
    PAGE_WIDTH_IN = 8.5
    PAGE_HEIGHT_IN = 11
    MARGIN_IN = 0.5

    PAGE_WIDTH_PT = PAGE_WIDTH_IN * inch
    PAGE_HEIGHT_PT = PAGE_HEIGHT_IN * inch
    MARGIN_PT = MARGIN_IN * inch

    # Open the image and get its dimensions
    try:
        img = Image.open(image_path)
        img_width_px, img_height_px = img.size
    except FileNotFoundError:
        print(f"Error: Image file not found at {image_path}", file=sys.stderr)
        sys.exit(1)

    # Calculate poster dimensions
    aspect_ratio = img_height_px / img_width_px
    poster_height_in = poster_width_in * aspect_ratio
    poster_width_pt = poster_width_in * inch
    poster_height_pt = poster_height_in * inch

    # Calculate the number of tiles needed
    effective_page_width_in = PAGE_WIDTH_IN - MARGIN_IN
    effective_page_height_in = PAGE_HEIGHT_IN - MARGIN_IN

    if poster_width_in <= PAGE_WIDTH_IN:
        num_cols = 1
    else:
        num_cols = math.ceil((poster_width_in - PAGE_WIDTH_IN) / effective_page_width_in) + 1

    if poster_height_in <= PAGE_HEIGHT_IN:
        num_rows = 1
    else:
        num_rows = math.ceil((poster_height_in - PAGE_HEIGHT_IN) / effective_page_height_in) + 1

    # Create the PDF
    c = canvas.Canvas(output_pdf_path, pagesize=(PAGE_WIDTH_PT, PAGE_HEIGHT_PT))

    # --- Create Cover Sheet ---
    # Add Title
    image_filename = os.path.basename(image_path)
    c.setFont("Helvetica-Bold", 16)
    c.drawCentredString(PAGE_WIDTH_PT / 2, PAGE_HEIGHT_PT - 0.75 * inch, "Poster Assembly Guide")
    c.setFont("Helvetica", 12)
    c.drawCentredString(PAGE_WIDTH_PT / 2, PAGE_HEIGHT_PT - 1.0 * inch, f"Source: {image_filename}")

    # Calculate scale to fit the guide on one page
    guide_area_width = PAGE_WIDTH_PT - 2 * inch
    guide_area_height = PAGE_HEIGHT_PT - 4 * inch # More space for text
    
    scale_w = guide_area_width / poster_width_pt
    scale_h = guide_area_height / poster_height_pt
    scale = min(scale_w, scale_h)

    guide_width = poster_width_pt * scale
    guide_height = poster_height_pt * scale
    
    # Center the guide
    start_x = (PAGE_WIDTH_PT - guide_width) / 2
    start_y = (PAGE_HEIGHT_PT - guide_height) / 2 - 1 * inch

    # Draw background image with opacity
    c.saveState()
    c.setFillAlpha(0.2)
    c.drawImage(image_path, start_x, start_y, width=guide_width, height=guide_height, preserveAspectRatio=True, anchor='c')
    c.restoreState()

    # Draw rulers
    draw_ruler(c, start_x, start_y + guide_height + 0.25 * inch, guide_width, poster_width_in, is_horizontal=True)
    draw_ruler(c, start_x - 0.25 * inch, start_y, guide_height, poster_height_in, is_horizontal=False)

    # Draw poster outline
    c.rect(start_x, start_y, guide_width, guide_height)
    
    # Draw tile grid and numbers
    c.setFont("Helvetica", 10)
    tile_width_scaled = (PAGE_WIDTH_IN - MARGIN_IN) * inch * scale
    tile_height_scaled = (PAGE_HEIGHT_IN - MARGIN_IN) * inch * scale
    
    for r in range(num_rows):
        for col in range(num_cols):
            page_num = r * num_cols + col + 1
            
            tile_x = start_x + col * tile_width_scaled
            tile_y = start_y + (num_rows - 1 - r) * tile_height_scaled
            
            draw_w = min(tile_width_scaled, guide_width - col * tile_width_scaled)
            draw_h = min(tile_height_scaled, guide_height - (num_rows - 1 - r) * tile_height_scaled)

            c.rect(tile_x, tile_y, draw_w, draw_h)
            c.drawCentredString(tile_x + draw_w / 2, tile_y + draw_h / 2, str(page_num))

    # Add total dimensions and scale text
    c.setFont("Helvetica", 12)
    dims_text = f"Total Dimensions: {poster_width_in:.2f}\" x {poster_height_in:.2f}\""
    c.drawCentredString(PAGE_WIDTH_PT / 2, start_y - 0.5 * inch, dims_text)
    scale_text = f"Scale: 1 inch on guide ≈ {1/scale:.2f} inches on poster"
    c.drawCentredString(PAGE_WIDTH_PT / 2, start_y - 0.75 * inch, scale_text)
    c.showPage()


    # --- Create Tile Pages ---
    for row in range(num_rows):
        for col in range(num_cols):
            x_offset = -col * effective_page_width_in * inch
            y_offset = -row * effective_page_height_in * inch

            c.saveState()
            path = c.beginPath()
            path.rect(0, 0, PAGE_WIDTH_PT, PAGE_HEIGHT_PT)
            c.clipPath(path, stroke=0)

            c.drawImage(
                image_path,
                x_offset,
                y_offset,
                width=poster_width_pt,
                height=poster_height_pt,
                preserveAspectRatio=True,
                anchor='c'
            )
            c.restoreState()

            c.saveState()
            c.setLineWidth(2)
            c.rect(
                MARGIN_PT,
                MARGIN_PT,
                PAGE_WIDTH_PT - 2 * MARGIN_PT,
                PAGE_HEIGHT_PT - 2 * MARGIN_PT,
                stroke=1,
                fill=0
            )
            c.restoreState()
            
            page_num = row * num_cols + col + 1
            circle_x = PAGE_WIDTH_PT - MARGIN_PT / 2
            circle_y = PAGE_HEIGHT_PT - MARGIN_PT / 2
            radius = 10
            c.circle(circle_x, circle_y, radius, stroke=1, fill=0)
            c.drawCentredString(circle_x, circle_y - 3, str(page_num))

            c.showPage()

    c.save()

    print(f"{num_rows * num_cols} tiles")
    print(f"Dimensions: {poster_width_in:.2f} x {poster_height_in:.2f} inches")


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
        default="poster.pdf",
        help="Path to save the output PDF file.\n(default: poster.pdf)"
    )

    args = parser.parse_args()

    create_poster(args.image_file, args.width, args.output)
    print(f"Poster saved to {args.output}")
