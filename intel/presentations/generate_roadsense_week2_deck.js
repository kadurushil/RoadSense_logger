/**
 * RoadSense Week 2 Executive & Technical Progress Presentation Generator
 * 
 * Generates an 11-slide, 100% native vector PowerPoint presentation (.pptx)
 * showcasing the major architectural leaps from September 15 to September 29, 2026:
 * - 6-DOF Spatial Calibration & Closed-Form Reverse Touch Solver (O(1)).
 * - RViz-Inspired Hybrid Radar Lollipops & Perspective Ground Range Rings.
 * - 100 Hz LSM6DSL IMU Kinematics, Linear Accel & Orientation Quaternions.
 * - Zero-Dependency Local Web Dashboard & Real-Time SSE Streamer (localhost:8088).
 * - Foxglove MCAP High-Throughput Archiving (>11,500 FPS Zero-Cost vs. NVENC).
 * - Root Cause Analysis & Deep Post-Mortems for Bugs #11 through #15.
 * - Quantitative Benchmarks & Week 3 Edge Perception Roadmap.
 */

import pptxgen from "pptxgenjs";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export async function generateRoadSenseWeek2Presentation() {
    const pres = new pptxgen();

    // 1. Define Modern 16:9 Widescreen (13.333" x 7.500")
    pres.defineLayout({ name: "SCREEN16x9_FULL", width: 13.333, height: 7.500 });
    pres.layout = "SCREEN16x9_FULL";
    pres.author = "RoadSense Core Engineering Team";
    pres.company = "Bajaj Auto Ltd. - Advanced Perception & Telemetry";
    pres.title = "RoadSense: Week 2 Progress — Spatial Fusion, 100 Hz Kinematics & MCAP Perception Cockpit";

    // 2. High-Contrast Corporate & Automotive Palette (Pure White Background)
    const COLOR_BG = "FFFFFF";          // Crisp pure white background
    const COLOR_CARD_BG = "F8FAFC";     // Very light slate card fill
    const COLOR_CARD_BORDER = "CBD5E1"; // Subtle clean gray border
    const COLOR_PRIMARY = "1D4ED8";     // Deep cobalt blue (primary accents & data flows)
    const COLOR_SECONDARY = "0284C7";   // Cyan-blue (secondary stream highlights)
    const COLOR_SUCCESS = "15803D";     // Forest green (verified milestones & passing tests)
    const COLOR_WARNING = "D97706";     // Amber / warning highlights
    const COLOR_TEXT_MAIN = "0F172A";   // Deep slate / almost black for maximum readability
    const COLOR_TEXT_MUTED = "334155";  // Medium slate for body text
    const COLOR_TEXT_SUBTLE = "64748B"; // Cool gray for sub-headers and metadata
    const COLOR_PURPLE = "7E22CE";      // Purple accent for IMU & kinematics
    const COLOR_INDIGO = "4338CA";      // Indigo accent for MCAP & visualizer
    const COLOR_ROSE = "BE123C";        // Rose/crimson for bugs & safety

    // Helper: Standard Slide Header (Consistent across slides 2-11)
    function addSlideHeader(slide, category, title, subtitle, slideNum) {
        slide.background = { color: COLOR_BG };

        // Category Tag
        slide.addText(category.toUpperCase(), {
            x: 0.80, y: 0.40, w: 8.50, h: 0.25,
            fontSize: 10, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });

        // Main Title
        slide.addText(title, {
            x: 0.80, y: 0.68, w: 9.50, h: 0.45,
            fontSize: 20, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });

        // Subtitle
        slide.addText(subtitle, {
            x: 0.80, y: 1.15, w: 9.50, h: 0.30,
            fontSize: 11.5, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });

        // Top-Right Project Pill Badge
        slide.addShape(pres.ShapeType.roundRect, {
            x: 10.73, y: 0.45, w: 1.80, h: 0.36,
            fill: { color: "EFF6FF" },
            line: { color: "BFDBFE", width: 1 },
            rectRadius: 0.05
        });
        slide.addText("ROADSENSE • WEEK 2", {
            x: 10.73, y: 0.45, w: 1.80, h: 0.36,
            fontSize: 8.5, bold: true, color: COLOR_PRIMARY, align: "center", fontFace: "Segoe UI",
            valign: "middle", margin: 0
        });

        // Header Divider Rule
        slide.addShape(pres.ShapeType.line, {
            x: 0.80, y: 1.52, w: 11.733, h: 0,
            line: { color: "E2E8F0", width: 1.2 }
        });

        // Slide Number Footer
        if (slideNum) {
            slide.addText(`Slide ${slideNum} of 11`, {
                x: 10.80, y: 7.10, w: 1.733, h: 0.25,
                fontSize: 8.5, color: COLOR_TEXT_SUBTLE, align: "right", fontFace: "Segoe UI",
                valign: "middle", margin: 0
            });
            slide.addText("CONFIDENTIAL • BAJAJ AUTO LTD. • ADVANCED PERCEPTION & TELEMETRY", {
                x: 0.80, y: 7.10, w: 8.50, h: 0.25,
                fontSize: 8, color: "94A3B8", fontFace: "Segoe UI",
                valign: "middle", margin: 0
            });
        }
    }

    // Helper: Add Native Vector Card
    function addCard(slide, x, y, w, h, borderColor = COLOR_CARD_BORDER, fillColor = COLOR_CARD_BG) {
        slide.addShape(pres.ShapeType.roundRect, {
            x, y, w, h,
            fill: { color: fillColor },
            line: { color: borderColor, width: 1.2 },
            rectRadius: 0.06
        });
    }

    // Helper: Add Designated Image / Screenshot Placeholder Slot
    function addPlaceholder(slide, x, y, w, h, title, desc, tag = "Screenshot Slot") {
        slide.addShape(pres.ShapeType.roundRect, {
            x, y, w, h,
            fill: { color: "F1F5F9" },
            line: { color: COLOR_PRIMARY, width: 1.5, dashType: "dash" },
            rectRadius: 0.06
        });

        slide.addText([
            { text: `[ ${tag.toUpperCase()} ]\n\n`, options: { fontSize: 10, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: title + "\n\n", options: { fontSize: 12.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: desc, options: { fontSize: 9.5, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } }
        ], {
            x: x + 0.20, y: y + 0.30, w: w - 0.40, h: h - 0.60,
            align: "center", valign: "middle", margin: 0
        });
    }

    // =========================================================================
    // SLIDE 1: Title Slide (Executive Sprint Briefing)
    // =========================================================================
    {
        const slide = pres.addSlide();
        slide.background = { color: COLOR_BG };

        // Left Accent Brand Bar
        slide.addShape(pres.ShapeType.rect, {
            x: 0.80, y: 1.40, w: 0.12, h: 4.60,
            fill: { color: COLOR_PRIMARY },
            line: { color: COLOR_PRIMARY }
        });

        // Sprint Badge
        slide.addShape(pres.ShapeType.roundRect, {
            x: 1.15, y: 1.40, w: 2.80, h: 0.36,
            fill: { color: "EFF6FF" },
            line: { color: "BFDBFE", width: 1 },
            rectRadius: 0.05
        });
        slide.addText("SPRINT 2 TECHNICAL HANDOVER", {
            x: 1.15, y: 1.40, w: 2.80, h: 0.36,
            fontSize: 9, bold: true, color: COLOR_PRIMARY, align: "center", fontFace: "Segoe UI",
            valign: "middle", margin: 0
        });

        // Main Title & Subtitle
        slide.addText([
            { text: "RoadSense: Week 2 Progress\n", options: { fontSize: 32, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Spatial Fusion, 100 Hz Kinematics & High-Throughput MCAP Perception Cockpit", options: { fontSize: 16, color: COLOR_SECONDARY, fontFace: "Segoe UI" } }
        ], {
            x: 1.15, y: 1.95, w: 10.80, h: 1.30,
            valign: "top", margin: 0
        });

        // Divider
        slide.addShape(pres.ShapeType.line, {
            x: 1.15, y: 3.40, w: 11.383, h: 0,
            line: { color: "E2E8F0", width: 1.2 }
        });

        // 3 Key Milestone Cards
        const heroCards = [
            {
                tag: "PERCEPTION & CALIBRATION",
                title: "6-DOF Spatial Fusion & RViz HUD",
                desc: "Closed-form O(1) Reverse Touch Solver, pinhole intrinsics K, hybrid radar lollipops with 12-segment road footprints & perspective range rings.",
                color: COLOR_PRIMARY,
                bg: "F0F9FF"
            },
            {
                tag: "VEHICLE DYNAMICS",
                title: "100 Hz LSM6DSL Kinematics",
                desc: "Sub-2ms jitter tracking, gravity-removed linear acceleration, magnetic-immune 6-DOF game rotation vectors, and dedicated thread acquisition.",
                color: COLOR_PURPLE,
                bg: "FAF5FF"
            },
            {
                tag: "TOOLCHAIN & PERFORMANCE",
                title: "MCAP Pipeline & Web Dashboard",
                desc: "Zero-cost H.264 bitstream demuxing at >11,500 FPS (~3.4s) with zero GPU load, local SSE web server at :8088, and root cause post-mortems for Bugs #11-#15.",
                color: COLOR_SUCCESS,
                bg: "F0FDF4"
            }
        ];

        heroCards.forEach((c, idx) => {
            const cardX = 1.15 + idx * 3.88;
            addCard(slide, cardX, 3.65, 3.62, 2.35, c.color, c.bg);

            slide.addText([
                { text: c.tag + "\n", options: { fontSize: 8.5, bold: true, color: c.color, fontFace: "Segoe UI" } },
                { text: c.title + "\n\n", options: { fontSize: 13, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: c.desc, options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
            ], {
                x: cardX + 0.20, y: 3.80, w: 3.22, h: 2.05,
                valign: "top", margin: 0, wrap: true
            });
        });

        // Bottom Metadata Footer
        slide.addText("Date: September 29, 2026   |   Target: Android 14 (API 34)   |   Test Invariants: 100% JVM Tests Passing (24/24)   |   Author: RoadSense Engineering", {
            x: 1.15, y: 6.40, w: 11.383, h: 0.35,
            fontSize: 9.5, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI",
            valign: "middle", margin: 0
        });
    }

    // =========================================================================
    // SLIDE 2: Sprint Executive Summary (From Logger to Perception Platform)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Executive Overview", "Week 2 Sprint Deliverables: From Logger to Perception Platform", "Consolidating on-device calibration, high-rate kinematics, and desktop analytics", 2);

        // 3 Vertical Milestone Column Cards
        const colW = 3.65;
        const colY = 1.75;
        const colH = 4.30;

        // Column 1: On-Device Spatial Fusion
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "1. On-Device Spatial Fusion\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• 6-DOF Rigid Transform ([R|T]):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Lateral X, Setback Y, Height Z, Pitch θ, Yaw ψ, Roll φ. Persistent JSON profiles.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Reverse Touch Solver (O(1)):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Instant calibration snap from single viewfinder tap without physical laser rigs.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• RViz-Inspired Radar Lollipops:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  12-segment road footprints, vertical projection stems, and Painter's depth sorting.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Perspective Range Rings:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Concentric ground arcs at 10m, 30m, 60m, 120m clamped to camera horizontal FOV.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Column 2: 100 Hz Vehicle Kinematics
        addCard(slide, 4.84, colY, colW, colH, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "2. 100 Hz Vehicle Kinematics\n\n", options: { fontSize: 13, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• ST LSM6DSL 100 Hz ASIC:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  High-rate accelerometer and gyroscope acquisition via dedicated listener thread.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Gravity-Subtracted Linear Accel:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  On-device sensor fusion removing Earth gravity (9.81 m/s²) for true dynamic braking/accel.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 6-DOF Game Rotation Vector:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Magnetic-immune orientation quaternion (x,y,z,w) safe from EV inverter EMF.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Real-Time Jitter Profiler:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Nanosecond monotonic tracking guaranteeing <2ms jitter across continuous drive logs.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 5.04, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Column 3: High-Throughput Desktop Toolchain
        addCard(slide, 8.88, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "3. PC Operations & MCAP Toolchain\n\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Zero-Cost Video Demuxing (>11,500 FPS):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Demuxes H.264 bitstream directly into MCAP in ~3.4s with 0% GPU load via /tf roll fold.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Zero-Dependency Web Dashboard:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Local Python HTTP server on port 8088 with Server-Sent Events (SSE) live progress.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Hardware NVENC Transcoder (~650 FPS):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Enforces closed GOP 30 & IDR keyframes eliminating video seek freeze bugs.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Foxglove Studio Integration:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Auto-embeds RoadSense_Cockpit_Layout.json directly inside every single .mcap container.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 9.08, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Bottom Banner: Bugs #11 to #15 Post-Mortems
        addCard(slide, 0.80, 6.20, 11.733, 0.70, "CBD5E1", "EFF6FF");
        slide.addText([
            { text: "CRITICAL SYSTEM HARDENING COMPLETED: ", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Root cause post-mortems conducted and permanently resolved for Bug #11 (28B Stride Aliasing), Bug #12 (Exynos AUX Camera ISP Limit), Bug #13 (Foxglove Ghost Tracks via Static Entity ID), Bug #14 (1-sec GOP 30 Seek Freeze), and Bug #15 (Zero-Cost 180° /tf Camera Frame Roll).", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: 6.25, w: 11.333, h: 0.60,
            valign: "middle", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 3: 6-DOF Spatial Calibration & Reverse Touch Solver
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Spatial Fusion", "60-Second In-Situ Extrinsic Calibration Engine", "Solving mounting pitch and yaw in closed form (O(1)) without calibration rigs", 3);

        const colW = 5.65;
        const colY = 1.75;
        const colH = 5.15;

        // Left Column: Closed-Form Reverse Touch Solver & Math
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Closed-Form Mathematical Reverse Touch Solver\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "1. 6-DOF Rigid Body Transformation [ R | T ]:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Extrinsics specify 3D baseline: Lateral offset ΔX, Setback ΔY, Height ΔZ.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Rotation matrix parameterized by Pitch (θ), Yaw (ψ), and Roll (φ).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Camera Intrinsics Matrix (K):\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Automatically queried from CameraCharacteristics (LENS_INTRINSIC_CALIBRATION).\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Focal lengths (fx, fy) and optical principal center (cx, cy) mapped to preview.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Closed-Form Angle Recovery (O(1)):\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Given a known radar target at forward range R and tap pixel (u, v):\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "     u_norm = (u - cx) / fx    |    v_norm = (v - cy) / fy\n", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "     pitch_solved = -atan(v_norm)    |    yaw_solved = atan(u_norm)\n", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "   • Solves pitch and yaw instantaneously without iterative gradient descent or non-linear optimization loops.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Single-Tap Snap Workflow:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • In-situ technician taps vehicle center in viewfinder; system locks calibration in <60s.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Interactive Calibration Studio & UX Controls
        addCard(slide, 6.88, colY, colW, colH, COLOR_SECONDARY, "F8FAFC");
        slide.addText([
            { text: "Interactive Cockpit Calibration Studio\n\n", options: { fontSize: 13, bold: true, color: COLOR_SECONDARY, fontFace: "Segoe UI" } },
            { text: "1. Real-Time Fullscreen Reticle HUD:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Viewfinder displays high-contrast target reticle overlay with live coordinate readouts.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Displays live target range (m), azimuth (°), and pixel reprojection error.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Dual-Mode Manual Fine-Tuning:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Trackpad Delta Drag: Smooth finger dragging across virtual trackpad for fine analog adjustment.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Precision Nudge Bar: Discrete step buttons (±0.1° fine, ±1.0° coarse) for pitch/yaw.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Persistent JSON Calibration Profile:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Automatically saved to /sdcard/.../calibration/radar_camera_calib.json.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Reloaded across app restarts and embedded into downstream session archives.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Accidental Gesture Interlock:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Cockpit HorizontalPager userScrollEnabled = false during active calibration to prevent inadvertent tab paging while dragging reticles.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 7.08, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 4: Real-Time Viewfinder Radar Overlays (Hybrid Lollipops & Range Rings)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Perception Visualization", "RViz-Inspired Hybrid Radar Lollipops & Perspective Range Rings", "Overcoming 3D-to-2D depth ambiguity with ground footprints and Painter's sorting", 4);

        const colW = 5.65;
        const colY = 1.75;
        const colH = 5.15;

        // Left Column: Visual Architecture & Depth Cues
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Depth Ambiguity & Visual Architecture\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "1. The 3D-to-2D Perspective Challenge:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Standard radar point reprojection renders floating 2D dots, making it impossible for human drivers or annotators to discern vehicle ground contact.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. RViz-Inspired Hybrid Lollipop Structure:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Road Footprint: 12-segment perspective ellipse projected onto the road plane (Z = 0) showing vehicle contact patch.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Vertical Stem: Translucent line rising from ground plane to physical detection height.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Centroid Sphere: Color-coded detection head showing Doppler velocity (m/s).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Painter's Algorithm Depth-Sorting:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Invariant: lollipops.sortByDescending { it.depthM }.\n", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "   • Ensures distant targets (100m) render first, and close targets (10m) render cleanly on top without foreground occlusion artifacts.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Staggered Perspective Range Rings:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Concentric ground distance arcs at 10m, 30m, 60m, 120m clamped to camera HFOV with staggered distance labels preventing visual clutter.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Visualizer Placeholder / Architecture Demonstration Slot
        addPlaceholder(
            slide,
            6.88, colY, colW, colH,
            "ViewfinderRadarOverlay in Cockpit Studio",
            "Real-time 1080p preview demonstrating perspective radar projections:\n• 12-segment road contact ellipses (Ground Z=0)\n• Vertical perspective stems connecting ground to target\n• Doppler velocity color mapping (Approach Red / Recede Green)\n• Staggered distance range rings (10m, 30m, 60m, 120m)\n• Zero dropped frames at 20 Hz radar + 30 FPS Camera2",
            "Cockpit Viewfinder HUD Slot"
        );
    }

    // =========================================================================
    // SLIDE 5: High-Rate 100 Hz IMU Subsystem & Kinematics
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Vehicle Dynamics", "100 Hz LSM6DSL IMU Integration & Real-Time Jitter Profiler", "Capturing vehicle chassis dynamics, pitch/roll, and magnetic-immune orientation", 5);

        const colW = 3.65;
        const colY = 1.75;
        const colH = 5.15;

        // Card 1: Hardware ASIC & Dedicated Threading
        addCard(slide, 0.80, colY, colW, colH, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "ASIC Hardware & Acquisition\n\n", options: { fontSize: 13, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• ST LSM6DSL Sensor Hub:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Integrated 6-axis inertial measurement unit (3-axis accelerometer + 3-axis gyroscope) with ultra-low noise density.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• SENSOR_DELAY_FASTEST Rate:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Operating at 100 Hz nominal acquisition frequency (~10.0 ms sampling interval).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Dedicated Listener Thread:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Isolated SensorEventListener running on a dedicated HandlerThread to prevent UI thread contention.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Monotonic Hardware Clock:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Timestamped with SystemClock.elapsedRealtimeNanos() matching Camera2 HAL and TI mmWave radar streams.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Card 2: 3 Kinematic Data Streams
        addCard(slide, 4.84, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "3 Core Kinematic Streams\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "1. Calibrated Accel & Gyro:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Raw 3-axis acceleration (m/s²) capturing chassis vibration and road surface roughness.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • 3-axis angular rotation rate (rad/s) capturing yaw rate and roll dynamics.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Linear Acceleration:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Earth gravity vector (9.81 m/s²) mathematically removed via onboard Kalman fusion.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Isolates true longitudinal braking/acceleration and lateral cornering forces.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Game Rotation Vector:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • 6-DOF orientation quaternion (x, y, z, w).\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Completely immune to vehicle electromagnetic interference (avoids noisy magnetometers).", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 5.04, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Card 3: Real-Time Diagnostics & Jitter Profiler
        addCard(slide, 8.88, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "Live HUD & Jitter Profiler\n\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Live Metrics Bar in Cockpit:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Displays instantaneous sampling rate (Hz), sample count, and orientation status in real time.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Continuous Jitter Profiler:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Monitors inter-frame arrival delta (Δt). Automatically flags and logs any jitter anomaly exceeding >2.0 ms.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Session CSV Logging:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  All 9 DOF recorded to imu_frames.csv with nanosecond monotonic timestamps.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Downstream MCAP Ingestion:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Directly mapped to foxglove.Imu and geometry_msgs.Transform in PC pipeline.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 9.08, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 6: PC Operations & Interactive Web Dashboard
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Desktop Toolchain", "Zero-Dependency Local Web Dashboard & ADB Automation", "Complete test-session management, live telemetry gauges, and 1-click execution", 6);

        const colW = 5.65;
        const colY = 1.75;
        const colH = 5.15;

        // Left Column: Server Architecture (tools/roadsense_web_server.py)
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Zero-Dependency Local Web Server\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "1. Zero External Python Packages:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Built exclusively on Python 3 standard library (http.server.ThreadingHTTPServer).\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Runs instantly out-of-the-box on Windows/Linux without pip dependency conflicts.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Port 8088 Service Architecture:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Accessible at http://localhost:8088 across local browser sessions.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Robust REST API endpoints for sessions, devices, extraction, and playback.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Server-Sent Events (SSE) Live Streaming:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Endpoint: /api/stream delivers real-time execution progress directly to browser.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Eliminates aggressive client polling loops; streams byte-level transfer progress.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Autonomous ADB Device Health Poller:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Continuous background health checks tracking device connection, battery %, battery temperature, and available phone storage space.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Web Cockpit Interface (tools/web_dashboard/index.html)
        addCard(slide, 6.88, colY, colW, colH, COLOR_SECONDARY, "F8FAFC");
        slide.addText([
            { text: "Automotive Web Cockpit Features\n\n", options: { fontSize: 13, bold: true, color: COLOR_SECONDARY, fontFace: "Segoe UI" } },
            { text: "1. Dark-Mode Automotive Cockpit:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • High-contrast slate/cyan UI designed for test-track visibility and quick operation.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Dynamic gauges for Battery (%), Thermal State (°C), and Storage (GB free).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Session Ingestion & Health Inspector:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Automatically enumerates all on-device drive sessions with sensor inventories.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • 1-Click extraction pulling Radar, Camera, IMU, GNSS, and CANedge2 logs.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Automated MCAP Conversion Pipeline:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Triggers session_to_mcap.py directly with user-selectable demuxing modes.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Real-time progress bar tracking video demux, point cloud serialization, and /tf.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Instant 1-Click Foxglove Launcher:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Generates direct desktop foxglove://open?ds=file URIs for instant visualization.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 7.08, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 7: Foxglove MCAP Archiving & Zero-Cost 3D Orientation
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Data Architecture", "High-Throughput Foxglove MCAP Pipeline (>11,500 FPS)", "Mathematical coordinate alignment eliminating GPU transcoding overhead", 7);

        // Top Summary Card: Breakthrough & Mathematical /tf Roll Alignment
        addCard(slide, 0.80, 1.75, 11.733, 2.05, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "The Orientation Challenge & The Zero-Cost Mathematical Breakthrough\n\n", options: { fontSize: 12.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• The Hardware Orientation Dilemma: ", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Android smartphones mounted in landscape record H.264 video with an internal 180° rotation matrix. Previously, rendering this upright in Foxglove 3D space required full video transcoding (decoding and re-encoding every frame).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Zero-Cost Bitstream Demuxing (>11,500 FPS): ", options: { fontSize: 10, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Instead of modifying pixel buffers, session_to_mcap.py inspects the MP4 tkhd matrix in O(1) time and automatically folds 180° into the camera_optical /tf frame roll:\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "  tf_roll = 180.0°   |   tf_pitch = -mounting_pitch   |   tf_yaw = -mounting_yaw\n", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "• Embedded Cockpit Layout: ", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Every .mcap file automatically embeds RoadSense_Cockpit_Layout.json as a container attachment for zero-setup 3D perception visualization.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: 1.90, w: 11.333, h: 1.75,
            valign: "top", margin: 0, wrap: true
        });

        // Bottom Table: Quantitative Performance Benchmark Matrix
        const benchmarkRows = [
            [
                { text: "Conversion Mode", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Processing Speed", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "1-Min Video Duration", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "GPU / Hardware Load", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Image Bitstream Quality", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Recommended Use Case", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } }
            ],
            [
                { text: "Zero-Cost Demux (Default)", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } },
                { text: ">11,500 FPS", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } },
                { text: "~3.4 seconds", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } },
                { text: "0% GPU (Minimal CPU)", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "100% Bit-Lossless Copy", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "Default high-throughput archiving pipeline", options: { fontSize: 9, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "NVENC Transcoding (--flip-video)", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY } },
                { text: "~650 FPS", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY } },
                { text: "~32 seconds", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY } },
                { text: "Dedicated NVENC SIP block", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "High-Bitrate (GOP 30 / IDR)", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "3rd-party viewers lacking /tf roll support", options: { fontSize: 9, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "CPU Software Transcoding", options: { fontSize: 9, color: COLOR_TEXT_SUBTLE } },
                { text: "~85 FPS", options: { fontSize: 9, color: COLOR_TEXT_SUBTLE } },
                { text: "~240 seconds", options: { fontSize: 9, color: COLOR_TEXT_SUBTLE } },
                { text: "100% Multi-Core CPU Load", options: { fontSize: 9, color: COLOR_TEXT_SUBTLE } },
                { text: "Standard libx264 profile", options: { fontSize: 9, color: COLOR_TEXT_SUBTLE } },
                { text: "Fallback for non-NVIDIA test machines", options: { fontSize: 9, color: COLOR_TEXT_SUBTLE } }
            ]
        ];

        slide.addTable(benchmarkRows, {
            x: 0.80, y: 4.05, w: 11.733,
            colW: [2.50, 1.60, 1.80, 2.00, 1.933, 1.90],
            border: { type: "solid", pt: 1, color: "CBD5E1" },
            fill: "F8FAFC",
            valign: "middle"
        });

        // Bottom Callout Note
        addCard(slide, 0.80, 6.05, 11.733, 0.75, "BFDBFE", "EFF6FF");
        slide.addText([
            { text: "ARCHITECTURAL RESULT: ", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Zero-cost demuxing delivers a 9.4x speedup over hardware NVENC and a 70x speedup over CPU transcoding, allowing field engineers to convert 1-hour multi-sensor drive runs in under 3.5 minutes on standard laptops.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: 6.10, w: 11.333, h: 0.65,
            valign: "middle", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 8: Critical Engineering Resolutions & Bug Post-Mortems
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "System Hardening", "Root Cause Analysis: Bugs #11 Through #15 Resolved", "Overcoming hardware quirks, codec bottlenecks, and scene-graph memory leaks", 8);

        const cardW = 5.65;
        const cardH = 2.45;
        const col1X = 0.80;
        const col2X = 6.88;
        const row1Y = 1.75;
        const row2Y = 4.45;

        // Card 1: Bug #11 (28-Byte Stride Aliasing)
        addCard(slide, col1X, row1Y, cardW, cardH, COLOR_ROSE, "F8FAFC");
        slide.addText([
            { text: "Bug #11: 28-Byte Stride Aliasing in Radar Parser\n\n", options: { fontSize: 11.5, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "• Symptom: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Intermittent parser desynchronization and corrupted point coordinates during dense mmWave clusters.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Root Cause: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "TI mmWave AWR1843BOOST TLV point struct assumed 32-byte alignment, but firmware outputted packed 28-byte structures for side-info descriptors.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Permanent Fix: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Implemented dynamic TLV length validation and strict 28/32-byte struct stride offsets; verified zero desync across >100,000 continuous frames.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: col1X + 0.20, y: row1Y + 0.15, w: cardW - 0.40, h: cardH - 0.30,
            valign: "top", margin: 0, wrap: true
        });

        // Card 2: Bug #12 (Exynos Dual-Stream HAL Stalling)
        addCard(slide, col2X, row1Y, cardW, cardH, COLOR_ROSE, "F8FAFC");
        slide.addText([
            { text: "Bug #12: Exynos Dual-Stream HAL Stalling on AUX Camera\n\n", options: { fontSize: 11.5, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "• Symptom: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Camera2 HAL pipeline deadlock and ANR crashes when activating dual analysis streams on Samsung Exynos chipsets.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Root Cause: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Exynos hardware ISP enforces strict concurrent surface output limits, starving ImageReader buffers.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Permanent Fix: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Road AE samples downsampled 32x24 bitmaps directly from active TextureView on Dispatchers.Default; zero extra HAL streams allocated.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: col2X + 0.20, y: row1Y + 0.15, w: cardW - 0.40, h: cardH - 0.30,
            valign: "top", margin: 0, wrap: true
        });

        // Card 3: Bug #13 (Foxglove Scene-Graph Ghost Tracks)
        addCard(slide, col1X, row2Y, cardW, cardH, COLOR_ROSE, "F8FAFC");
        slide.addText([
            { text: "Bug #13: Foxglove Scene-Graph Ghost Tracks\n\n", options: { fontSize: 11.5, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "• Symptom: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Past radar target bounding boxes and tracks persisted indefinitely on the Foxglove 3D canvas, creating clutter.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Root Cause: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Dynamic entity IDs generated per frame prevented Foxglove's scene graph from identifying track updates as replacements.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Permanent Fix: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Standardized on static entity ID 'radar_tracks' in foxglove.SceneUpdate, atomically replacing target states per frame.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: col1X + 0.20, y: row2Y + 0.15, w: cardW - 0.40, h: cardH - 0.30,
            valign: "top", margin: 0, wrap: true
        });

        // Card 4: Bugs #14 & #15 (Seeking Freeze & Optical Frame Disorientation)
        addCard(slide, col2X, row2Y, cardW, cardH, COLOR_ROSE, "F8FAFC");
        slide.addText([
            { text: "Bugs #14 & #15: Video Seek Freeze & Optical /tf Roll\n\n", options: { fontSize: 11.5, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "• Symptom (Bug #14): ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Seeking the Foxglove timeline on long recordings caused video playback to freeze indefinitely.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Permanent Fix #14: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Enforced strict 1-sec closed GOPs (-g 30, -forced-idr 1) and in-band SPS/PPS headers; seeking is instantaneous.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Permanent Fix #15: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Auto-detected MP4 180° rotation folded into camera_optical /tf roll; video and 3D radar clouds render 100% upright.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: col2X + 0.20, y: row2Y + 0.15, w: cardW - 0.40, h: cardH - 0.30,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 9: System Performance & Verification Benchmark Matrix
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Quality Engineering", "Quantitative Benchmarks & Automated Test Coverage", "Subsystem throughput, latency, memory consumption, and unit test invariants", 9);

        // Verification Matrix Table
        const matrixRows = [
            [
                { text: "Subsystem / Pipeline", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Target Freq", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Bandwidth / Data Rate", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Latency / Jitter Limit", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Memory / CPU Profile", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Verification Status", options: { fontSize: 9.5, bold: true, color: "FFFFFF", fill: "1E293B" } }
            ],
            [
                { text: "TI mmWave Radar (UART)", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY } },
                { text: "20.0 Hz", options: { fontSize: 9, color: COLOR_TEXT_MAIN } },
                { text: "3,125,000 baud (3.125 Mbps)", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "< 1.2 ms buffer latency", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "64KB ring buffer / <4% CPU", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "100% Pass (0 Drops)", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } }
            ],
            [
                { text: "Camera2 Video Engine", options: { fontSize: 9, bold: true, color: COLOR_SECONDARY } },
                { text: "30.0 FPS", options: { fontSize: 9, color: COLOR_TEXT_MAIN } },
                { text: "1080p H.264 (~20 Mbps)", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "< 15 ms frame display", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "Hardware MediaCodec / <8% CPU", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "100% Pass (Road AE)", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } }
            ],
            [
                { text: "LSM6DSL Kinematics (IMU)", options: { fontSize: 9, bold: true, color: COLOR_PURPLE } },
                { text: "100.0 Hz", options: { fontSize: 9, color: COLOR_TEXT_MAIN } },
                { text: "3 streams (9 DOF + Quat)", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "< 1.8 ms jitter (σ = 0.4ms)", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "Dedicated Thread / <1% CPU", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "100% Pass (Sub-2ms)", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } }
            ],
            [
                { text: "CANedge2 Ingestion Engine", options: { fontSize: 9, bold: true, color: COLOR_WARNING } },
                { text: "1-min split", options: { fontSize: 9, color: COLOR_TEXT_MAIN } },
                { text: "Dual CAN/CAN-FD (500k/2M)", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "1.5s backoff cooldown", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "2-folder FIFO pool staging", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "100% Pass (Bug #10 fixed)", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } }
            ],
            [
                { text: "MCAP Container Toolchain", options: { fontSize: 9, bold: true, color: COLOR_INDIGO } },
                { text: ">11.5k FPS", options: { fontSize: 9, color: COLOR_TEXT_MAIN } },
                { text: "Lossless Protobuf Bitstream", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "~3.4s conversion per min", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "0% GPU / Low CPU Demux", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "100% Pass (Foxglove)", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } }
            ],
            [
                { text: "Automated JVM Unit Tests", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } },
                { text: "CI Pipeline", options: { fontSize: 9, color: COLOR_TEXT_MAIN } },
                { text: "24 Executed Unit Tests", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "0 Failures / 0 Regressions", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "Gradle JBR 17 Environment", options: { fontSize: 9, color: COLOR_TEXT_MUTED } },
                { text: "24/24 Tests Passing", options: { fontSize: 9, bold: true, color: COLOR_SUCCESS } }
            ]
        ];

        slide.addTable(matrixRows, {
            x: 0.80, y: 1.75, w: 11.733,
            colW: [2.50, 1.40, 2.30, 2.00, 2.033, 1.50],
            border: { type: "solid", pt: 1, color: "CBD5E1" },
            fill: "F8FAFC",
            valign: "middle"
        });

        // 3 Supporting Metric Highlight Badges
        const badges = [
            { title: "ZERO DROPPED FRAMES", value: "20.0 Hz Radar / 30 FPS Video", desc: "Rigorous ring buffering and MediaCodec HAL isolation", color: COLOR_PRIMARY },
            { title: "SUB-2MS IMU JITTER", value: "100 Hz Continuous", desc: "Monotonic hardware timestamps synchronized across sensors", color: COLOR_PURPLE },
            { title: "CLEAN GRADLE BUILD", value: "24/24 JVM Tests Clean", desc: "Passing testDebugUnitTest across parser and projection modules", color: COLOR_SUCCESS }
        ];

        badges.forEach((b, idx) => {
            const bX = 0.80 + idx * 4.04;
            addCard(slide, bX, 5.50, 3.65, 1.30, b.color, "FFFFFF");
            slide.addText([
                { text: b.title + "\n", options: { fontSize: 9, bold: true, color: b.color, fontFace: "Segoe UI" } },
                { text: b.value + "\n", options: { fontSize: 13, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.desc, options: { fontSize: 8.5, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } }
            ], {
                x: bX + 0.15, y: 5.60, w: 3.35, h: 1.10,
                valign: "middle", margin: 0, wrap: true
            });
        });
    }

    // =========================================================================
    // SLIDE 10: ADAS Roadmap: Week 3 & Beyond
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Strategic Direction", "The Road Ahead: On-Device Edge Perception & Active Safety Alerts", "Transitioning from multimodal dataset logging to real-time collision warning", 10);

        const colW = 3.65;
        const colY = 1.75;
        const colH = 5.15;

        // Pillar 1: On-Device Edge Object Detection
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Pillar 1: Edge Object Detection\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Lightweight TFLite / YOLO-Nano:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Deploy quantized mobile neural network directly onto smartphone NPU / GPU delegate.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 30 FPS Real-Time Inference:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Zero latency penalty on Camera2 recording pipeline; asynchronous frame sampling.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 2D Bounding Box Extraction:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Detect vehicles, motorcycles, auto-rickshaws, and pedestrians in camera pixel space.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Ground Plane Snapping:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Bottom edge of 2D bounding boxes projected onto 3D road plane for spatial fusion.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Pillar 2: Multimodal Radar-Camera EKF Tracker
        addCard(slide, 4.84, colY, colW, colH, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "Pillar 2: Multimodal EKF Fusion\n\n", options: { fontSize: 13, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• Extended Kalman Filter (EKF):\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Fuse radar range and Doppler range-rate with camera 2D azimuth and elevation angles.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Sensor Complementarity:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Radar excels at depth & speed (immune to rain/glare); Vision excels at lateral position & classification.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Track Persistence & Gating:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Mahalanobis distance gating to prevent ghost association and maintain persistent track IDs.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Ego-Motion Compensation:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  LSM6DSL yaw rate and IMU velocity dynamically subtracted from radar Doppler vectors.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 5.04, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Pillar 3: Active Safety Warning Engine (FCW / BSD)
        addCard(slide, 8.88, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "Pillar 3: Active Safety Warnings\n\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Forward Collision Warning (FCW):\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Compute Time-to-Collision (TTC = Range / -RangeRate). Trigger audio-visual alerts when TTC < 2.5s.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Blind Spot Detection (BSD):\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Radar boundary monitoring in adjacent lane zones (lateral 1.5m to 4.5m, setback -5m to +2m).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Cockpit HUD Warning Overlays:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Red visual warning halos and audible vehicle speaker chimes on high-priority collision vectors.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Event Tagging & Highlight Export:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Automatically tag near-miss events in MCAP and session_timeline.csv for fleet safety analysis.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 9.08, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 11: Executive Conclusion & Next Steps
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Project Handover", "Consolidated Milestones & Immediate Action Items", "Summary of Week 2 deliverables and execution plan for Week 3", 11);

        const colW = 5.65;
        const colY = 1.75;
        const colH = 5.15;

        // Left Column: Key Accomplishments Summary
        addCard(slide, 0.80, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "Sprint 2 Key Accomplishments\n\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "1. 6-DOF Spatial Calibration Complete:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Full [R|T] extrinsics and Camera2 K matrix operational.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Reverse Touch Solver snaps pitch/yaw in <60 seconds.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. RViz-Inspired Radar Overlays:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • 12-segment road contact footprints and vertical stems solve depth ambiguity.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Staggered distance range rings render seamlessly in live viewfinder.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. 100 Hz LSM6DSL Kinematics:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Gravity-free linear acceleration and magnetic-immune 6-DOF orientation.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Sub-2ms inter-frame jitter verified via monotonic hardware clock.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. High-Throughput Desktop Toolchain:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Zero-dependency web dashboard (:8088) with real-time SSE progress.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Zero-cost MCAP video demuxing at >11,500 FPS (~3.4s, 0% GPU load).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "5. Production Engineering Hardening:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Bugs #11 to #15 diagnosed and permanently eliminated; 24/24 unit tests passing.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Immediate Action Items & Deployment Checklist
        addCard(slide, 6.88, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Immediate Action Items & Week 3 Sprint\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "1. Vehicle Test Track Verification:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Validate 6-DOF Reverse Touch Solver across 5 varied vehicle mounting heights and windshield rake angles on the Bajaj test track.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Extended Drive Cycle Thermal Validation:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Execute continuous 60-minute logging runs under high ambient heat (38°C+) to benchmark sustained 100 Hz IMU jitter and Camera2 thermals.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. TFLite Edge Perception Pipeline:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Integrate quantized YOLOv8-nano model on Android NNAPI/GPU delegate for 30 FPS vehicle and pedestrian bounding box detection.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Multimodal EKF Tracker Development:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Implement Kotlin Extended Kalman Filter fusing radar Doppler velocity with vision bounding box centroids.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "Questions & Technical Discussion", options: { fontSize: 12, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } }
        ], {
            x: 7.08, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });
    }

    // 3. Write Output PPTX
    const outputDir = path.join(__dirname, "decks");
    const outputPath = path.join(outputDir, "RoadSense_Week2_Executive_Progress.pptx");
    await pres.writeFile({ fileName: outputPath });
    console.log(`Successfully generated RoadSense Week 2 presentation: ${outputPath}`);
}

generateRoadSenseWeek2Presentation().catch((err) => {
    console.error("Error generating RoadSense Week 2 presentation:", err);
    process.exit(1);
});
