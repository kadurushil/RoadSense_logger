# RoadSense Coordinate Systems — AI Agent Quick Reference Cheat Sheet

> **File:** `intel/COORDINATE_SYSTEMS_AI_CHEATSHEET.md`  
> **Audience:** Autonomous AI Coding Agents & Deterministic Automated Tooling  
> **Purpose:** High-density, zero-ambiguity reference for sensor frames, axis directions, matrix definitions, and transformation equations.  

---

## 1. Quick Frame & Axis Lookup Table

| Frame Name | Context | Standard | +X Axis | +Y Axis | +Z Axis | Heading / Yaw Convention |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`base_link`** | Vehicle Body / World Root | ISO 8855 FLU | **Forward** | **Left** | **Up** | CCW positive ($0^\circ$ = Ahead) |
| **`radar_link`** | TI AWR1843 Raw TLV | TI Native RFU | **Right** | **Forward** | **Up** | CW positive ($0^\circ$ = Ahead) |
| **`camera_optical`** | Pinhole Optical | OpenCV RDF | **Right** | **Down** | **Forward** | Pan Right positive |
| **`imu_device`** | Phone Sensor HAL | Android HAL | **Right** (short) | **Top** (long) | **Out of Screen** | Azimuth ($0^\circ$ = North, CW) |
| **`screen_pixels`** | Viewfinder / Video Canvas | Raster 2D | **Col (0 to W-1)** | **Row (0 to H-1)** | **Depth ($Z_c$)** | N/A |

---

## 2. Axis Conversions (1-to-1 Mapping)

### Radar (RFU) $\to$ Vehicle (`base_link` FLU)
$$X_{\text{base}} = Y_{\text{radar}}$$
$$Y_{\text{base}} = -X_{\text{radar}}$$
$$Z_{\text{base}} = Z_{\text{radar}} + H_{\text{radar}}$$
$$\text{heading}_{\text{base}} = -\text{heading}_{\text{radar}} + \psi_{\text{radar\_yaw\_offset}}$$

### Vehicle (`base_link` FLU) $\to$ Base Optical Frame (RDF)
$$X_c = -Y_{\text{base}}$$
$$Y_c = -Z_{\text{base}}$$
$$Z_c = +X_{\text{base}}$$

---

## 3. Transformation Matrices

### Base Optical Matrix ($R_0$, FLU $\to$ RDF)
```python
R0 = np.array([
    [ 0, -1,  0],
    [ 0,  0, -1],
    [ 1,  0,  0]
], dtype=float)
```

### Extrinsic Rotations in Camera Optical Frame
```python
# 1. Yaw (Pan around Camera Y-axis pointing down)
Ry = np.array([
    [ math.cos(yaw), 0, -math.sin(yaw)],
    [ 0,             1,  0],
    [ math.sin(yaw), 0,  math.cos(yaw)]
], dtype=float)

# 2. Pitch (Tilt around Camera X-axis pointing right: POSITIVE TILTS DOWN TOWARD ASPHALT)
# CRITICAL: Row 1 must have -sin(pitch), Row 2 must have +sin(pitch)!
Rx = np.array([
    [ 1, 0,                0],
    [ 0, math.cos(pitch), -math.sin(pitch)],
    [ 0, math.sin(pitch),  math.cos(pitch)]
], dtype=float)

# 3. Roll (Roll around Camera Z-axis boresight: Fold 180° for reverse landscape mount)
Rz = np.array([
    [ math.cos(roll), math.sin(roll), 0],
    [-math.sin(roll), math.cos(roll), 0],
    [ 0,              0,              1]
], dtype=float)

# Combined Transform: base_link -> camera_optical
R_total = Rz @ Rx @ Ry @ R0

# Camera optical rays back to base_link:
R_cam_to_base = R_total.T
```

### Extrinsic Translation Vector ($\mathbf{T}_{\text{cam}}$ in `base_link`)
```python
T_cam = np.array([
    -setback_m,       # e.g. -0.30 m (behind front bumper)
    -lateral_offset_m,# e.g.  0.00 m (centerline)
    radar_height_m + height_offset_m # e.g. 0.95 + 0.40 = 1.35 m
], dtype=float)
```

### Intrinsic Camera Matrix ($K$)
```python
K = np.array([
    [fx, 0.0, cx],
    [0.0, fy, cy],
    [0.0, 0.0, 1.0]
], dtype=float)

# 720p HD:  fx = fy = 991.00,  cx = 640.0, cy = 360.0
# 1080p FHD: fx = fy = 1486.50, cx = 960.0, cy = 540.0
```

---

## 4. Forward & Inverse Projection Equations

### Forward (3D $P_{\text{base}} \to$ 2D Pixels $[u, v]$)
```python
# 1. Transform point to camera optical frame:
P_cam = R_total @ (P_base - T_cam)

# 2. Pinhole projection:
u = cx + fx * (P_cam[0] / P_cam[2])
v = cy + fy * (P_cam[1] / P_cam[2])
```

### Inverse (2D Pixel $[u, v] \to$ 3D Ground Plane $Z_{\text{base}} = 0$)
```python
# 1. Optical ray:
ray_c = np.array([(u - cx) / fx, (v - cy) / fy, 1.0])

# 2. Ray in base_link:
ray_base = R_total.T @ ray_c

# 3. Intersection with asphalt (Z_base = 0):
t = -T_cam[2] / ray_base[2]
P_ground = T_cam + t * ray_base
```

---

## 5. Reverse Landscape Mount Rules (`Surface.ROTATION_270`)
* **Display Rotation:** $270^\circ$ (USB-C cable exits to the vehicle's left).
* **Video Buffer:** Upside down ($180^\circ$).
* **Zero-Copy Rule:** DO NOT re-encode MP4 with FFmpeg. Fold $180^\circ$ into `/tf` optical roll:
  $$\phi_{\text{effective}} = (\phi_{\text{calib}} + 180^\circ) \pmod{360^\circ}$$
* In Foxglove Studio, this orients the 3D camera frustum right side up in world space at 0% GPU load.

---

## 6. Deterministic Verification Invariant (AI Self-Test)
When validating any optical pitch rotation matrix $R_x(P)$ for a camera looking downward by $P > 0$:
```python
# Optical axis vector in camera_optical:
v_cam = np.array([0, 0, 1.0])

# Transformed into vehicle base_link:
v_base = R_total.T @ v_cam

# INVARIANT: Z component MUST BE NEGATIVE (pointing down toward asphalt):
assert v_base[2] < 0, f"FATAL: Camera points into sky! Z_base = {v_base[2]}"
# For pitch = 5.42 deg: v_base[2] == -sin(5.42 deg) == -0.0944
```

---

## 7. Canonical Source File Index

| Component | Language | Canonical File Path | Key Functions / Classes |
| :--- | :--- | :--- | :--- |
| **Android Projection Engine** | Kotlin | `app/src/main/java/.../SpatialProjectionEngine.kt` | `project2DRadarToScreen`, `solveExtrinsicsFromTouch` |
| **MCAP Extrinsics Publisher** | Python | `tools/convert_session_to_mcap.py` | `compute_camera_optical_tf`, `convert_session_to_mcap` |
| **Ground Plane Projector** | Python | `tools/vision/geometry/ground_plane_projector.py` | `GroundPlaneProjector`, `project_bottom_center_to_ground` |
| **IMU Orientation Remapper** | Kotlin | `app/src/main/java/.../ImuSample.kt` | `toEulerAnglesDeg`, `pitchDeg` |
| **Radar Model Parser** | Kotlin | `app/src/main/java/.../RadarPoint.kt` | `xM` (Right), `yM` (Forward), `zM` (Up) |
| **Human-Readable Master Manual** | Markdown | `intel/Coordinate Transforms.md` | Full diagrams, mathematical derivations, UX specs |
