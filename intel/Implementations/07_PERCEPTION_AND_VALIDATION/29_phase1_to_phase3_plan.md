# RoadSense Vision: Phase 1 to Phase 3 Implementation Plan

## Goal Description
Implement an offline, modular, and human-readable 3D vision pipeline that converts 2D bounding boxes into real-world $(X, Y, Z)$ coordinates via ground-plane geometry (Modality 1), injects them as an independent `/vision/geo_tracks` topic into Foxglove MCAP files without modifying source data, and benchmark vision accuracy against physical mmWave radar ground truth.

---

## User Review Required

> [!IMPORTANT]
> **Safety & Integrity Guarantees:**
> 1. **Zero Modifications to Source Logs:** All output files (JSON tracks, enriched MCAP, validation plots) will be generated exclusively under `tools/vision/output/` (or `output/`). The source folder `logs/session_20261006_095609/` will remain strictly read-only.
> 2. **Modular File Sizes:** Every Python file is designed with single responsibility and target line counts $< 200$ lines.
> 3. **Non-Destructive MCAP Ingestion:** `tools/vision/mcap/inject_vision_tracks.py` reads `session_20261006_095609.mcap`, copies existing streams, and writes out `output/mcap/session_20261006_095609_with_vision.mcap`.

---

## Proposed Changes

### 1. Geometry Module (`tools/vision/geometry/`)
Handles pure mathematical transformations without neural network dependencies.

#### [NEW] [`tools/vision/geometry/camera_extrinsics.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/camera_extrinsics.py)
* Loads `radar_camera_calib.json`.
* Computes $H_{\text{cam}} = \text{radarHeightM} + \text{heightOffsetM}$ ($1.45\text{ m}$) and pitch angle ($4.14^\circ$).
* Provides coordinate conversion methods between Camera Optical Frame ($\mathcal{F}_c$) and ISO 8855 Vehicle Frame ($\mathcal{F}_v$).

#### [NEW] [`tools/vision/geometry/ground_plane_projector.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/ground_plane_projector.py)
* Implements closed-form ray intersection:
  $$Y_v = \frac{H_{\text{cam}}}{\tan\left(\theta + \arctan\left(\frac{y_2 - c_y}{f_y}\right)\right)}$$
  $$X_v = \frac{c_x - c_x^{\text{principal}}}{f_x} \times Y_v + \Delta X$$
* Includes unit tests in `if __name__ == "__main__":`.

#### [NEW] [`tools/vision/geometry/box_dimensions.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/box_dimensions.py)
* **Option A (Class Priors):** Returns standard automotive 3D bounding box dimensions by object class (`car`, `truck`, `bus`, `motorcycle`, `person`).
* **Option B (Camera-Derived Dimensions):** Computes metric width $W$ and height $H$ from pixel dimensions $(w_{\text{px}}, h_{\text{px}})$ and depth $Z_c$.

---

### 2. Detection & Tracking Module (`tools/vision/detector/`)

#### [NEW] [`tools/vision/detector/yolo_detector.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/detector/yolo_detector.py)
* Loads `models/yolo26n.pt` using Ultralytics.
* Filters for automotive target classes and returns structured 2D detections.

#### [NEW] [`tools/vision/detector/track_manager.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/detector/track_manager.py)
* Maintains ByteTrack tracker state to assign persistent tracking IDs across video frames.

---

### 3. Pipeline Runner (`tools/vision/pipeline/`)

#### [NEW] [`tools/vision/pipeline/orientation_handler.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/orientation_handler.py)
* Inspects MP4 `tkhd` matrix to detect 180° / reverse landscape video rotation.

#### [NEW] [`tools/vision/pipeline/perception_runner.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/perception_runner.py)
* Coordinates video capture $\rightarrow$ orientation fix $\rightarrow$ YOLO detection $\rightarrow$ tracking $\rightarrow$ geometric projection.
* Reads `session_timeline.csv` to stamp each frame with hardware `elapsed_realtime_ns`.
* Exports structured output to `output/json/session_20261006_095609_vision_tracks.json`.

---

### 4. Non-Destructive MCAP Topic Injection (`tools/vision/mcap/`)

#### [NEW] [`tools/vision/mcap/mcap_scene_writer.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/mcap/mcap_scene_writer.py)
* Converts 3D vision detections into `foxglove.SceneUpdate` protobuf messages with electric magenta color styling (`rgba: 0.85, 0.1, 0.95, 0.75`).

#### [NEW] [`tools/vision/mcap/inject_vision_tracks.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/mcap/inject_vision_tracks.py)
* CLI tool that reads source MCAP, registers the `/vision/geo_tracks` channel, interleaves messages by timestamp, and writes `output/mcap/session_20261006_095609_with_vision.mcap`.

---

### 5. Automated Evaluation & Benchmarking (`tools/vision/eval/`)

#### [NEW] [`tools/vision/eval/spatial_associator.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/spatial_associator.py)
* Synchronizes Radar tracks and Vision tracks within a $\pm 33\text{ ms}$ time window and associates them via 2D ground Euclidean distance (Hungarian matching with $3.5\text{ m}$ gate).

#### [NEW] [`tools/vision/eval/error_metrics.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/error_metrics.py)
* Calculates Mean Absolute Error (MAE), Root Mean Square Error (RMSE), and range-binned errors ($0\text{--}15\text{m}$, $15\text{--}30\text{m}$, $>30\text{m}$).

#### [NEW] [`tools/vision/eval/plot_benchmarks.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/plot_benchmarks.py)
* Generates scatter plots ($Y_{\text{vision}}$ vs. $Y_{\text{radar}}$), error histograms, and pitch-correlation plots into `output/reports/`.

---

## Verification Plan

### Automated Tests
1. **Geometry Unit Tests:** Run standalone validation of `camera_extrinsics.py` and `ground_plane_projector.py` against reference ground truth values.
2. **Short-Clip Pipeline Dry Run:** Execute `perception_runner.py` on the first 150 frames of `session_20261006_095609` to verify JSON output and timestamp mapping.
3. **MCAP Topic Verification:** Verify that `inject_vision_tracks.py` creates a valid MCAP containing both `/radar/tracks` and `/vision/geo_tracks` using `mcap info`.

### Manual Verification
1. **Foxglove Visual Inspection:** Open `output/mcap/session_20261006_095609_with_vision.mcap` in Foxglove Studio. Verify that radar tracks (Cyan) and vision tracks (Magenta) track vehicles in the 3D scene panel.
2. **Statistical Error Review:** Inspect `output/reports/` metrics and scatter plots to confirm that geometric vision achieves expected accuracy.
