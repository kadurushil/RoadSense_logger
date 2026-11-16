# RoadSense × CV 3D Perception: Walkthrough

## Overview of Completed Work
We executed **Step 1** and **Step 2** of the phased 3D perception fusion roadmap:
1. **Step 1**: Created the dedicated knowledge base at `intel/Vision/` and authored [`RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Vision/RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md).
2. **Step 2**: Created the `tools/vision/` tools suite, set up model weights and checkpoints, and built and executed [`generate_baseline_perception.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/generate_baseline_perception.py).
3. **Execution & Audit**: Processed sample driving footage (`D:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4`), outputting both an annotated video (`tools/vision/output/baseline_annotated_video.mp4`) and structured detections JSON (`tools/vision/output/baseline_tracking_with_depth.json`).

---

## Directory Setup & Model Architecture

```
tools/vision/
 ├── models/
 │    └── yolo26n.pt                      # 5.5 MB Ultralytics YOLO26 weights (git-ignored)
 ├── checkpoints/                         # Junction to D:\Work\CV\Video-Depth-Anything\checkpoints (git-ignored)
 │    ├── metric_video_depth_anything_vitl.pth (1.5 GB ViT-L weights)
 │    └── metric_video_depth_anything_vits.pth (116 MB ViT-S weights)
 ├── video_depth_anything/                # VDA streaming model code
 ├── utils/                               # Spatial interpolation utilities
 ├── output/                              # Output directory for annotated video and JSON
 ├── generate_baseline_perception.py      # Master Baseline 3D Perception Generator
 └── README.md                            # Suite documentation and CLI instructions
```

---

## Verification & Execution Results

### Execution Command
```powershell
C:\ProgramData\miniconda3\envs\cv313\python.exe tools\vision\generate_baseline_perception.py `
    --input_video "D:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4" `
    --encoder vits `
    --max_frames 90 `
    --output_dir "tools\vision\output"
```

### Execution Metrics
* **Hardware**: NVIDIA T1000 8GB GPU via CUDA (`cv313` Python 3.11 environment).
* **Processing**: 90 frames @ $1920 \times 1080$ processed in 57.1s ($1.6\text{ FPS}$).
* **Tracking**: 3 unique track IDs identified across 102 total detections.
* **Artifacts Generated**:
  * Annotated Video: `tools/vision/output/baseline_annotated_video.mp4` ($6.7\text{ MB}$)
  * JSON Data: `tools/vision/output/baseline_tracking_with_depth.json` ($60\text{ KB}$)

---

## Baseline Anomaly Audit: Concrete Proof of Calibration Need

From Frame 1 of `baseline_tracking_with_depth.json`:

```json
{
  "track_id": 1,
  "class_name": "person",
  "confidence": 0.464,
  "bbox_pixels": { "x1": 1176, "y1": 459, "x2": 1202, "y2": 497 },
  "center_pixel": { "x": 1189.0, "y": 478.0 },
  "position_optical_m": {
    "x": 31.114,
    "y": -8.424,
    "z": 67.934
  }
}
```

### Quantitative Error Analysis
1. **The Negative $Y$ Sky-Person ($Y = -8.424\text{ m}$):**
   * *Observed:* Pedestrian standing on the road 68m away is calculated as **levitating 8.4m in the air**.
   * *Root Cause:* Downward camera mount pitch ($\approx 7^\circ$). Distant road objects appear above image center ($v = 478 < c_y = 540$). Without extrinsic pitch rotation $[R|T]$, optical back-projection treats this as negative elevation.
2. **Inflated Lateral Width ($X = 31.114\text{ m}$):**
   * *Observed:* Pedestrian appears 31m off the road to the right.
   * *Root Cause:* Uncalibrated placeholder $f_x = 500.0\text{ px}$. For a Full HD sensor with $68^\circ$ FOV, real $f_x \approx 1420\text{ px}$. The baseline artificially triples the lateral coordinate:
     $$\text{True } X \approx \frac{1189 - 960}{1420} \times 67.9 \approx 10.95\text{ m} \quad \text{vs. } \mathbf{31.11\text{ m}}$$
