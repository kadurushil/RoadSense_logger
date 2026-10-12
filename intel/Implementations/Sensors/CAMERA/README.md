# RoadSense Camera Subsystem & Computer Vision Pipelines

> **Directory:** `intel/Implementations/Sensors/CAMERA/`  
> **Component Package:** `com.bajajauto.roadsense.camera`, `com.bajajauto.roadsense.fusion`  
> **Status:** Active / Production Ready  
> **Last Updated:** 2026-10-12  

---

## 1. Executive Summary

The RoadSense Camera subsystem is responsible for real-time video capture, camera calibration, hardware-accelerated video encoding, and monocular computer vision feature tracking for spatial alignment with mmWave radar point clouds.

---

## 2. Directory Index & Documentation Hierarchy

| File | Type | Purpose |
| :--- | :--- | :--- |
| [`plan_automated_camera_calibration_pipeline.md`](plan_automated_camera_calibration_pipeline.md) | **Plan** | Automated camera calibration pipeline architecture and checkerboard detection formulation. |
| [`walkthrough_automated_camera_calibration_pipeline.md`](walkthrough_automated_camera_calibration_pipeline.md) | **Walkthrough** | Implementation verification and test runbook for automated camera intrinsics extraction. |
| [`plan_hardware_accelerated_video_pipeline.md`](plan_hardware_accelerated_video_pipeline.md) | **Plan** | Hardware-accelerated H.264/HEVC video pipeline utilizing MediaCodec and Camera2 surfaces. |
| [`walkthrough_hardware_accelerated_video_pipeline.md`](walkthrough_hardware_accelerated_video_pipeline.md) | **Walkthrough** | Performance profiling, frame synchronization, and timestamping validation for video recordings. |
