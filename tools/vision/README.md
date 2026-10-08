# RoadSense Vision Suite (`tools/vision/`)

This directory houses the computer vision tools, neural network models, and multi-modal perception pipelines bridging **RoadSense** and the **`D:\Work\CV`** vision repository.

---

## Directory Layout

```
tools/vision/
 ├── models/
 │    └── yolo26n.pt                      # Ultralytics YOLO26 weights (copied from D:\Work\CV)
 ├── checkpoints/                         # Junction to D:\Work\CV\Video-Depth-Anything\checkpoints
 │    ├── metric_video_depth_anything_vitl.pth (1.5 GB ViT-L weights)
 │    └── metric_video_depth_anything_vits.pth (116 MB ViT-S weights)
 ├── video_depth_anything/                # Core neural modules from ByteDance Video Depth Anything
 ├── utils/                               # Helper utilities for depth transform & interpolation
 ├── output/                              # Output directory for annotated videos and JSON exports
 ├── generate_baseline_perception.py      # Master Step 2 Baseline 3D Perception Generator
 └── README.md                            # This documentation file
```

---

## Environment & Dependencies

The pipeline runs inside the dedicated conda environment **`cv313`**:
* **Python**: 3.10+ (Miniconda `cv313`)
* **PyTorch**: 2.1.1 + CUDA 12.1 (GPU acceleration enabled)
* **Ultralytics**: YOLO26 with ByteTrack
* **OpenCV**: 4.11.0

### Running with the Conda Environment:
```powershell
C:\ProgramData\miniconda3\envs\cv313\python.exe tools\vision\generate_baseline_perception.py --help
```

---

## Step 2: Running the Uncalibrated Baseline

The script supports clean, single-line commands without line breaks or backticks. It accepts either a direct video file OR a RoadSense session directory directly:

### 1. Simple Single-Line Run on Session (First 100 Frames):
```powershell
python tools\vision\generate_baseline_perception.py logs\session_20261002_141835 -n 100
```

### 2. Full Run on Session Video (All Frames, Fast Model):
```powershell
python tools\vision\generate_baseline_perception.py logs\session_20261002_141835
```

### 3. High-Precision Model Run (ViT-L):
```powershell
python tools\vision\generate_baseline_perception.py logs\session_20261002_141835 -e vitl
```

### 4. Run on Any Arbitrary Video File:
```powershell
python tools\vision\generate_baseline_perception.py "D:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4" -n 150
```

### Outputs Generated:
1. `tools\vision\output\baseline_annotated_video.mp4`:
   * High-definition video with bounding boxes, motion trails, 3D coordinate badges, and real-time telemetry diagnostics.
2. `tools\vision\output\baseline_tracking_with_depth.json`:
   * Complete frame-by-frame JSON recording bounding box coordinates, optical 3D positions $(X, Y, Z)$, and metadata.
