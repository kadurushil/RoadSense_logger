# RoadSense Implementation Plans & Walkthroughs Index

> **Total Documents:** 145 files (`01_`?`99_` chronological pairs across 8 subsystem folders)  
> **Last Reorganized:** 2026-11-17

## 1. Subsystem Folders (Quick Navigation)

| Folder | Files | Focus Area |
|---|---:|---|
| [`01_RADAR/`](01_RADAR/) | 25 | TI AWR1843BOOST TLV, CP2105 3.125 Mbps UART, USB DMA, Bug #29 scheduler starvation, Display Pacer |
| [`02_CAMERA_AND_CALIBRATION/`](02_CAMERA_AND_CALIBRATION/) | 18 | Camera2 preview/latency, Road AE, 6-DOF spatial calibration, optical pitch, horizon alignment |
| [`03_IMU_AND_KINEMATICS/`](03_IMU_AND_KINEMATICS/) | 26 | LSM6DSL 100-200 Hz acquisition, IMU jitter, 3D attitude viz, yaw/vehicle frame transforms, kinematics |
| [`04_GNSS_AND_SYNC/`](04_GNSS_AND_SYNC/) | 14 | GNSS dual-stream separation, cross-sensor timing audit, HTML audit reports, precision logging |
| [`05_MCAP_AND_TOOLING/`](05_MCAP_AND_TOOLING/) | 26 | Foxglove MCAP pipeline, zero-cost upright video, web dashboard, ADB sync, Python diagnostics |
| [`06_UI_AND_PERFORMANCE/`](06_UI_AND_PERFORMANCE/) | 11 | Compose root isolation, 60 Hz jitter elimination, UI stutter eradication, Phase 1-6 perf plans |
| [`07_PERCEPTION_AND_VALIDATION/`](07_PERCEPTION_AND_VALIDATION/) | 7 | ADAS tracking/FCW/BSD master roadmap, CV perception fusion, real-world validation |
| [`08_HANDOVER_AND_DOCS/`](08_HANDOVER_AND_DOCS/) | 18 | Executive handover, documentation sync, Week 2/3 presentations, codebase cleanup, capabilities |

## 2. Complete Chronological Master Index (`2026-09-22` ? `2026-11-16`)

| # | Date | Subsystem | Document | Commit |
|---:|---|---|---|---|
| 01 | 2026-09-22 | `02_CAMERA_AND_CALIBRATION` | [`01_hybrid_radar_lollipop_footprint_plan.md`](02_CAMERA_AND_CALIBRATION/01_hybrid_radar_lollipop_footprint_plan.md) | `0ca085c` |
| 02 | 2026-09-22 | `02_CAMERA_AND_CALIBRATION` | [`02_Radar_Camera_Calibration_Walkthrough.md`](02_CAMERA_AND_CALIBRATION/02_Radar_Camera_Calibration_Walkthrough.md) | `0ca085c` |
| 02 | 2026-09-22 | `02_CAMERA_AND_CALIBRATION` | [`02_radar_camera_spatial_calibration_plan.md`](02_CAMERA_AND_CALIBRATION/02_radar_camera_spatial_calibration_plan.md) | `0ca085c` |
| 02 | 2026-09-22 | `02_CAMERA_AND_CALIBRATION` | [`02_radar_camera_spatial_calibration_walkthrough.md`](02_CAMERA_AND_CALIBRATION/02_radar_camera_spatial_calibration_walkthrough.md) | `0ca085c` |
| 03 | 2026-09-22 | `01_RADAR` | [`03_custom_mrr_v2_tlv_pipeline_plan.md`](01_RADAR/03_custom_mrr_v2_tlv_pipeline_plan.md) | `eef6ba7` |
| 04 | 2026-09-23 | `05_MCAP_AND_TOOLING` | [`04_mcap_phase1_walkthrough.md`](05_MCAP_AND_TOOLING/04_mcap_phase1_walkthrough.md) | `80b6d1b` |
| 05 | 2026-09-23 | `05_MCAP_AND_TOOLING` | [`05_mcap_phase1_implementation_plan.md`](05_MCAP_AND_TOOLING/05_mcap_phase1_implementation_plan.md) | `80b6d1b` |
| 06 | 2026-09-23 | `03_IMU_AND_KINEMATICS` | [`06_imu_sensor_implementation_plan.md`](03_IMU_AND_KINEMATICS/06_imu_sensor_implementation_plan.md) | `373f795` |
| 07 | 2026-09-23 | `08_HANDOVER_AND_DOCS` | [`07_DEVICE_SENSOR_AND_CAMERA_CAPABILITIES.md`](08_HANDOVER_AND_DOCS/07_DEVICE_SENSOR_AND_CAMERA_CAPABILITIES.md) | `373f795` |
| 08 | 2026-09-23 | `08_HANDOVER_AND_DOCS` | [`08_README.md`](08_HANDOVER_AND_DOCS/08_README.md) | `373f795` |
| 09 | 2026-09-23 | `08_HANDOVER_AND_DOCS` | [`09_walkthrough.md`](08_HANDOVER_AND_DOCS/09_walkthrough.md) | `373f795` |
| 10 | 2026-09-23 | `03_IMU_AND_KINEMATICS` | [`10_RADAR_PITCH_CALIBRATION_VIA_IMU.md`](03_IMU_AND_KINEMATICS/10_RADAR_PITCH_CALIBRATION_VIA_IMU.md) | `1906a73` |
| 11 | 2026-09-23 | `08_HANDOVER_AND_DOCS` | [`11_RoadSense_Handover_Documentation_Plan.md`](08_HANDOVER_AND_DOCS/11_RoadSense_Handover_Documentation_Plan.md) | `0399b85` |
| 11 | 2026-09-23 | `08_HANDOVER_AND_DOCS` | [`11_RoadSense_Handover_Documentation_Walkthrough.md`](08_HANDOVER_AND_DOCS/11_RoadSense_Handover_Documentation_Walkthrough.md) | `0399b85` |
| 12 | 2026-09-24 | `08_HANDOVER_AND_DOCS` | [`12_handover_executive_and_systems_restructuring_plan.md`](08_HANDOVER_AND_DOCS/12_handover_executive_and_systems_restructuring_plan.md) | `c26cd42` |
| 12 | 2026-09-24 | `08_HANDOVER_AND_DOCS` | [`12_handover_executive_and_systems_restructuring_walkthrough.md`](08_HANDOVER_AND_DOCS/12_handover_executive_and_systems_restructuring_walkthrough.md) | `c26cd42` |
| 13 | 2026-09-24 | `01_RADAR` | [`13_automated_vision_ground_truth_and_radar_validation_roadmap_plan.md`](01_RADAR/13_automated_vision_ground_truth_and_radar_validation_roadmap_plan.md) | `4739f78` |
| 13 | 2026-09-24 | `01_RADAR` | [`13_automated_vision_ground_truth_and_radar_validation_roadmap_walkthrough.md`](01_RADAR/13_automated_vision_ground_truth_and_radar_validation_roadmap_walkthrough.md) | `4739f78` |
| 14 | 2026-09-24 | `07_PERCEPTION_AND_VALIDATION` | [`14_validation_framework_ramp_up_brainstorm_and_roadmap_plan.md`](07_PERCEPTION_AND_VALIDATION/14_validation_framework_ramp_up_brainstorm_and_roadmap_plan.md) | `f0b0958` |
| 14 | 2026-09-24 | `07_PERCEPTION_AND_VALIDATION` | [`14_validation_framework_ramp_up_brainstorm_and_roadmap_walkthrough.md`](07_PERCEPTION_AND_VALIDATION/14_validation_framework_ramp_up_brainstorm_and_roadmap_walkthrough.md) | `f0b0958` |
| 15 | 2026-09-24 | `01_RADAR` | [`15_adas_radar_tracking_fcw_bsd_validation_master_plan.md`](01_RADAR/15_adas_radar_tracking_fcw_bsd_validation_master_plan.md) | `bd59ebf` |
| 15 | 2026-09-24 | `01_RADAR` | [`15_adas_radar_tracking_fcw_bsd_validation_master_walkthrough.md`](01_RADAR/15_adas_radar_tracking_fcw_bsd_validation_master_walkthrough.md) | `bd59ebf` |
| 16 | 2026-09-29 | `02_CAMERA_AND_CALIBRATION` | [`16_plan_hardware_accelerated_video_pipeline.md`](02_CAMERA_AND_CALIBRATION/16_plan_hardware_accelerated_video_pipeline.md) | `2b12dcb` |
| 16 | 2026-09-29 | `02_CAMERA_AND_CALIBRATION` | [`16_walkthrough_hardware_accelerated_video_pipeline.md`](02_CAMERA_AND_CALIBRATION/16_walkthrough_hardware_accelerated_video_pipeline.md) | `2b12dcb` |
| 17 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`17_plan_roadsense_gui_dashboard.md`](05_MCAP_AND_TOOLING/17_plan_roadsense_gui_dashboard.md) | `2b12dcb` |
| 17 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`17_walkthrough_roadsense_gui_dashboard.md`](05_MCAP_AND_TOOLING/17_walkthrough_roadsense_gui_dashboard.md) | `2b12dcb` |
| 18 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`18_plan_eliminating_manual_foxglove_image_rotation.md`](05_MCAP_AND_TOOLING/18_plan_eliminating_manual_foxglove_image_rotation.md) | `b6a87b1` |
| 19 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`19_plan_embed_layout_and_auto_upright_mcap.md`](05_MCAP_AND_TOOLING/19_plan_embed_layout_and_auto_upright_mcap.md) | `b6a87b1` |
| 20 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`20_plan_zero_cost_mcap_orientation_and_android_alignment.md`](05_MCAP_AND_TOOLING/20_plan_zero_cost_mcap_orientation_and_android_alignment.md) | `b6a87b1` |
| 20 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`20_walkthrough_zero_cost_mcap_orientation.md`](05_MCAP_AND_TOOLING/20_walkthrough_zero_cost_mcap_orientation.md) | `b6a87b1` |
| 21 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`21_plan_documentation_sync_mcap_and_web_dashboard.md`](05_MCAP_AND_TOOLING/21_plan_documentation_sync_mcap_and_web_dashboard.md) | `7dca3f4` |
| 21 | 2026-09-29 | `05_MCAP_AND_TOOLING` | [`21_walkthrough_documentation_sync_mcap_and_web_dashboard.md`](05_MCAP_AND_TOOLING/21_walkthrough_documentation_sync_mcap_and_web_dashboard.md) | `7dca3f4` |
| 22 | 2026-09-29 | `08_HANDOVER_AND_DOCS` | [`22_plan_roadsense_week2_presentation.md`](08_HANDOVER_AND_DOCS/22_plan_roadsense_week2_presentation.md) | `475ab59` |
| 22 | 2026-09-29 | `08_HANDOVER_AND_DOCS` | [`22_walkthrough_roadsense_week2_presentation.md`](08_HANDOVER_AND_DOCS/22_walkthrough_roadsense_week2_presentation.md) | `475ab59` |
| 23 | 2026-10-01 | `08_HANDOVER_AND_DOCS` | [`23_plan_roadsense_week2_presentation_validation_pivot.md`](08_HANDOVER_AND_DOCS/23_plan_roadsense_week2_presentation_validation_pivot.md) | `65b58f0` |
| 23 | 2026-10-01 | `08_HANDOVER_AND_DOCS` | [`23_walkthrough_roadsense_week2_presentation_validation_pivot.md`](08_HANDOVER_AND_DOCS/23_walkthrough_roadsense_week2_presentation_validation_pivot.md) | `65b58f0` |
| 24 | 2026-10-02 | `07_PERCEPTION_AND_VALIDATION` | [`24_staged_real_world_algorithm_validation_plan.md`](07_PERCEPTION_AND_VALIDATION/24_staged_real_world_algorithm_validation_plan.md) | `a4539ee` |
| 25 | 2026-10-06 | `06_UI_AND_PERFORMANCE` | [`25_plan_ui_interaction_desync_debugging.md`](06_UI_AND_PERFORMANCE/25_plan_ui_interaction_desync_debugging.md) | `1a6e0ed` |
| 26 | 2026-10-06 | `02_CAMERA_AND_CALIBRATION` | [`26_plan_automated_camera_calibration_pipeline.md`](02_CAMERA_AND_CALIBRATION/26_plan_automated_camera_calibration_pipeline.md) | `b0373da` |
| 26 | 2026-10-06 | `02_CAMERA_AND_CALIBRATION` | [`26_walkthrough_automated_camera_calibration_pipeline.md`](02_CAMERA_AND_CALIBRATION/26_walkthrough_automated_camera_calibration_pipeline.md) | `b0373da` |
| 27 | 2026-10-06 | `05_MCAP_AND_TOOLING` | [`27_plan_diagnostic_script_readability_formatting.md`](05_MCAP_AND_TOOLING/27_plan_diagnostic_script_readability_formatting.md) | `c5e0ba5` |
| 27 | 2026-10-06 | `05_MCAP_AND_TOOLING` | [`27_walkthrough_diagnostic_script_readability_formatting.md`](05_MCAP_AND_TOOLING/27_walkthrough_diagnostic_script_readability_formatting.md) | `c5e0ba5` |
| 28 | 2026-10-08 | `07_PERCEPTION_AND_VALIDATION` | [`28_roadsense_cv_perception_fusion_plan.md`](07_PERCEPTION_AND_VALIDATION/28_roadsense_cv_perception_fusion_plan.md) | `e55d146` |
| 28 | 2026-10-08 | `07_PERCEPTION_AND_VALIDATION` | [`28_roadsense_cv_perception_fusion_walkthrough.md`](07_PERCEPTION_AND_VALIDATION/28_roadsense_cv_perception_fusion_walkthrough.md) | `e55d146` |
| 29 | 2026-10-08 | `07_PERCEPTION_AND_VALIDATION` | [`29_phase1_to_phase3_plan.md`](07_PERCEPTION_AND_VALIDATION/29_phase1_to_phase3_plan.md) | `7affcea` |
| 30 | 2026-10-12 | `05_MCAP_AND_TOOLING` | [`30_mcap_temporal_alignment_and_enrichment_plan.md`](05_MCAP_AND_TOOLING/30_mcap_temporal_alignment_and_enrichment_plan.md) | `bafef7b` |
| 29 | 2026-10-12 | `07_PERCEPTION_AND_VALIDATION` | [`29_roadsense_cv_perception_phase1_to_phase3_walkthrough.md`](07_PERCEPTION_AND_VALIDATION/29_roadsense_cv_perception_phase1_to_phase3_walkthrough.md) | `bafef7b` |
| 31 | 2026-10-12 | `02_CAMERA_AND_CALIBRATION` | [`31_README.md`](02_CAMERA_AND_CALIBRATION/31_README.md) | `d901992` |
| 32 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`32_plan_fix_imu_yaw_delay_and_reverse_landscape_coordinates.md`](03_IMU_AND_KINEMATICS/32_plan_fix_imu_yaw_delay_and_reverse_landscape_coordinates.md) | `d901992` |
| 33 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`33_plan_imu_game_rotation_3d_orientation_visualizer.md`](03_IMU_AND_KINEMATICS/33_plan_imu_game_rotation_3d_orientation_visualizer.md) | `d901992` |
| 33 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`33_walkthrough_imu_game_rotation_3d_orientation_visualizer.md`](03_IMU_AND_KINEMATICS/33_walkthrough_imu_game_rotation_3d_orientation_visualizer.md) | `d901992` |
| 34 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`34_README.md`](03_IMU_AND_KINEMATICS/34_README.md) | `d901992` |
| 35 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`35_plan_phase2_imu_assisted_camera_alignment_and_radar_yaw_calibration.md`](03_IMU_AND_KINEMATICS/35_plan_phase2_imu_assisted_camera_alignment_and_radar_yaw_calibration.md) | `58e6fc5` |
| 35 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`35_walkthrough_phase2_imu_assisted_camera_alignment_and_radar_yaw_calibration.md`](03_IMU_AND_KINEMATICS/35_walkthrough_phase2_imu_assisted_camera_alignment_and_radar_yaw_calibration.md) | `58e6fc5` |
| 36 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`36_plan_camera_viewport_aspect_ratio_and_horizon_alignment.md`](03_IMU_AND_KINEMATICS/36_plan_camera_viewport_aspect_ratio_and_horizon_alignment.md) | `535ffbe` |
| 36 | 2026-10-12 | `03_IMU_AND_KINEMATICS` | [`36_walkthrough_camera_viewport_aspect_ratio_and_horizon_alignment.md`](03_IMU_AND_KINEMATICS/36_walkthrough_camera_viewport_aspect_ratio_and_horizon_alignment.md) | `535ffbe` |
| 37 | 2026-10-14 | `02_CAMERA_AND_CALIBRATION` | [`37_walkthrough_downstream_camera_alignment_and_radar_fusion_pipeline.md`](02_CAMERA_AND_CALIBRATION/37_walkthrough_downstream_camera_alignment_and_radar_fusion_pipeline.md) | `ef4ca6a` |
| 38 | 2026-10-14 | `04_GNSS_AND_SYNC` | [`38_precision_logging_and_imu_jitter_plan.md`](04_GNSS_AND_SYNC/38_precision_logging_and_imu_jitter_plan.md) | `ef4ca6a` |
| 39 | 2026-10-14 | `02_CAMERA_AND_CALIBRATION` | [`39_standard_camera_preview_and_sensor_stability_plan.md`](02_CAMERA_AND_CALIBRATION/39_standard_camera_preview_and_sensor_stability_plan.md) | `be048bd` |
| 38 | 2026-10-14 | `04_GNSS_AND_SYNC` | [`38_walkthrough_precision_logging_and_zero_jitter_imu.md`](04_GNSS_AND_SYNC/38_walkthrough_precision_logging_and_zero_jitter_imu.md) | `be048bd` |
| 40 | 2026-10-14 | `03_IMU_AND_KINEMATICS` | [`40_perfetto_imu_jitter_analysis_plan.md`](03_IMU_AND_KINEMATICS/40_perfetto_imu_jitter_analysis_plan.md) | `2636eea` |
| 41 | 2026-10-16 | `04_GNSS_AND_SYNC` | [`41_cross_sensor_timing_audit_plan.md`](04_GNSS_AND_SYNC/41_cross_sensor_timing_audit_plan.md) | `292ad97` |
| 41 | 2026-10-16 | `04_GNSS_AND_SYNC` | [`41_walkthrough_cross_sensor_timing_proximity_audit.md`](04_GNSS_AND_SYNC/41_walkthrough_cross_sensor_timing_proximity_audit.md) | `292ad97` |
| 42 | 2026-10-16 | `04_GNSS_AND_SYNC` | [`42_interactive_html_audit_report_and_dynamic_scaling_plan.md`](04_GNSS_AND_SYNC/42_interactive_html_audit_report_and_dynamic_scaling_plan.md) | `292ad97` |
| 43 | 2026-10-16 | `04_GNSS_AND_SYNC` | [`43_realtime_sync_and_quality_gate_plan.md`](04_GNSS_AND_SYNC/43_realtime_sync_and_quality_gate_plan.md) | `292ad97` |
| 44 | 2026-10-16 | `04_GNSS_AND_SYNC` | [`44_refactor_cross_sensor_sync_audit_plan.md`](04_GNSS_AND_SYNC/44_refactor_cross_sensor_sync_audit_plan.md) | `292ad97` |
| 44 | 2026-10-16 | `04_GNSS_AND_SYNC` | [`44_walkthrough_interactive_sync_audit_and_refactoring.md`](04_GNSS_AND_SYNC/44_walkthrough_interactive_sync_audit_and_refactoring.md) | `292ad97` |
| 45 | 2026-10-19 | `04_GNSS_AND_SYNC` | [`45_automate_all_logs_health_and_sync_audit_plan.md`](04_GNSS_AND_SYNC/45_automate_all_logs_health_and_sync_audit_plan.md) | `0e20ff6` |
| 46 | 2026-10-19 | `05_MCAP_AND_TOOLING` | [`46_foxglove_speed_video_sync_analysis_plan.md`](05_MCAP_AND_TOOLING/46_foxglove_speed_video_sync_analysis_plan.md) | `ce23f7f` |
| 46 | 2026-10-19 | `05_MCAP_AND_TOOLING` | [`46_plan_foxglove_speed_video_sync_analysis.md`](05_MCAP_AND_TOOLING/46_plan_foxglove_speed_video_sync_analysis.md) | `ce23f7f` |
| 47 | 2026-10-21 | `02_CAMERA_AND_CALIBRATION` | [`47_android_camera_sync_and_latency_hardening_plan.md`](02_CAMERA_AND_CALIBRATION/47_android_camera_sync_and_latency_hardening_plan.md) | `08b5b7f` |
| 48 | 2026-10-21 | `08_HANDOVER_AND_DOCS` | [`48_walkthrough.md`](08_HANDOVER_AND_DOCS/48_walkthrough.md) | `08b5b7f` |
| 49 | 2026-10-21 | `02_CAMERA_AND_CALIBRATION` | [`49_radar_camera_calibration_and_projection_fix_plan.md`](02_CAMERA_AND_CALIBRATION/49_radar_camera_calibration_and_projection_fix_plan.md) | `70f345d` |
| 50 | 2026-10-21 | `08_HANDOVER_AND_DOCS` | [`50_walkthrough_calibration_and_optical_tf_alignment.md`](08_HANDOVER_AND_DOCS/50_walkthrough_calibration_and_optical_tf_alignment.md) | `70f345d` |
| 51 | 2026-10-21 | `02_CAMERA_AND_CALIBRATION` | [`51_walkthrough_optical_pitch_sign_correction.md`](02_CAMERA_AND_CALIBRATION/51_walkthrough_optical_pitch_sign_correction.md) | `02cda35` |
| 52 | 2026-10-21 | `05_MCAP_AND_TOOLING` | [`52_optical_pitch_sign_correction_and_mcap_regeneration_plan.md`](05_MCAP_AND_TOOLING/52_optical_pitch_sign_correction_and_mcap_regeneration_plan.md) | `02cda35` |
| 53 | 2026-10-21 | `02_CAMERA_AND_CALIBRATION` | [`53_coordinate_transforms_and_bug24_documentation_plan.md`](02_CAMERA_AND_CALIBRATION/53_coordinate_transforms_and_bug24_documentation_plan.md) | `569c64a` |
| 53 | 2026-10-21 | `02_CAMERA_AND_CALIBRATION` | [`53_walkthrough_coordinate_transforms_documentation.md`](02_CAMERA_AND_CALIBRATION/53_walkthrough_coordinate_transforms_documentation.md) | `569c64a` |
| 54 | 2026-10-21 | `02_CAMERA_AND_CALIBRATION` | [`54_plan_master_sensor_alignment_and_decoupling_framework.md`](02_CAMERA_AND_CALIBRATION/54_plan_master_sensor_alignment_and_decoupling_framework.md) | `d0304f6` |
| 55 | 2026-10-21 | `02_CAMERA_AND_CALIBRATION` | [`55_master_sensor_alignment_framework_plan.md`](02_CAMERA_AND_CALIBRATION/55_master_sensor_alignment_framework_plan.md) | `d0304f6` |
| 56 | 2026-10-23 | `08_HANDOVER_AND_DOCS` | [`56_plan_roadsense_week3_presentation_ipm_and_validation.md`](08_HANDOVER_AND_DOCS/56_plan_roadsense_week3_presentation_ipm_and_validation.md) | `50305c0` |
| 56 | 2026-10-23 | `08_HANDOVER_AND_DOCS` | [`56_walkthrough_roadsense_week3_presentation_ipm_and_validation.md`](08_HANDOVER_AND_DOCS/56_walkthrough_roadsense_week3_presentation_ipm_and_validation.md) | `50305c0` |
| 57 | 2026-10-23 | `05_MCAP_AND_TOOLING` | [`57_plan_adb_sync_dual_progress.md`](05_MCAP_AND_TOOLING/57_plan_adb_sync_dual_progress.md) | `52f6785` |
| 57 | 2026-10-23 | `05_MCAP_AND_TOOLING` | [`57_walkthrough_adb_sync_dual_progress.md`](05_MCAP_AND_TOOLING/57_walkthrough_adb_sync_dual_progress.md) | `52f6785` |
| 58 | 2026-10-23 | `05_MCAP_AND_TOOLING` | [`58_plan_visualizer_json_progress.md`](05_MCAP_AND_TOOLING/58_plan_visualizer_json_progress.md) | `52f6785` |
| 58 | 2026-10-23 | `05_MCAP_AND_TOOLING` | [`58_walkthrough_visualizer_json_progress.md`](05_MCAP_AND_TOOLING/58_walkthrough_visualizer_json_progress.md) | `52f6785` |
| 59 | 2026-10-23 | `05_MCAP_AND_TOOLING` | [`59_plan_central_dashboard_vision_suite.md`](05_MCAP_AND_TOOLING/59_plan_central_dashboard_vision_suite.md) | `f8bd90d` |
| 59 | 2026-10-23 | `05_MCAP_AND_TOOLING` | [`59_walkthrough_central_dashboard_vision_suite.md`](05_MCAP_AND_TOOLING/59_walkthrough_central_dashboard_vision_suite.md) | `f8bd90d` |
| 60 | 2026-10-26 | `03_IMU_AND_KINEMATICS` | [`60_plan_dynamic_pitch_auto_calibration.md`](03_IMU_AND_KINEMATICS/60_plan_dynamic_pitch_auto_calibration.md) | `b791ad2` |
| 61 | 2026-10-26 | `03_IMU_AND_KINEMATICS` | [`61_plan_vehicle_orientation_and_3d_bounding_box.md`](03_IMU_AND_KINEMATICS/61_plan_vehicle_orientation_and_3d_bounding_box.md) | `b791ad2` |
| 61 | 2026-10-26 | `03_IMU_AND_KINEMATICS` | [`61_walkthrough_cuboid_center_offset_fix.md`](03_IMU_AND_KINEMATICS/61_walkthrough_cuboid_center_offset_fix.md) | `b791ad2` |
| 61 | 2026-10-26 | `03_IMU_AND_KINEMATICS` | [`61_walkthrough_phase1_orientation_and_3d_bounding_box.md`](03_IMU_AND_KINEMATICS/61_walkthrough_phase1_orientation_and_3d_bounding_box.md) | `b791ad2` |
| 62 | 2026-10-26 | `08_HANDOVER_AND_DOCS` | [`62_plan_documentation_synchronization_october_2026.md`](08_HANDOVER_AND_DOCS/62_plan_documentation_synchronization_october_2026.md) | `b791ad2` |
| 63 | 2026-10-26 | `03_IMU_AND_KINEMATICS` | [`63_walkthrough_vehicle_orientation_stabilization.md`](03_IMU_AND_KINEMATICS/63_walkthrough_vehicle_orientation_stabilization.md) | `306fe9b` |
| 64 | 2026-10-26 | `03_IMU_AND_KINEMATICS` | [`64_plan_vehicle_orientation_stabilization_and_kinematics.md`](03_IMU_AND_KINEMATICS/64_plan_vehicle_orientation_stabilization_and_kinematics.md) | `306fe9b` |
| 65 | 2026-10-27 | `03_IMU_AND_KINEMATICS` | [`65_plan_ego_turning_model_and_oncoming_orientation_fix.md`](03_IMU_AND_KINEMATICS/65_plan_ego_turning_model_and_oncoming_orientation_fix.md) | `ea28566` |
| 65 | 2026-10-27 | `03_IMU_AND_KINEMATICS` | [`65_walkthrough_ego_turning_model_and_oncoming_orientation_fix.md`](03_IMU_AND_KINEMATICS/65_walkthrough_ego_turning_model_and_oncoming_orientation_fix.md) | `ea28566` |
| 66 | 2026-10-28 | `03_IMU_AND_KINEMATICS` | [`66_plan_yaw_rate_smoothing_and_imu_filtering.md`](03_IMU_AND_KINEMATICS/66_plan_yaw_rate_smoothing_and_imu_filtering.md) | `0c882d7` |
| 66 | 2026-10-28 | `03_IMU_AND_KINEMATICS` | [`66_walkthrough_yaw_rate_smoothing_and_imu_calibration.md`](03_IMU_AND_KINEMATICS/66_walkthrough_yaw_rate_smoothing_and_imu_calibration.md) | `0c882d7` |
| 67 | 2026-10-28 | `05_MCAP_AND_TOOLING` | [`67_plan_mcap_vision_enrichment_and_debug_topics.md`](05_MCAP_AND_TOOLING/67_plan_mcap_vision_enrichment_and_debug_topics.md) | `1239b5c` |
| 67 | 2026-10-28 | `05_MCAP_AND_TOOLING` | [`67_walkthrough_mcap_vision_enrichment_and_debug_topics.md`](05_MCAP_AND_TOOLING/67_walkthrough_mcap_vision_enrichment_and_debug_topics.md) | `1239b5c` |
| 68 | 2026-10-29 | `04_GNSS_AND_SYNC` | [`68_plan_sync_audit_report_callouts.md`](04_GNSS_AND_SYNC/68_plan_sync_audit_report_callouts.md) | `0da33f6` |
| 69 | 2026-10-29 | `05_MCAP_AND_TOOLING` | [`69_plan_data_conversion_pipeline_audit.md`](05_MCAP_AND_TOOLING/69_plan_data_conversion_pipeline_audit.md) | `b6cd4c5` |
| 69 | 2026-10-29 | `05_MCAP_AND_TOOLING` | [`69_walkthrough_data_conversion_pipeline_audit.md`](05_MCAP_AND_TOOLING/69_walkthrough_data_conversion_pipeline_audit.md) | `b6cd4c5` |
| 70 | 2026-10-29 | `04_GNSS_AND_SYNC` | [`70_plan_gnss_dual_stream_network_separation.md`](04_GNSS_AND_SYNC/70_plan_gnss_dual_stream_network_separation.md) | `f6113d5` |
| 70 | 2026-10-29 | `04_GNSS_AND_SYNC` | [`70_walkthrough_gnss_dual_stream_network_separation.md`](04_GNSS_AND_SYNC/70_walkthrough_gnss_dual_stream_network_separation.md) | `f6113d5` |
| 71 | 2026-10-30 | `03_IMU_AND_KINEMATICS` | [`71_plan_imu_mounting_calibration_compensation.md`](03_IMU_AND_KINEMATICS/71_plan_imu_mounting_calibration_compensation.md) | `caec7a8` |
| 71 | 2026-10-30 | `03_IMU_AND_KINEMATICS` | [`71_walkthrough_imu_mounting_calibration_compensation.md`](03_IMU_AND_KINEMATICS/71_walkthrough_imu_mounting_calibration_compensation.md) | `caec7a8` |
| 72 | 2026-10-30 | `03_IMU_AND_KINEMATICS` | [`72_plan_imu_vehicle_frame_transformation.md`](03_IMU_AND_KINEMATICS/72_plan_imu_vehicle_frame_transformation.md) | `caec7a8` |
| 72 | 2026-10-30 | `03_IMU_AND_KINEMATICS` | [`72_walkthrough_imu_vehicle_frame_transformation.md`](03_IMU_AND_KINEMATICS/72_walkthrough_imu_vehicle_frame_transformation.md) | `caec7a8` |
| 73 | 2026-10-30 | `04_GNSS_AND_SYNC` | [`73_plan_imu_gnss_fusion_module.md`](04_GNSS_AND_SYNC/73_plan_imu_gnss_fusion_module.md) | `e055e7d` |
| 73 | 2026-10-30 | `04_GNSS_AND_SYNC` | [`73_walkthrough_imu_gnss_fusion_module.md`](04_GNSS_AND_SYNC/73_walkthrough_imu_gnss_fusion_module.md) | `e055e7d` |
| 74 | 2026-11-02 | `01_RADAR` | [`74_tlv_mss_v101_parity_update.md`](01_RADAR/74_tlv_mss_v101_parity_update.md) | `aa94651` |
| 75 | 2026-11-02 | `01_RADAR` | [`75_radar_high_speed_zero_loss_acquisition_plan.md`](01_RADAR/75_radar_high_speed_zero_loss_acquisition_plan.md) | `8e7e5c8` |
| 75 | 2026-11-02 | `01_RADAR` | [`75_radar_high_speed_zero_loss_acquisition_walkthrough.md`](01_RADAR/75_radar_high_speed_zero_loss_acquisition_walkthrough.md) | `8e7e5c8` |
| 76 | 2026-11-02 | `01_RADAR` | [`76_multithreading_and_data_parity_audit_plan.md`](01_RADAR/76_multithreading_and_data_parity_audit_plan.md) | `3012b3c` |
| 77 | 2026-11-04 | `01_RADAR` | [`77_cp2105_high_speed_streaming_deep_dive_plan.md`](01_RADAR/77_cp2105_high_speed_streaming_deep_dive_plan.md) | `ead6a89` |
| 77 | 2026-11-04 | `01_RADAR` | [`77_cp2105_high_speed_streaming_resolution_walkthrough.md`](01_RADAR/77_cp2105_high_speed_streaming_resolution_walkthrough.md) | `ead6a89` |
| 78 | 2026-11-04 | `01_RADAR` | [`78_radar_android_scheduling_and_priority_hardening_plan.md`](01_RADAR/78_radar_android_scheduling_and_priority_hardening_plan.md) | `ead6a89` |
| 79 | 2026-11-04 | `03_IMU_AND_KINEMATICS` | [`79_radar_uart_simulation_and_systrace_profiling_plan.md`](03_IMU_AND_KINEMATICS/79_radar_uart_simulation_and_systrace_profiling_plan.md) | `ead6a89` |
| 80 | 2026-11-04 | `08_HANDOVER_AND_DOCS` | [`80_codebase_cleanup_and_commit_plan.md`](08_HANDOVER_AND_DOCS/80_codebase_cleanup_and_commit_plan.md) | `ead6a89` |
| 81 | 2026-11-04 | `01_RADAR` | [`81_radar_scheduler_starvation_and_priority_hardening_plan.md`](01_RADAR/81_radar_scheduler_starvation_and_priority_hardening_plan.md) | `e3e4aec` |
| 81 | 2026-11-04 | `01_RADAR` | [`81_walkthrough_bug29_radar_scheduler_starvation_fixes.md`](01_RADAR/81_walkthrough_bug29_radar_scheduler_starvation_fixes.md) | `e3e4aec` |
| 82 | 2026-11-04 | `06_UI_AND_PERFORMANCE` | [`82_root_composition_isolation_plan.md`](06_UI_AND_PERFORMANCE/82_root_composition_isolation_plan.md) | `e3e4aec` |
| 83 | 2026-11-05 | `01_RADAR` | [`83_m20_vs_m32_deterministic_comparison_report.md`](01_RADAR/83_m20_vs_m32_deterministic_comparison_report.md) | `27f320e` |
| 84 | 2026-11-05 | `01_RADAR` | [`84_radar_codebase_change_audit_keep_vs_revert.md`](01_RADAR/84_radar_codebase_change_audit_keep_vs_revert.md) | `27f320e` |
| 85 | 2026-11-05 | `01_RADAR` | [`85_radar_hardware_dma_and_fullscreen_hud_hardening_plan.md`](01_RADAR/85_radar_hardware_dma_and_fullscreen_hud_hardening_plan.md) | `27f320e` |
| 86 | 2026-11-05 | `01_RADAR` | [`86_radar_root_cause_definitive_forensic_report.md`](01_RADAR/86_radar_root_cause_definitive_forensic_report.md) | `27f320e` |
| 87 | 2026-11-05 | `01_RADAR` | [`87_radar_usb_dma_and_salvaging_plan.md`](01_RADAR/87_radar_usb_dma_and_salvaging_plan.md) | `27f320e` |
| 87 | 2026-11-05 | `01_RADAR` | [`87_radar_usb_dma_and_salvaging_walkthrough.md`](01_RADAR/87_radar_usb_dma_and_salvaging_walkthrough.md) | `27f320e` |
| 88 | 2026-11-05 | `01_RADAR` | [`88_radar_hardware_clock_disciplining_and_jitter_elimination.md`](01_RADAR/88_radar_hardware_clock_disciplining_and_jitter_elimination.md) | `b5bf420` |
| 89 | 2026-11-06 | `01_RADAR` | [`89_plan_logcat_radar_display_pacer_diagnostics.md`](01_RADAR/89_plan_logcat_radar_display_pacer_diagnostics.md) | `7e19e1a` |
| 90 | 2026-11-06 | `01_RADAR` | [`90_walkthrough_radar_display_pacer_and_rendering_fixes.md`](01_RADAR/90_walkthrough_radar_display_pacer_and_rendering_fixes.md) | `7e19e1a` |
| 91 | 2026-11-06 | `01_RADAR` | [`91_radar_smooth_display_pacer_plan.md`](01_RADAR/91_radar_smooth_display_pacer_plan.md) | `7e19e1a` |
| 91 | 2026-11-06 | `01_RADAR` | [`91_radar_smooth_display_pacer_walkthrough.md`](01_RADAR/91_radar_smooth_display_pacer_walkthrough.md) | `7e19e1a` |
| 92 | 2026-11-06 | `06_UI_AND_PERFORMANCE` | [`92_ui_streamlining_and_60hz_jitter_elimination_plan.md`](06_UI_AND_PERFORMANCE/92_ui_streamlining_and_60hz_jitter_elimination_plan.md) | `ac27d75` |
| 92 | 2026-11-06 | `06_UI_AND_PERFORMANCE` | [`92_walkthrough_ui_streamlining_and_60hz_jitter_elimination.md`](06_UI_AND_PERFORMANCE/92_walkthrough_ui_streamlining_and_60hz_jitter_elimination.md) | `ac27d75` |
| 93 | 2026-11-06 | `06_UI_AND_PERFORMANCE` | [`93_ui_performance_audit_and_ground_up_optimization_plan.md`](06_UI_AND_PERFORMANCE/93_ui_performance_audit_and_ground_up_optimization_plan.md) | `905b974` |
| 94 | 2026-11-12 | `06_UI_AND_PERFORMANCE` | [`94_walkthrough_ui_optimization_and_virtualization.md`](06_UI_AND_PERFORMANCE/94_walkthrough_ui_optimization_and_virtualization.md) | `459fdb8` |
| 95 | 2026-11-12 | `06_UI_AND_PERFORMANCE` | [`95_ui_stutter_eradication_walkthrough.md`](06_UI_AND_PERFORMANCE/95_ui_stutter_eradication_walkthrough.md) | `f93dbe0` |
| 96 | 2026-11-12 | `06_UI_AND_PERFORMANCE` | [`96_ui_stutter_forensic_root_cause_and_eradication_plan.md`](06_UI_AND_PERFORMANCE/96_ui_stutter_forensic_root_cause_and_eradication_plan.md) | `f93dbe0` |
| 97 | 2026-11-13 | `06_UI_AND_PERFORMANCE` | [`97_bench_test_and_verification_plan.md`](06_UI_AND_PERFORMANCE/97_bench_test_and_verification_plan.md) | `9bdab5b` |
| 97 | 2026-11-13 | `06_UI_AND_PERFORMANCE` | [`97_performance_mitigation_and_instrumentation_plan.md`](06_UI_AND_PERFORMANCE/97_performance_mitigation_and_instrumentation_plan.md) | `9bdab5b` |
| 98 | 2026-11-16 | `06_UI_AND_PERFORMANCE` | [`98_phase_5_calibration_studio_imu_decoupling_plan.md`](06_UI_AND_PERFORMANCE/98_phase_5_calibration_studio_imu_decoupling_plan.md) | `7e4dd84` |
| 99 | 2026-11-16 | `08_HANDOVER_AND_DOCS` | [`99_intel_implementations_reorganization_plan.md`](08_HANDOVER_AND_DOCS/99_intel_implementations_reorganization_plan.md) | `new` |

| 100 | 2026-11-16 | `06_UI_AND_PERFORMANCE` | [`100_high_performance_ui_and_threading_overhaul_plan.md`](06_UI_AND_PERFORMANCE/100_high_performance_ui_and_threading_overhaul_plan.md) | `26cd3dc` |
| 101 | 2026-11-17 | `06_UI_AND_PERFORMANCE` | [`101_surfaceview_and_overlay_dedup_plan.md`](06_UI_AND_PERFORMANCE/101_surfaceview_and_overlay_dedup_plan.md) | `0c5e146` |
| 102 | 2026-11-17 | `06_UI_AND_PERFORMANCE` | [`102_ui_surfaceview_and_vsync_overhaul_walkthrough.md`](06_UI_AND_PERFORMANCE/102_ui_surfaceview_and_vsync_overhaul_walkthrough.md) | `b866676` |
| 103 | 2026-11-17 | `06_UI_AND_PERFORMANCE` | [`103_immersive_sbs_bev_scaling_and_intrinsics_fix_walkthrough.md`](06_UI_AND_PERFORMANCE/103_immersive_sbs_bev_scaling_and_intrinsics_fix_walkthrough.md) | `0d9840e` |
