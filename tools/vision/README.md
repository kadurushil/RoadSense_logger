# RoadSense Vision Suite (`tools/vision/`)

This directory houses the computer vision tools, neural network models, and multi-modal perception pipelines bridging **RoadSense** and the **`D:\Work\CV`** vision repository.

It provides two complementary perception stacks:
1. **The Modular Geometric Vision Pipeline (Phases 1–3):** Ultra-fast, lightweight 3D vehicle & pedestrian tracking running closed-form ground-plane geometry ($Z_{\text{road}} = 0$) at **50+ FPS** without neural depth models, non-destructively injecting 3D cuboids into Foxglove MCAP files and benchmarking against mmWave radar ground truth.
2. **The Baseline Perception Generator (`generate_baseline_perception.py`):** Multi-modal reference runner supporting both high-speed 2D tracking and dense neural depth estimation using ByteDance Video Depth Anything (ViT-S / ViT-L).

---

## Directory Layout & Modular Architecture

```
tools/vision/
 ├── geometry/                            # Pure mathematical ground-plane projections & extrinsics
 │    ├── camera_extrinsics.py            # Loads radar_camera_calib.json, RDF <-> ISO frame conversions
 │    ├── ground_plane_projector.py       # Closed-form ray-to-ground intersection (v_bottom -> Y_v, X_v)
 │    └── box_dimensions.py               # Optical extent & class-prior 3D bounding geometry
 ├── detector/                            # Modular detection & ByteTrack tracking
 │    ├── yolo_detector.py                # Ultralytics YOLO26 inference & class filtering
 │    └── track_manager.py                # ByteTrack tracking state & lifetime management
 ├── pipeline/                            # Offline perception orchestration
 │    ├── orientation_handler.py          # MP4 container tkhd parsing & 180° rotation correction
 │    ├── timeline_loader.py              # Master session_timeline.csv nanosecond synchronization
 │    └── perception_runner.py            # Main CLI: video -> tracking -> 3D geometry -> JSON export
 ├── mcap/                                # Non-destructive MCAP topic injection
 │    ├── mcap_scene_writer.py            # Serializes 3D vision cuboids to foxglove.SceneUpdate
 │    └── inject_vision_tracks.py         # Injects /vision/geo_tracks without modifying logs/
 ├── eval/                                # Programmatic validation against radar ground truth
 │    ├── spatial_associator.py           # Spatio-temporal Hungarian track matching with distance gating
 │    ├── error_metrics.py                # MAE, RMSE, lateral error, and range-binned breakdown
 │    └── evaluate_vision_vs_radar.py     # End-to-end benchmark CLI & diagnostic report generator
 ├── models/                              # Neural network weights (yolo26n.pt)
 ├── checkpoints/                         # Windows directory junction to Video Depth Anything checkpoints
 ├── output/                              # Git-ignored output directory (json/, mcap/, reports/)
 ├── generate_baseline_perception.py      # Master Step 2 Baseline 3D Perception & Depth Generator
 └── README.md                            # This comprehensive documentation file
```

---

## Environment & Dependencies

The entire suite executes inside the dedicated Miniconda environment **`cv313`**:
* **Python**: 3.11 (`C:\ProgramData\miniconda3\envs\cv313\python.exe`)
* **PyTorch**: 2.1.1 + CUDA 12.1 (NVIDIA GPU hardware acceleration enabled)
* **Ultralytics**: YOLO26 with ByteTrack tracking
* **OpenCV**: 4.11.0 (Color mapping, video I/O, orientation transformations)
* **MCAP & Protobuf**: `mcap`, `mcap-protobuf-support`, `foxglove-schemas-protobuf`
* **Assignment**: `lap` (`lapjv` Jonker-Volgenant linear sum assignment)

---

## Key Engineering Achievements

### 1. Ground Plane Geometry (Modality 1)
* **Mathematical Derivation:** Vehicles, two-wheelers, and pedestrians contact the asphalt surface ($Z_{\text{road}} = 0$). By taking the bottom-center of the 2D bounding box $(c_x, y_2)$ as the tire contact patch, the real-world distance is solved in closed form:
  $$\beta = \theta + \arctan\left(\frac{y_2 - c_y}{f_y}\right)$$
  $$Y_v = \frac{H_{\text{cam}}}{\tan(\beta)}$$
  $$X_v = \frac{c_x - c_x^{\text{principal}}}{f_x} \times Y_v + \Delta X$$
  Where $H_{\text{cam}} = \text{radarHeightM} + \text{heightOffsetM} = 1.45\text{ m}$ and $\theta = \text{pitchDeg} = 4.14^\circ$.
* **3D Bounding Box Sizing ([`box_dimensions.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/box_dimensions.py)):**
  * **Option A (Class Priors):** Standard automotive dimensions (nuScenes / KITTI conventions: Car $1.8\text{m} \times 1.5\text{m} \times 4.2\text{m}$, Pedestrian $0.6\text{m} \times 1.7\text{m} \times 0.6\text{m}$).
  * **Option B (Camera-Derived Dimensions):** Uses the camera's high resolution to calculate physical width and height directly from pixel span ($W = \frac{w_{\text{px}}}{f_x} \times Z_c, H = \frac{h_{\text{px}}}{f_y} \times Z_c$), estimating longitudinal length via class aspect ratios ($L \approx 2.2 \times W$).
  * **Ground Elevation:** Automatically elevates the box center to $Z_v = H / 2$ so 3D cuboids rest naturally on the road surface rather than being buried underground.

### 2. Robust 180° Video Orientation & Inversion Correction
* **The Problem:** RoadSense videos recorded on smartphones mounted in reverse landscape mode appear upside down when processed with OpenCV, even though VLC media player renders them correctly.
* **The Solution ([`orientation_handler.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/orientation_handler.py)):** Inspects MP4 container `moov` and `tkhd` track header atoms across large recording files ($> 500\text{ MB}$) to identify the 180° rotation matrix ($a=-1, d=-1$). Frames are automatically rotated upright before detection, inference, and recording.

### 3. Master Hardware Synchronization
* **The Problem:** Video frames run at ~30 FPS; radar frames run at ~15–20 FPS. Naive frame index matching leads to temporal drift.
* **The Solution ([`timeline_loader.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/timeline_loader.py)):** Reads `session_timeline.csv`, which records the master `elapsed_realtime_ns` sequence across all hardware sensors (CAMERA, RADAR, CAN, IMU, GNSS). Each video frame is stamped with its exact monotonic nanosecond timestamp, ensuring sub-millisecond alignment with radar tracks.

### 4. Non-Destructive MCAP Topic Injection
* **Safety Contract:** The original session recordings in `logs/` (e.g. `session_20261006_095609.mcap`, 1.43 GB) are **100% read-only** and never modified in place.
* **Full Stream Merging (Default):** Takes the source MCAP, copies all existing channels (`/camera/video`, `/radar/points`, `/radar/tracks`, `/tf`, `/imu/data`), preserves all attachments (`foxglove.layout`, metadata), and injects `/vision/geo_tracks` sorted in strict chronological timestamp order.
* **Topic Styling ([`inject_vision_tracks.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/mcap/inject_vision_tracks.py)):** Encodes 3D vision detections into `foxglove.SceneUpdate` messages under topic `/vision/geo_tracks`. Renders cuboids and labels in **Electric Magenta** (`rgba: 0.85, 0.10, 0.95, 0.75`) to provide clear visual separation from radar tracks in Foxglove Studio.
* **Output Destination:** Writes out an enriched `session_with_vision.mcap` to `tools/vision/output/mcap/`.

### 5. Automated Radar-vs-Vision Benchmarking Engine
* **Matching Algorithm ([`spatial_associator.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/spatial_associator.py)):** Synchronizes vision and radar frames within $\pm 50\text{ ms}$, builds a 2D ground Euclidean distance cost matrix, and performs optimal bipartite matching via `lap.lapjv` with a $3.5\text{ m}$ gating cutoff.
* **Statistical Metrics ([`error_metrics.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/error_metrics.py)):** Computes Mean Absolute Error (MAE), Root Mean Square Error (RMSE), and range-binned breakdowns (Near: $0\text{--}15\text{m}$, Mid: $15\text{--}30\text{m}$, Far: $>30\text{m}$), exporting detailed JSON reports and printing terminal summaries.

---

## Execution Workflow: Modular Pipeline (Phases 1–3)

### Phase 1: Run Geometric Vision Tracking
Detects vehicles and pedestrians, projects them to 3D ground coordinates, and exports nanosecond-stamped JSON tracks:

```powershell
# Instant 3D projection from pre-existing 2D detections (0.7s for all 57,613 frames):
python tools\vision\pipeline\perception_runner.py logs\session_20261006_095609 --from_2d tools\vision\output\baseline_yolo_tracking.json

# Quick 300-frame slice from raw MP4 video (~10 seconds):
python tools\vision\pipeline\perception_runner.py logs\session_20261006_095609 -n 300

# Full 32-minute driving session from raw MP4 video:
python tools\vision\pipeline\perception_runner.py logs\session_20261006_095609
```
* **Output:** `tools\vision\output\json\session_20261006_095609_vision_tracks.json` (57,613 frames, 51.7 MB)

---

### Phase 2: Non-Destructive MCAP Topic Injection
Injects the 3D vision tracks into the original MCAP, creating an all-in-one unified file with both Radar and Vision tracks:

```powershell
# Primary: Create enriched MCAP with ALL original topics + /vision/geo_tracks:
python tools\vision\mcap\inject_vision_tracks.py logs\session_20261006_095609

# Optional: Create lightweight standalone tracks-only MCAP:
python tools\vision\mcap\inject_vision_tracks.py logs\session_20261006_095609 --standalone
```
* **Primary Output:** `tools\vision\output\mcap\session_20261006_095609_with_vision.mcap`
* **Temporal Alignment:** Master UTC Wall Clock synchronization guarantees that `/vision/geo_tracks` aligns with `/camera/video` to the exact nanosecond ($0.0\text{ ms}$ delta), eliminating the Foxglove multi-year timeline stretch bug.
* **Integrity Contract:** 100% of original messages (630,468 msgs across 12 channels) and all 4 attachments (`foxglove.layout`, metadata) are preserved byte-for-byte. Original `logs/` recordings remain read-only.
* **Visualizing in Foxglove Studio:**
  1. Open Foxglove Studio.
  2. Open `session_20261006_095609_with_vision.mcap`.
  3. The file opens with the pre-configured RoadSense cockpit layout.
  4. In the 3D scene panel:
     * Radar tracks appear in **Cyan / Amber / Crimson**.
     * Geometric Vision tracks appear in **Electric Magenta** (`/vision/geo_tracks`).

---

### Phase 3: Benchmark Against Radar Ground Truth
Runs automated spatio-temporal association and statistical accuracy evaluation:

```powershell
python tools\vision\eval\evaluate_vision_vs_radar.py logs\session_20261006_095609
```
* **Output:** `tools\vision\output\reports\session_20261006_095609_benchmark_report.json`
* **Terminal Summary:** Displays match rate, longitudinal forward MAE/RMSE ($\Delta Y$), lateral MAE/RMSE ($\Delta X$), and accuracy breakdown across near ($0\text{--}15\text{m}$), mid ($15\text{--}30\text{m}$), and far ($>30\text{m}$) ranges.

---

## Execution Workflow: Baseline Generator (`generate_baseline_perception.py`)

For end-to-end video annotation with bounding boxes and optional dense neural depth estimation:

### 1. High-Speed Standalone YOLO Mode (`-y`, ~45–55+ FPS):
Bypasses heavy depth models to run pure YOLO26 + ByteTrack tracking with video rendering:

```powershell
# Quick 300-frame slice:
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -y -n 300

# Full session run:
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -y
```
* **Outputs:**
  * `tools\vision\output\baseline_yolo_tracking.mp4` (annotated video with motion trails and bounding boxes)
  * `tools\vision\output\baseline_yolo_tracking.json` (semantic audit with class counts, confidences, and durations)

### 2. Full 3D Baseline Perception (YOLO + Video Depth Anything):
Runs YOLO26 tracking + Video Depth Anything metric depth estimation:

```powershell
# Fast model (ViT-S):
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -n 150

# High-precision model (ViT-L):
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -e vitl -n 150
```
* **Outputs:**
  * `tools\vision\output\baseline_annotated_video.mp4` (video with 3D coordinate badges and telemetry)
  * `tools\vision\output\baseline_tracking_with_depth.json` (depth-annotated trajectories)
