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

## Step 2: Running Perception & Tracking

The script supports clean, single-line commands without line breaks or backticks. It accepts either a direct video file OR a RoadSense session directory directly, and automatically corrects reverse landscape video orientation (180° rotation):

### 1. High-Speed Standalone YOLO Tracking (-y / ~45-55+ FPS):
Bypasses heavy depth models to run pure YOLO26 + ByteTrack tracking. Recommended for long driving logs:

```powershell
# Quick 300-frame slice (~6 seconds):
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -y -n 300

# Full 32-minute driving session (~18-20 minutes total runtime):
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -y
```

### 2. Full 3D Baseline Perception (YOLO + Depth Anything):
Runs YOLO26 tracking + Video Depth Anything metric depth estimation:

```powershell
# Fast model (VITS):
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -n 150

# High-precision model (VITL):
python tools\vision\generate_baseline_perception.py logs\session_20261006_095609 -e vitl -n 150
```

### 3. Automatic Inversion & Orientation Handling:
Smartphones mounted in reverse landscape mode (180°) are detected automatically via MP4 container metadata (`-r auto`) and rendered 100% upright. You can also manually specify rotation if needed: `-r 180` or `-r 0`.
