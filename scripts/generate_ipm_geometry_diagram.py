"""
RoadSense IPM Geometry Diagram Generator
Generates a vector-accurate, publication-quality technical schematic
for Slide 3: Inverse Perspective Mapping (IPM) Ground-Plane Raycasting.
"""

import matplotlib.pyplot as plt
import matplotlib.patches as patches
from matplotlib.patches import Arc, FancyArrowPatch, PathPatch
from matplotlib.path import Path
import numpy as np
import os

def create_ipm_diagram(output_png, output_svg=None):
    # Overall canvas: width 11.4 inches, height 10.6 inches (~ 5.7 x 5.3 ratio)
    fig = plt.figure(figsize=(11.4, 10.6), dpi=300, facecolor="#FFFFFF")
    
    # 2 Subplots: Top = Side View (Elevation), Bottom = Top View (Azimuth)
    # [left, bottom, width, height]
    ax_side = fig.add_axes([0.06, 0.46, 0.90, 0.48], facecolor="#F8FAFC")
    ax_top  = fig.add_axes([0.06, 0.05, 0.90, 0.36], facecolor="#F8FAFC")
    
    # Palette
    C_NAVY = "#0F172A"
    C_BLUE = "#2563EB"
    C_LIGHTBLUE = "#38BDF8"
    C_MAGENTA = "#D946EF"
    C_DARKMAGENTA = "#A21CAF"
    C_EMERALD = "#059669"
    C_AMBER = "#D97706"
    C_ROSE = "#E11D48"
    C_GRAY = "#64748B"
    C_LIGHTGRAY = "#E2E8F0"
    C_ROAD = "#334155"

    # =========================================================================
    # 1. TOP PANEL: SIDE VIEW (Elevation Plane Y-Z)
    # =========================================================================
    ax_side.set_title("ELEVATION VIEW: Pitch (θ_mount), Depression (β) & Ground-Plane Raycast (Z_road = 0)",
                      fontsize=12, fontweight="bold", color=C_NAVY, pad=12, loc="left", fontfamily="sans-serif")
    
    # Coordinates in diagram space (arbitrary scaled units for visual clarity):
    # Host vehicle at X_diag = 1.0. Road at Z_diag = 1.0.
    # Camera at (1.5, 4.2). Target car at Y_diag = 8.5.
    cam_x = 1.6
    cam_z = 4.3
    road_z = 1.2
    h_cam = cam_z - road_z  # 3.1 visual units
    target_x = 8.6
    
    # Road Surface (Z_road = 0)
    ax_side.axhline(road_z, color=C_ROAD, linewidth=3.5, zorder=2)
    # Road markings (dashed white center stripe)
    for dash_x in np.arange(0.2, 10.0, 0.8):
        ax_side.plot([dash_x, dash_x + 0.4], [road_z - 0.04, road_z - 0.04], color="#94A3B8", linewidth=2, zorder=3)
    ax_side.text(0.3, road_z - 0.28, "Road Asphalt Plane  (Z_road = 0)", fontsize=9.5, fontweight="bold", color=C_ROAD)
    
    # Host Vehicle Silhouette (Side View outline)
    host_verts = [
        (0.2, road_z), (0.2, 2.6), (0.7, 2.7), (1.1, 3.4), 
        (1.8, 4.4), (2.0, 4.4), (2.1, 3.4), (2.6, 2.7), (2.7, road_z)
    ]
    # Simple sleek car profile
    car_x = [0.2, 0.2, 0.6, 1.0, 1.8, 2.2, 2.4, 2.5, 2.5]
    car_z = [road_z, 2.3, 2.5, 3.3, 4.4, 4.4, 3.3, 2.5, road_z]
    ax_side.fill(car_x, car_z, color="#E2E8F0", alpha=0.7, zorder=2)
    ax_side.plot(car_x, car_z, color=C_GRAY, linewidth=1.5, linestyle="--", zorder=3)
    # Wheels
    w1 = plt.Circle((0.7, road_z), 0.35, color="#475569", zorder=3)
    w2 = plt.Circle((2.1, road_z), 0.35, color="#475569", zorder=3)
    ax_side.add_patch(w1)
    ax_side.add_patch(w2)
    
    # Camera Mount Point (Optical Center C)
    cam_circle = plt.Circle((cam_x, cam_z), 0.12, color=C_BLUE, zorder=10)
    ax_side.add_patch(cam_circle)
    ax_side.plot(cam_x, cam_z, marker="o", markersize=4, color="#FFFFFF", zorder=11)
    ax_side.text(cam_x - 0.15, cam_z + 0.25, "Camera Optical Center [C]\n(Windshield Mount)", 
                 fontsize=9, fontweight="bold", color=C_BLUE, ha="right", va="bottom")

    # Height dimension line (H_cam)
    dim_x = 0.5
    ax_side.annotate("", xy=(dim_x, cam_z), xytext=(dim_x, road_z),
                     arrowprops=dict(arrowstyle="<->", color=C_NAVY, lw=1.5), zorder=8)
    ax_side.plot([dim_x - 0.15, cam_x], [cam_z, cam_z], color=C_GRAY, linestyle=":", lw=1)
    ax_side.plot([dim_x - 0.15, dim_x + 0.15], [road_z, road_z], color=C_NAVY, lw=1.5)
    ax_side.text(dim_x - 0.1, (cam_z + road_z)/2, "H_cam = 1.35 m", 
                 fontsize=9.5, fontweight="bold", color=C_NAVY, rotation=90, ha="right", va="center")

    # True Horizontal Reference (Horizon: pitch = 0)
    ax_side.plot([cam_x, 9.8], [cam_z, cam_z], color=C_GRAY, linestyle="--", linewidth=1.2, zorder=4)
    ax_side.text(9.7, cam_z + 0.10, "True Horizontal Horizon (0° Level)", fontsize=8.5, color=C_GRAY, ha="right")

    # Optical Boresight (tilted down by theta_mount = 5.42 deg)
    theta_deg = 5.42
    theta_rad = np.radians(theta_deg)
    # Vector length
    ray_len = 8.0
    bore_end_x = cam_x + ray_len * np.cos(-theta_rad)
    bore_end_z = cam_z + ray_len * np.sin(-theta_rad)
    ax_side.plot([cam_x, bore_end_x], [cam_z, bore_end_z], color=C_LIGHTBLUE, linestyle="-.", linewidth=1.5, zorder=5)
    ax_side.text(bore_end_x - 0.5, bore_end_z + 0.15, "Optical Boresight (θ_mount = 5.42°)", 
                 fontsize=8.5, fontweight="bold", color="#0284C7", rotation=-theta_deg, ha="center")

    # Virtual Image / Sensor Plane (perpendicular to optical boresight at distance f_y)
    # Unit direction of optical axis:
    u_opt = np.array([np.cos(-theta_rad), np.sin(-theta_rad)])
    # Perpendicular direction (up along image sensor):
    u_sens = np.array([-np.sin(-theta_rad), np.cos(-theta_rad)])
    
    sens_dist = 1.3  # visual distance for virtual sensor plane
    sens_center = np.array([cam_x, cam_z]) + sens_dist * u_opt
    sens_half_h = 0.9
    sens_top = sens_center + sens_half_h * u_sens
    sens_bot = sens_center - sens_half_h * u_sens
    
    # Draw virtual image plane line
    ax_side.plot([sens_top[0], sens_bot[0]], [sens_top[1], sens_bot[1]], color=C_NAVY, linewidth=2.5, zorder=7)
    # Principal Point (c_y)
    ax_side.plot(sens_center[0], sens_center[1], marker="x", markersize=7, color=C_BLUE, mew=2, zorder=8)
    ax_side.text(sens_center[0] - 0.15, sens_center[1] + 0.12, "Principal Point c_y", fontsize=8, color=C_BLUE, ha="right")
    ax_side.text(sens_top[0], sens_top[1] + 0.10, "Image Sensor Plane\n(f_y focal length)", fontsize=8, fontweight="bold", color=C_NAVY, ha="center")

    # Target Lead Vehicle (Side View silhouette)
    tv_w = 1.8
    tv_h = 1.6
    tv_x0 = target_x - 0.6
    tv_box = patches.FancyBboxPatch((tv_x0, road_z), tv_w, tv_h, boxstyle="round,pad=0.08,rounding_size=0.15",
                                    facecolor="#F1F5F9", edgecolor=C_NAVY, linewidth=1.8, zorder=4)
    ax_side.add_patch(tv_box)
    # Lead vehicle wheels
    lw1 = plt.Circle((tv_x0 + 0.35, road_z), 0.28, color="#334155", zorder=5)
    lw2 = plt.Circle((tv_x0 + 1.45, road_z), 0.28, color="#334155", zorder=5)
    ax_side.add_patch(lw1)
    ax_side.add_patch(lw2)
    # Target Car Label
    ax_side.text(target_x + 0.3, road_z + tv_h/2, "Target Lead Vehicle", 
                 fontsize=9.5, fontweight="bold", color=C_NAVY, ha="center")

    # 2D Bounding Box depiction (in Magenta)
    bbox_x0 = tv_x0 - 0.08
    bbox_w = tv_w + 0.16
    bbox_h = tv_h + 0.35
    bbox_rect = patches.Rectangle((bbox_x0, road_z), bbox_w, bbox_h, 
                                  facecolor="none", edgecolor=C_MAGENTA, linewidth=2.2, linestyle="--", zorder=6)
    ax_side.add_patch(bbox_rect)
    ax_side.text(bbox_x0 + bbox_w/2, road_z + bbox_h + 0.12, "YOLO 2D Bounding Box", 
                 fontsize=8.5, fontweight="bold", color=C_DARKMAGENTA, ha="center")

    # Ground Contact Patch (where bottom of bbox touches asphalt)
    contact_pt = np.array([target_x, road_z])
    contact_circle = plt.Circle((contact_pt[0], contact_pt[1]), 0.14, color=C_MAGENTA, zorder=12)
    ax_side.add_patch(contact_circle)
    ax_side.plot(contact_pt[0], contact_pt[1], marker="o", markersize=5, color="#FFFFFF", zorder=13)
    ax_side.text(contact_pt[0], road_z - 0.30, "Tire Contact Patch (Z_road = 0)\n[v_bottom ray intersection]", 
                 fontsize=9, fontweight="bold", color=C_DARKMAGENTA, ha="center", va="top")

    # Raycast Line of Sight (Passing through C, v_bottom on sensor, and striking Contact Patch)
    # Line from Cam to Contact Point:
    ax_side.plot([cam_x, contact_pt[0]], [cam_z, contact_pt[1]], color=C_MAGENTA, linewidth=2.5, zorder=6)
    
    # Mark v_bottom on the virtual sensor plane:
    # Ray vector:
    ray_dir = contact_pt - np.array([cam_x, cam_z])
    ray_unit = ray_dir / np.linalg.norm(ray_dir)
    # Intersection with sensor plane:
    # parametric: cam + t * ray_unit such that (pt - sens_center) . u_opt = 0
    t_sens = sens_dist / np.dot(ray_unit, u_opt)
    v_bottom_pt = np.array([cam_x, cam_z]) + t_sens * ray_unit
    ax_side.plot(v_bottom_pt[0], v_bottom_pt[1], marker="o", markersize=6, color=C_ROSE, zorder=9)
    ax_side.text(v_bottom_pt[0] + 0.12, v_bottom_pt[1] - 0.05, "v_bottom (Tire Contact Pixel)", 
                 fontsize=8.5, fontweight="bold", color=C_ROSE, ha="left")

    # Pixel deviation delta_v = v_bottom - c_y
    ax_side.plot([sens_center[0], v_bottom_pt[0]], [sens_center[1], v_bottom_pt[1]], 
                 color=C_ROSE, linewidth=3.5, zorder=8)
    ax_side.text((sens_center[0] + v_bottom_pt[0])/2 - 0.15, (sens_center[1] + v_bottom_pt[1])/2 - 0.18, 
                 "Δv = (v_bottom - c_y)", fontsize=8, color=C_ROSE, ha="right")

    # Angle Arcs:
    # 1. theta_mount (between Horizon and Optical Boresight)
    arc_rad1 = 2.0
    arc1 = Arc((cam_x, cam_z), arc_rad1*2, arc_rad1*2, angle=0, theta1=-theta_deg, theta2=0,
               color=C_BLUE, linewidth=1.8, zorder=8)
    ax_side.add_patch(arc1)
    ax_side.text(cam_x + arc_rad1 + 0.15, cam_z - 0.12, "θ_mount (5.42°)", 
                 fontsize=8.5, fontweight="bold", color=C_BLUE, va="center")

    # 2. Total Depression Angle beta (between Horizon and Contact Ray)
    beta_rad = np.arctan2(cam_z - road_z, contact_pt[0] - cam_x)
    beta_deg = np.degrees(beta_rad)
    arc_rad2 = 2.6
    arc2 = Arc((cam_x, cam_z), arc_rad2*2, arc_rad2*2, angle=0, theta1=-beta_deg, theta2=0,
               color=C_DARKMAGENTA, linewidth=2.0, zorder=8)
    ax_side.add_patch(arc2)
    ax_side.text(cam_x + arc_rad2 + 0.15, cam_z - 0.55, "β = θ_mount + α\n(Total Depression Angle)", 
                 fontsize=9, fontweight="bold", color=C_DARKMAGENTA, va="center")

    # Longitudinal Distance Dimension (Y_v)
    ax_side.annotate("", xy=(contact_pt[0], road_z + 0.35), xytext=(cam_x, road_z + 0.35),
                     arrowprops=dict(arrowstyle="<->", color=C_EMERALD, lw=2.0), zorder=9)
    ax_side.plot([cam_x, cam_x], [road_z, cam_z], color=C_GRAY, linestyle=":", lw=1)
    ax_side.plot([contact_pt[0], contact_pt[0]], [road_z, road_z + 0.5], color=C_GRAY, linestyle=":", lw=1)
    
    # Formula callout badge for Y_v
    formula_box = patches.FancyBboxPatch((4.0, road_z + 0.45), 2.8, 0.65, boxstyle="round,pad=0.06,rounding_size=0.1",
                                         facecolor="#ECFDF5", edgecolor=C_EMERALD, linewidth=1.5, zorder=10)
    ax_side.add_patch(formula_box)
    ax_side.text(5.4, road_z + 0.77, "Longitudinal Range Solution:\n", fontsize=8, color="#065F46", ha="center")
    ax_side.text(5.4, road_z + 0.60, "Y_v = H_cam / tan(β)", fontsize=10.5, fontweight="bold", color=C_EMERALD, ha="center")

    ax_side.set_xlim(-0.2, 10.0)
    ax_side.set_ylim(0.4, 5.2)
    ax_side.axis("off")


    # =========================================================================
    # 2. BOTTOM PANEL: TOP VIEW (Azimuth Plane X-Y)
    # =========================================================================
    ax_top.set_title("AZIMUTH VIEW: Horizontal FOV, Lateral Pixel Deviation (Δu) & Lateral Position (X_v)",
                     fontsize=12, fontweight="bold", color=C_NAVY, pad=12, loc="left", fontfamily="sans-serif")
    
    # Coordinates in Top View:
    # Vehicle Centerline runs along X_center = 2.0 (vertical line across the plot)
    # Host Vehicle at Y_top = 0.8. Target Vehicle at Y_top = 7.8.
    # Longitudinal distance is vertical in top view, lateral is horizontal.
    # To keep aligned with Side view, let's map:
    # Horizontal axis = Forward Longitudinal (Y_v) [same as side view!]
    # Vertical axis = Lateral Offset (X_v)
    
    # Host vehicle travel axis (Centerline) at Lateral Z_top = 1.8
    y_centerline = 1.8
    
    # Road lanes
    ax_top.axhline(y_centerline + 1.2, color=C_ROAD, linewidth=2.5, linestyle="-")
    ax_top.axhline(y_centerline - 1.2, color=C_ROAD, linewidth=2.5, linestyle="-")
    ax_top.axhline(y_centerline, color="#CBD5E1", linewidth=1.5, linestyle="--")
    ax_top.text(0.3, y_centerline + 1.30, "Road Lane Boundary (+Left)", fontsize=8, color=C_GRAY)
    ax_top.text(0.3, y_centerline - 1.35, "Road Lane Boundary (-Right)", fontsize=8, color=C_GRAY)
    
    # Host Vehicle footprint
    h_box = patches.Rectangle((0.4, y_centerline - 0.45), 1.6, 0.9,
                              facecolor="#E2E8F0", edgecolor=C_GRAY, linewidth=1.5, linestyle="--", zorder=3)
    ax_top.add_patch(h_box)
    ax_top.text(1.2, y_centerline, "Host Vehicle\nCenterline", fontsize=8, color=C_NAVY, ha="center", va="center")
    
    # Camera Mount Point (with lateral offset delta_X)
    delta_x = 0.25  # visual lateral offset of phone mount
    cam_top_x = cam_x
    cam_top_y = y_centerline + delta_x
    cam_top_circle = plt.Circle((cam_top_x, cam_top_y), 0.12, color=C_BLUE, zorder=10)
    ax_top.add_patch(cam_top_circle)
    ax_top.plot(cam_top_x, cam_top_y, marker="o", markersize=4, color="#FFFFFF", zorder=11)
    ax_top.text(cam_top_x, cam_top_y + 0.22, "Windshield Camera Mount (ΔX)", fontsize=8.5, fontweight="bold", color=C_BLUE, ha="center")

    # Camera HFOV Cone (e.g. ±32° HFOV)
    hfov_deg = 32.0
    hfov_rad = np.radians(hfov_deg)
    hfov_len = 8.2
    
    fov_pts = [
        [cam_top_x, cam_top_y],
        [cam_top_x + hfov_len, cam_top_y + hfov_len * np.tan(hfov_rad)],
        [cam_top_x + hfov_len, cam_top_y - hfov_len * np.tan(hfov_rad)]
    ]
    fov_poly = patches.Polygon(fov_pts, closed=True, facecolor="#EFF6FF", edgecolor="#93C5FD", 
                               linestyle=":", linewidth=1.2, alpha=0.6, zorder=2)
    ax_top.add_patch(fov_poly)
    ax_top.text(cam_top_x + hfov_len - 0.2, cam_top_y + hfov_len * np.tan(hfov_rad) - 0.2, 
                "Camera HFOV Boundary", fontsize=8, color="#60A5FA", ha="right")

    # Camera Optical Axis (straight forward)
    ax_top.plot([cam_top_x, cam_top_x + hfov_len], [cam_top_y, cam_top_y], 
                color=C_LIGHTBLUE, linestyle="-.", linewidth=1.5, zorder=4)
    ax_top.text(cam_top_x + 3.2, cam_top_y + 0.10, "Optical Centerline (c_x)", fontsize=8, color="#0284C7")

    # Target Vehicle Footprint (at Y_v = target_x, lateral X_v = target_lat)
    target_lat = y_centerline - 0.35  # target vehicle slightly to the right of lane center
    tv_top_w = 1.6
    tv_top_h = 0.8
    tv_top_box = patches.FancyBboxPatch((target_x - tv_top_w/2, target_lat - tv_top_h/2), tv_top_w, tv_top_h,
                                        boxstyle="round,pad=0.06,rounding_size=0.1",
                                        facecolor="#F1F5F9", edgecolor=C_NAVY, linewidth=1.8, zorder=5)
    ax_top.add_patch(tv_top_box)
    ax_top.text(target_x, target_lat, "Lead Vehicle\n(X_v, Y_v)", 
                fontsize=8.5, fontweight="bold", color=C_NAVY, ha="center", va="center")

    # Optical Ray from Camera to Target Center
    ax_top.plot([cam_top_x, target_x], [cam_top_y, target_lat], color=C_MAGENTA, linewidth=2.2, zorder=6)
    
    # Target centroid point
    ax_top.plot(target_x, target_lat, marker="o", markersize=6, color=C_DARKMAGENTA, zorder=8)

    # Lateral Distance Dimension (X_v)
    ax_top.annotate("", xy=(target_x, target_lat), xytext=(target_x, cam_top_y),
                    arrowprops=dict(arrowstyle="<->", color=C_AMBER, lw=1.8), zorder=9)
    ax_top.plot([cam_top_x, target_x + 0.3], [cam_top_y, cam_top_y], color=C_GRAY, linestyle=":", lw=1)
    
    # Formula callout badge for X_v
    formula_x_box = patches.FancyBboxPatch((target_x - 3.4, target_lat - 0.65), 3.2, 0.50,
                                           boxstyle="round,pad=0.05,rounding_size=0.08",
                                           facecolor="#FFFBEB", edgecolor=C_AMBER, linewidth=1.5, zorder=10)
    ax_top.add_patch(formula_x_box)
    ax_top.text(target_x - 1.8, target_lat - 0.45, "X_v = Y_v · ((u - c_x) / f_x) · cos(θ) + ΔX", 
                fontsize=8.5, fontweight="bold", color="#92400E", ha="center", va="center")

    # Virtual Sensor pixel projection (Horizontal)
    sens_top_dist = 1.6
    u_sens_center = cam_top_y
    u_target_proj = cam_top_y + sens_top_dist * (target_lat - cam_top_y) / (target_x - cam_top_x)
    # Sensor bar
    ax_top.plot([cam_top_x + sens_top_dist, cam_top_x + sens_top_dist], [u_sens_center - 0.6, u_sens_center + 0.6],
                color=C_NAVY, linewidth=2.5, zorder=7)
    ax_top.plot(cam_top_x + sens_top_dist, u_sens_center, marker="x", markersize=6, color=C_BLUE, mew=2, zorder=8)
    ax_top.plot(cam_top_x + sens_top_dist, u_target_proj, marker="o", markersize=5, color=C_ROSE, zorder=9)
    ax_top.text(cam_top_x + sens_top_dist + 0.1, u_target_proj - 0.12, "u_center pixel", fontsize=8, color=C_ROSE)
    ax_top.text(cam_top_x + sens_top_dist + 0.1, u_sens_center + 0.15, "c_x", fontsize=8, color=C_BLUE)

    ax_top.set_xlim(-0.2, 10.0)
    ax_top.set_ylim(0.2, 3.4)
    ax_top.axis("off")

    # Save outputs
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png), exist_ok=True)
    fig.savefig(output_png, dpi=300, bbox_inches="tight")
    print(f"[OK] Generated High-Res PNG: {output_png}")
    
    if output_svg:
        fig.savefig(output_svg, format="svg", bbox_inches="tight")
        print(f"[OK] Generated Clean Vector SVG: {output_svg}")
    
    plt.close(fig)

if __name__ == "__main__":
    out_dir = r"C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\intel\presentations\assets"
    png_path = os.path.join(out_dir, "ipm_ground_plane_raycast_geometry.png")
    svg_path = os.path.join(out_dir, "ipm_ground_plane_raycast_geometry.svg")
    create_ipm_diagram(png_path, svg_path)
    
    # Mirror into intel/Vision/assets
    vision_dir = r"C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\intel\Vision\assets"
    create_ipm_diagram(os.path.join(vision_dir, "ipm_ground_plane_raycast_geometry.png"),
                       os.path.join(vision_dir, "ipm_ground_plane_raycast_geometry.svg"))
