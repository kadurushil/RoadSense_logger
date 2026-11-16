# Walkthrough: Enriching Foxglove MCAP with Vision Debug Sub-Topics & State Matrix Observability

## Overview
This walkthrough demonstrates the newly implemented vision debugging architecture in RoadSense Foxglove MCAP containers:
1. **2D Video Overlay (`/vision/2d_boxes`)**: YOLO 2D bounding boxes, track labels, and ground contact points rendered using `foxglove.ImageAnnotations` directly over `/camera/video`.
2. **3D Scene Enrichment (`/vision/geo_tracks`)**: 3D cuboids, velocity arrows (`ArrowPrimitive`), and road contact footprints (`LinePrimitive`) on $Z_{\text{road}} = 0$.
3. **Trajectory Breadcrumbs (`/vision/trajectories`)**: 3D multi-second history trails (`LINE_STRIP`) per active track ID.
4. **Camera Field-of-View (`/vision/camera_frustum`)**: 3D ground projection boundary trapezoid.
5. **Structured Telemetry & State Matrix (`/vision/diagnostics`)**: 3-tier schema providing Ego Dynamics, Lead Obstacle 1-click plotting, and complete per-track **State Vector $\mathbf{x}$** and **Covariance Matrix $\mathbf{P}$**.
6. **Cockpit Layout Preset**: [`RoadSense_Cockpit_Layout.json`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/foxglove_layouts/RoadSense_Cockpit_Layout.json) updated to pre-activate `/vision/2d_boxes`, `/vision/geo_tracks`, `/vision/trajectories`, and `/vision/camera_frustum`.

---

## 1. Sub-Topic Architecture in Output MCAP

Verification on [`session_20261023_111120_with_vision.mcap`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/output/mcap/session_20261023_111120_with_vision.mcap) (1,384.1 MB):

| Topic Name | Schema | Message Encoding | Message Count | Description |
| :--- | :--- | :--- | :---: | :--- |
| **`/camera/video`** | `foxglove.CompressedVideo` | Protobuf | 35,330 | H.264 camera video stream (baseline). |
| **`/vision/2d_boxes`** | `foxglove.ImageAnnotations` | Protobuf | **35,330** | 1:1 exact frame-by-frame 2D YOLO bboxes & contact points. |
| **`/vision/geo_tracks`** | `foxglove.SceneUpdate` | Protobuf | **35,330** | 3D oriented cuboids, velocity arrows, and ground footprints. |
| **`/vision/trajectories`** | `foxglove.SceneUpdate` | Protobuf | **35,330** | 3D historical breadcrumbs per vehicle. |
| **`/vision/camera_frustum`** | `foxglove.SceneUpdate` | Protobuf | **3,533** | Optical ground projection boundary trapezoid (~3 Hz). |
| **`/vision/diagnostics`** | `roadsense.VisionDiagnostics` | JSON Schema | **35,330** | 3-tier telemetry, ego turning state, and State Matrices. |

Total messages in container: **565,729 messages** (144,853 vision messages injected across 5 sub-topics).

---

## 2. Verified Sample Payloads

### 2.1 `/vision/2d_boxes` (`foxglove.ImageAnnotations`)
Directly overlays onto `/camera/video` in Foxglove Studio's Image panel:
```json
{
  "points": [
    {
      "type": "LINE_LOOP",
      "thickness": 2.0,
      "outline_color": {"r": 0.85, "g": 0.10, "b": 0.95, "a": 0.85},
      "points": [
        {"x": 583.7, "y": 273.3},
        {"x": 639.6, "y": 273.3},
        {"x": 639.6, "y": 319.9},
        {"x": 583.7, "y": 319.9}
      ]
    }
  ],
  "circles": [
    {
      "position": {"x": 611.65, "y": 319.9},
      "diameter": 8.0,
      "fill_color": {"r": 0.10, "g": 1.00, "b": 0.20, "a": 0.95}
    }
  ],
  "texts": [
    {
      "text": "ID #1 | CAR 0.36 | 28.1m",
      "position": {"x": 583.7, "y": 269.3},
      "font_size": 12.0
    }
  ]
}
```

### 2.2 `/vision/diagnostics` (`roadsense.VisionDiagnostics`)
Provides 1-click plotting and full State Matrix inspection:
```json
{
  "frame_index": 0,
  "ego_speed_kmh": 19.3,
  "ego_yaw_rate_smoothed_degps": -0.11,
  "lead_obstacle": {
    "track_id": 1,
    "class_name": "car",
    "range_m": 28.05,
    "x_fwd_m": 30.14,
    "y_left_m": 0.82,
    "speed_kmh": 0.0,
    "ttc_sec": 5.23
  },
  "tracks": [
    {
      "track_id": 1,
      "class_name": "car",
      "confidence": 0.359,
      "range_m": 28.05,
      "is_moving": false,
      "state_matrix": {
        "state_vector": {
          "x_m": 30.139,
          "y_m": 0.818,
          "vx_mps": 0.0,
          "vy_mps": 0.0,
          "yaw_deg": 0.0,
          "yaw_rate_degps": 0.0
        },
        "covariance_diag": {
          "sigma_x_m": 0.452,
          "sigma_y_m": 0.241,
          "sigma_vx_mps": 0.10,
          "sigma_vy_mps": 0.08,
          "sigma_yaw_deg": 0.80,
          "sigma_yaw_rate_degps": 0.50
        },
        "covariance_matrix": [
          [0.2044, 0.0000, 0.0045, 0.0000, 0.0000, 0.0000],
          [0.0000, 0.0581, 0.0000, 0.0019, 0.0000, 0.0000],
          [0.0045, 0.0000, 0.0100, 0.0000, 0.0000, 0.0000],
          [0.0000, 0.0019, 0.0000, 0.0064, 0.0000, 0.0000],
          [0.0000, 0.0000, 0.0000, 0.0000, 0.6400, 0.0000],
          [0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.2500]
        ],
        "residuals": {
          "dx_m": 0.0,
          "dy_m": 0.0,
          "range_residual_m": 0.0
        }
      }
    }
  ]
}
```

---

## 3. Test Coverage

* **`scripts/tests/test_mcap_vision_enrichment.py`**:
  * 6 tests verifying ImageAnnotations, SceneUpdate cuboids & arrows, Trajectories line strips, Frustum projection, Diagnostics State Matrix, and Standalone MCAP packaging.
  * Result: `Ran 6 tests in 0.005s. OK`.
* **Full Python Suite**: `Ran 44 tests in 0.068s. OK`.
* **Gradle Suite**: `./gradlew testDebugUnitTest` `BUILD SUCCESSFUL in 8s`.
