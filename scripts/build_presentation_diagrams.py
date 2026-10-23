"""
RoadSense Presentation - Diagram Builder for Slide 3 (IPM Geometry)
Prepares high-resolution, perfectly tailored geometry diagrams from the
authoritative reference material in intel/Vision/Camera_Intrinsics_fx_fy_and_Projection_Explained.md.
"""

from PIL import Image, ImageDraw, ImageFont
import os

def build_presentation_diagrams():
    assets_dir = r"C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\intel\presentations\assets"
    vision_dir = r"C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\intel\Vision\assets"
    os.makedirs(assets_dir, exist_ok=True)
    
    # 1. Load the pristine source infographic
    infographic_path = os.path.join(vision_dir, "Automotive Vision Geometry Infographic.png")
    im = Image.open(infographic_path).convert("RGBA")
    w, h = im.size
    
    # -------------------------------------------------------------------------
    # Option 1: Standalone Side Elevation View (Panel 1) with RoadSense Callouts
    # -------------------------------------------------------------------------
    p1 = im.crop((12, 12, int(w * 0.496), int(h * 0.496)))
    # Save high-res standalone elevation view
    p1_path = os.path.join(assets_dir, "ipm_side_elevation_view.png")
    p1.save(p1_path, "PNG", dpi=(300, 300))
    print(f"[OK] Saved: {p1_path}")

    # -------------------------------------------------------------------------
    # Option 2: Standalone Similar Triangles Schematic (Panel 4: Top + Side)
    # -------------------------------------------------------------------------
    p4 = im.crop((int(w * 0.504), int(h * 0.504), w - 12, h - 12))
    p4_path = os.path.join(assets_dir, "ipm_similar_triangles_dual_view.png")
    p4.save(p4_path, "PNG", dpi=(300, 300))
    print(f"[OK] Saved: {p4_path}")

    # -------------------------------------------------------------------------
    # Option 3: Master 2-Panel Stack (Side Elevation + Top-Down Azimuth)
    # Designed specifically for Slide 3's right-hand container (5.70" x 5.30" ~ 1.08 ratio)
    # -------------------------------------------------------------------------
    p2 = im.crop((int(w * 0.504), 12, w - 12, int(h * 0.496)))
    
    # Create master container (e.g. 1500 x 1400)
    canvas_w = 1500
    canvas_h = 1390
    master = Image.new("RGBA", (canvas_w, canvas_h), (255, 255, 255, 255))
    draw = ImageDraw.Draw(master)
    
    # Outer Card Border (matching presentation card styling: border #CBD5E1, bg #F8FAFC)
    draw.rounded_rectangle([(4, 4), (canvas_w - 5, canvas_h - 5)], radius=18, 
                           fill="#FFFFFF", outline="#CBD5E1", width=3)
    
    # Resize p1 and p2 to fit stacked with equal width (e.g. width = 1440)
    panel_w = 1440
    p1_h = int(p1.height * (panel_w / p1.width))
    p2_h = int(p2.height * (panel_w / p2.width))
    
    # Target height for each panel to fit inside 1390:
    # Available height = 1390 - 40 (padding) - 20 (gap) = 1330 -> ~650 each
    avail_panel_h = 650
    # Resize preserving aspect ratio
    p1_scaled = p1.resize((panel_w, int(p1.height * (panel_w / p1.width))), Image.Resampling.LANCZOS)
    p2_scaled = p2.resize((panel_w, int(p2.height * (panel_w / p2.width))), Image.Resampling.LANCZOS)
    
    # Fit into height by scaling both proportionally
    scale_factor = 650 / p1_scaled.height
    final_w = int(panel_w * scale_factor)
    final_h = 650
    
    p1_final = p1.resize((final_w, final_h), Image.Resampling.LANCZOS)
    p2_final = p2.resize((final_w, final_h), Image.Resampling.LANCZOS)
    
    x_offset = (canvas_w - final_w) // 2
    master.paste(p1_final, (x_offset, 25), p1_final)
    master.paste(p2_final, (x_offset, 25 + final_h + 30), p2_final)
    
    # Divider line between the two views
    divider_y = 25 + final_h + 15
    draw.line([(40, divider_y), (canvas_w - 40, divider_y)], fill="#E2E8F0", width=2)
    
    dual_stack_path = os.path.join(assets_dir, "ipm_master_dual_view_schematic.png")
    master.convert("RGB").save(dual_stack_path, "PNG", dpi=(300, 300))
    print(f"[OK] Saved: {dual_stack_path}")
    
    # Mirror into intel/Vision/assets
    master.convert("RGB").save(os.path.join(vision_dir, "ipm_master_dual_view_schematic.png"), "PNG", dpi=(300, 300))
    p1.save(os.path.join(vision_dir, "ipm_side_elevation_view.png"), "PNG", dpi=(300, 300))

if __name__ == "__main__":
    build_presentation_diagrams()
