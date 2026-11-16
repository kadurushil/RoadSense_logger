# Implementation Plan: Eliminating Manual Foxglove Image Panel Rotation

> **Document:** `plan_eliminating_manual_foxglove_image_rotation.md`  
> **Status:** Draft / Pending User Decision  
> **Author:** Antigravity (AI Assistant)  

---

## 1. Goal Description

The user verified the newly exported MCAP file and noted:
> **"In this now I have to use the rotation tab in the general image panel settings. Is this fine or we can fix that as well ?"**

This plan evaluates:
1. **Why the rotation tab had to be toggled**: Foxglove's default Image Panel opens with `rotation: 0` unless the RoadSense layout preset is imported or the video stream is physically re-encoded.
2. **Three distinct architectural solutions** to eliminate manual user interaction:
   * **Solution 1 (Zero-Copy + Embedded Layout):** Embed the pre-configured layout with `"rotation": 180` directly into the `.mcap` file as a `foxglove.layout` attachment, so Foxglove loads the upright configuration automatically.
   * **Solution 2 (Automated Fast GPU NVENC Re-encode):** Make our newly optimized NVENC pipeline (650+ FPS, ~15 seconds total) the default when an inverted mount is detected. The physical video stream becomes universally upright, requiring **zero** panel adjustments anywhere.
   * **Solution 3 (Android In-App Mount Guidance or GPU Shader):** Guide the phone mounting orientation or apply in-app OpenGL ES texture rotation on Android.

---

## 2. Comparative Analysis of Solutions

| Dimension | Solution 1: Embedded Foxglove Layout | Solution 2: Automated Fast NVENC (Default) | Solution 3: Android Mount / GLES |
| :--- | :--- | :--- | :--- |
| **Conversion Speed** | **Instant (3.4 s, >11,000 FPS)** | **Very Fast (~15 s, ~650 FPS)** | Instant (on device) |
| **Manual Steps in Foxglove** | **Zero** (layout loads with `rotation: 180`) | **Zero** (video is physically upright) | **Zero** |
| **Compatibility with External Tools** | Upright in VLC/MP4; raw NALs inverted | **100% Upright everywhere** (VLC, Foxglove, Web Visualizer, OpenCV, Python) | 100% Upright everywhere |
| **GPU / Compute Overhead** | **0% GPU, 0% CPU** | ~80% GPU for 15 seconds | 0% PC / ~1ms on phone GPU |
| **Code Risk** | Minimal | Zero (already implemented and tested) | Moderate to High (if rewriting Camera2 to GLES) |

---

## 3. Solution Details

### Solution 1: Zero-Copy Bitstream + Embedded `foxglove.layout` in MCAP (Fastest, 3 Seconds)
Foxglove Studio supports embedded layout attachments in MCAP files:
* When an MCAP file contains an attachment named `foxglove.layout` with media type `application/json`, Foxglove Studio detects the layout on load and prompts or applies the pre-configured panel arrangement automatically.
* In `tools/convert_session_to_mcap.py`, we read `tools/foxglove_layouts/RoadSense_Cockpit_Layout.json` (which has `"rotation": 180` on `camera_view`) and attach it directly to the MCAP file:
  ```python
  with open("tools/foxglove_layouts/RoadSense_Cockpit_Layout.json", "rb") as lf:
      writer._writer.add_attachment(
          create_time=start_wall_ms * 1_000_000,
          log_time=start_wall_ms * 1_000_000,
          name="foxglove.layout",
          media_type="application/json",
          data=lf.read()
      )
  ```
* **User Experience**: When you open the `.mcap` in Foxglove, the layout automatically has rotation set to 180°. You never have to touch the panel settings.

---

### Solution 2: Fast NVENC Re-encode as Default for Inverted Mounts (~15 Seconds)
Because we already optimized the NVENC encoder to **650+ FPS** (with the 1-second keyframe seek fix):
* If `video_rotation == 180` is detected, the converter automatically applies the zero-latency NVENC 180° rotation in 15 seconds.
* The raw H.264 NAL units are physically upright in the file.
* **User Experience**:
  * You do not need to import any special layout.
  * You do not need to touch any rotation tab in Foxglove (it stays at default `rotation: 0`).
  * If someone opens the MCAP in Python, ROS, or Web Visualizer, the video is right-side up.

---

### Solution 3: Android In-App Guidance (0 Seconds PC Compute)
* **Why the video is 180° on Android**:
  The phone is currently clamped in **reverse landscape** (`Surface.ROTATION_270`, with the USB cable on the left).
  * If the phone is clamped in **standard landscape** (`Surface.ROTATION_90`, with the USB cable on the right):
    $$\text{rotationHint} = (90 - 90) = 0^\circ$$
    The camera sensor is naturally upright! Android records at 0°, the MP4 is 0°, and Foxglove is 0° with zero rotation needed anywhere!
* In `CameraDashboardCard.kt`, we can add a simple orientation badge showing whether the current mount orientation is Standard ($0^\circ$) or Inverted ($180^\circ$).

---

## 4. Recommendation & User Decision

> [!IMPORTANT]
> **Which experience do you prefer?**
> 1. **Option 1 (Maximum Speed: 3 seconds)**: Embed `foxglove.layout` directly inside every `.mcap` so Foxglove automatically opens with `rotation: 180` without touching the tab, keeping the 3-second export speed.
> 2. **Option 2 (Universal Upright Stream: 15 seconds)**: Automatically run our 650 FPS NVENC re-encode whenever 180° is detected, ensuring the video stream itself is physically upright in all panels and tools at default settings.
