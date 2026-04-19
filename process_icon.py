from PIL import Image, ImageOps
import os

def process_icon(input_path, output_dir):
    img = Image.open(input_path).convert("RGBA")
    data = img.getdata()
    
    # Identify background color (top-left pixel)
    bg_color = data[0]
    
    new_data = []
    for item in data:
        # If pixel is very close to bg_color, make it transparent
        # Using a threshold to handle slight compression artifacts
        diff = sum(abs(a - b) for a, b in zip(item[:3], bg_color[:3]))
        if diff < 30:
            new_data.append((255, 255, 255, 0))
        else:
            new_data.append(item)
            
    img.putdata(new_data)
    
    # Trim transparency
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    
    # Standard sizes
    sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192
    }
    
    for name, size in sizes.items():
        res_dir = f"{output_dir}/mipmap-{name}"
        os.makedirs(res_dir, exist_ok=True)
        # Resize to fit within 108x108 (adaptive icon foreground)
        # Foreground should be 108x108, but the actual icon should be smaller (approx 72x72) to stay in safe zone
        foreground = Image.new("RGBA", (size, size), (255, 255, 255, 0))
        
        icon_size = int(size * 0.66)
        scaled_icon = img.copy()
        scaled_icon.thumbnail((icon_size, icon_size), Image.Resampling.LANCZOS)
        
        # Paste centered
        offset = ((size - scaled_icon.width) // 2, (size - scaled_icon.height) // 2)
        foreground.paste(scaled_icon, offset, scaled_icon)
        
        foreground.save(f"{res_dir}/ic_launcher_foreground.png")
        # For legacy support, also save as ic_launcher
        foreground.save(f"{res_dir}/ic_launcher.png")

if __name__ == "__main__":
    process_icon("poster pdf.png", "app/src/main/res")
