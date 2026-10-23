/**
 * RoadSense Week 3 Executive & Technical Progress Presentation Generator
 * 
 * Generates a 16-slide, 100% native vector PowerPoint presentation (.pptx)
 * adhering to strict professional standards:
 * - 16:9 Modern Widescreen (13.333" x 7.500", SCREEN16x9_FULL).
 * - High-contrast automotive engineering typography (Deep Slate, Cobalt Blue, Forest Green).
 * - Clean reserved layout areas for native PowerPoint equations (Alt + = / OMML).
 * - Standardized dashed image/video placeholder slots for screenshots and visualizers.
 * - Single flowing text run arrays with { valign: "top", margin: 0, wrap: true }.
 * - Zero visual clutter: No top-left category tags, no blue pill badges, no footers, no slide numbers.
 * - Compact 6-box grid (3 columns x 2 rows) on system hardening bug post-mortem slides.
 * - Valid OpenXML metadata: Zero unescaped ampersands in pres.company / pres.title to eliminate PowerPoint repair warnings.
 * 
 * Covers major engineering milestones from October 5 to October 22, 2026:
 * - Inverse Perspective Mapping (IPM) Closed-Form Derivations & ADAS Heritage / Pitfalls.
 * - Modular Geometric Vision Pipeline & Non-Destructive MCAP Topic Injection (/vision/geo_tracks).
 * - IMU-Assisted Camera Calibration, 1-Tap Gravity Sync, Digital Clamp Level & Live Horizon Engine.
 * - Master Sensor Decoupling: Camera Boresight vs. Radar Squint (Bug #25) & Coordinate Architecture.
 * - Radar-to-Vision Spatio-Temporal Track Association & Quantitative Range Benchmarking.
 * - Master Timeline Synchronizer (Bug #22 Hardware Sequence Locking & 2.16s Phase Lead Fix).
 * - 100 Hz Zero-Jitter Kinematics Engine (Bug #19 SPSC Lock-Free Ring Buffer & Native 104 Hz).
 * - Deep-Tech Diagnostics: On-Device Perfetto Kernel Tracing via ADB (Zero-Overhead IRQ/Sched Profiling).
 * - Data Delivery: Zero-Dependency Standalone Interactive HTML Audit Reports & Mouse-Locked HUD.
 * - Central Web Dashboard (:8088) with Real-Time SSE Multi-Script Runner.
 * - Root Cause Post-Mortems for Bugs #11 Through #25 (Compact 6-Card Format across 2 slides).
 * - Milestone Handover & System Perfection Roadmap (>66% Track Association Gate).
 */

import pptxgen from "pptxgenjs";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export async function generateRoadSenseWeek3Presentation() {
    const pres = new pptxgen();

    // 1. Define Modern 16:9 Widescreen (13.333" x 7.500")
    pres.defineLayout({ name: "SCREEN16x9_FULL", width: 13.333, height: 7.500 });
    pres.layout = "SCREEN16x9_FULL";
    pres.author = "RoadSense Core Engineering Team";
    // CRITICAL: Use "and" instead of "&" to prevent OpenXML XML parsing errors in docProps/app.xml
    pres.company = "Bajaj Auto Ltd. - Advanced Perception and Telemetry";
    pres.title = "RoadSense: Week 3 Progress - Inverse Perspective Mapping, Coordinate Decoupling and Multi-Sensor Hardening";

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
    const COLOR_INDIGO = "4338CA";      // Indigo accent for MCAP & dashboard
    const COLOR_ROSE = "BE123C";        // Rose/crimson for bugs & safety

    // Helper: Minimalist Slide Header (Zero Visual Clutter: No category tag, no pill badge, no footers, no slide numbers)
    function addSlideHeader(slide, categoryOrTitle, titleOrSubtitle, optionalSubtitle) {
        slide.background = { color: COLOR_BG };

        // Support either (slide, title, subtitle) or legacy (slide, category, title, subtitle, slideNum)
        let mainTitle = titleOrSubtitle;
        let subTitle = optionalSubtitle;
        if (!optionalSubtitle) {
            mainTitle = categoryOrTitle;
            subTitle = titleOrSubtitle;
        }

        // Clean Prominent Main Title (Y=0.55, h=0.45, 22pt)
        slide.addText(mainTitle, {
            x: 0.80, y: 0.55, w: 11.733, h: 0.45,
            fontSize: 22, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });

        // Informative Subtitle (Y=1.05, h=0.30, 11.5pt)
        slide.addText(subTitle, {
            x: 0.80, y: 1.05, w: 11.733, h: 0.30,
            fontSize: 11.5, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });

        // Header Divider Rule (Y=1.45)
        slide.addShape(pres.ShapeType.line, {
            x: 0.80, y: 1.45, w: 11.733, h: 0,
            line: { color: "E2E8F0", width: 1.2 }
        });
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

    // Helper: Add Designated Image / Video Placeholder Slot
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

    // Helper: Add Designated Native Equation Reserved Slot
    function addEquationSlot(slide, x, y, w, h, label = "Native Equation (Alt + =)") {
        slide.addShape(pres.ShapeType.roundRect, {
            x, y, w, h,
            fill: { color: "FFFFFF" },
            line: { color: "93C5FD", width: 1.2, dashType: "dash" },
            rectRadius: 0.04
        });
        slide.addText(`[ ${label.toUpperCase()} ]`, {
            x, y, w, h,
            fontSize: 8.5, color: "3B82F6", align: "center", valign: "middle", fontFace: "Consolas", margin: 0
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
        slide.addText("SPRINT 3 TECHNICAL HANDOVER", {
            x: 1.15, y: 1.40, w: 2.80, h: 0.36,
            fontSize: 9, bold: true, color: COLOR_PRIMARY, align: "center", fontFace: "Segoe UI",
            valign: "middle", margin: 0
        });

        // Main Title & Subtitle
        slide.addText([
            { text: "RoadSense: Week 3 Progress\n", options: { fontSize: 32, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Inverse Perspective Mapping, Coordinate Decoupling & Multi-Sensor Hardening", options: { fontSize: 16, color: COLOR_SECONDARY, fontFace: "Segoe UI" } }
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
                tag: "PERCEPTION & IPM",
                title: "Inverse Perspective Mapping & 3D Vision",
                desc: "Closed-form flat-earth raycasting (Z=0) running at 50+ FPS with YOLO26 + ByteTrack; non-destructive MCAP topic injection (/vision/geo_tracks) in Electric Magenta.",
                color: COLOR_PRIMARY,
                bg: "F0F9FF"
            },
            {
                tag: "SPATIAL ALIGNMENT & SYNC",
                title: "IMU Auto-Leveling & Master Sync",
                desc: "1-tap gravity pitch sync, digital clamp spirit level, camera/radar yaw decoupling (Bug #25), and Master Timeline Synchronizer eliminating 2.16s video phase lead.",
                color: COLOR_PURPLE,
                bg: "FAF5FF"
            },
            {
                tag: "DEEP-TECH TOOLCHAIN",
                title: "Perfetto Tracing & Interactive HTML",
                desc: "On-device Linux kernel scheduler tracing via ADB shell (<0.05ms app slice), standalone zero-dependency HTML audit reports with mouse-locked HUD, and 10,400 FPS conversion.",
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

        // Meta Footer
        slide.addText("Bajaj Auto Ltd. • Advanced Perception and Telemetry", {
            x: 1.15, y: 6.85, w: 8.00, h: 0.25,
            fontSize: 8.5, color: "94A3B8", fontFace: "Segoe UI"
        });
        slide.addText("October 2026 • Sprint 3 Handover", {
            x: 8.50, y: 6.85, w: 4.033, h: 0.25,
            fontSize: 8.5, color: "94A3B8", align: "right", fontFace: "Segoe UI"
        });
    }

    // =========================================================================
    // SLIDE 2: Sprint Executive Summary
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Sprint 3 Deliverables: From Optical IPM to Nanosecond Hardware Sync",
            "Consolidating closed-form vision geometry, spatial decoupling, kernel diagnostics, and bug resolutions"
        );

        const colW = 3.65;
        const colH = 4.35;
        const colY = 1.65;

        // Column 1: Geometric Vision & IPM
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "1. Inverse Perspective Mapping\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Closed-Form Flat-Earth Raycasting:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Inverts pinhole projection (Z_road=0) solving (X_v, Y_v) at 50+ FPS on standard CPU without neural depth models.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Dual 3D Bounding Geometry:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  nuScenes class priors vs. camera-derived optical spans; boxes anchored to ground surface (Z=H/2).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Non-Destructive MCAP Enrichment:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Injects /vision/geo_tracks in Electric Magenta with 0.0ms timestamp delta and byte-for-byte log preservation.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Radar-Vision Hungarian Association:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Automated bipartite matching (lapjv) with 3.5m gating, computing near/mid/far MAE and RMSE.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Column 2: Spatial Decoupling & Timing Infrastructure
        addCard(slide, 4.84, colY, colW, colH, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "2. Spatial Decoupling & Sync\n\n", options: { fontSize: 13, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• IMU-Assisted Auto-Leveling:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  1-tap gravity pitch extraction (GAME_ROTATION_VECTOR), digital clamp spirit level (±0.8°), and ADAS quality badge.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Camera / Radar Boresight Decoupling:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Separates Camera Heading (0.0°) from Bumper Radar Squint (+1.0°), eliminating 17px vision track lateral drift.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Master Timeline Synchronizer:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Hardware sequence mapping eliminates 2.16s video phase lead (Bug #22); adds /sync/frame_info.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 100 Hz Zero-Jitter Kinematics:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  SPSC lock-free ring buffer and SENSOR_DELAY_FASTEST bypass HAL filters, locking to native 104 Hz.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 5.04, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Column 3: Deep-Tech Toolchain & System Hardening
        addCard(slide, 8.88, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "3. Deep-Tech Toolchain & Hardening\n\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• On-Device Perfetto Kernel Tracing:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Zero-overhead scheduler & IRQ tracing via ADB shell; proves <0.05ms app slice execution.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Standalone Interactive HTML Reports:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Zero-dependency offline single-file report (~1.2MB) with mouse-locked HUD and 6-panel lockstep zooming.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 10,400 FPS MCAP Pipeline:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  80x throughput boost converting 40-minute driving sessions in 33 seconds via O(log N) binary search.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Central Web Dashboard (:8088):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  1-click runner orchestrating sync audit, MCAP build, 8-point health checks, and geometric vision.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 9.08, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Bottom Banner: Bugs #11 to #25 Resolved
        addCard(slide, 0.80, 6.20, 11.733, 0.70, "CBD5E1", "EFF6FF");
        slide.addText([
            { text: "CRITICAL SYSTEM HARDENING COMPLETED: ", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Root cause post-mortems conducted and permanently resolved for Bug #16 (MP4 moov Overflow), Bug #17 (TLV 6 FCW Decoding Offset), Bug #18 (Foxglove Schema Hash), Bug #19 (100 Hz IMU Jitter Spikes), Bug #21 (Pre-Roll Video Desync), Bug #22 (2.16s Video Phase Lead), Bug #23 (10,400 FPS Converter Boost), Bug #24 (Optical Pitch Sign Correction), and Bug #25 (Camera/Radar Yaw Decoupling).", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: 6.25, w: 11.333, h: 0.60,
            valign: "middle", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 3: Inverse Perspective Mapping (IPM) - Mathematical Formulation
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Inverse Perspective Mapping (IPM): Mathematical Derivation",
            "Closed-form flat-earth raycasting (Z_road = 0) recovering 3D metric coordinates from 2D pixel contact patches"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Step-by-Step Derivation & Formulations
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "1. Inverse Optical Ray Back-Projection:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Given 2D pixel coordinate ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "[u, v, 1]^T", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: " and intrinsic matrix ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "K", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: ", the normalized optical ray vector in camera frame {C} is:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   d_C = K^(-1) · [u, v, 1]^T = [(u - c_x)/f_x,  (v - c_y)/f_y,  1]^T\n\n", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Consolas" } },

            { text: "2. Total Depression Angle (β):\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Taking the bottom-most bounding box edge ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "(u_center, v_bottom)", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: " as the tire contact patch with road asphalt, the vertical optical ray angle combines camera mounting downward pitch (θ_mount) and vertical pixel deviation:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   β = θ_mount + arctan((v_bottom - c_y) / f_y)\n\n", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Consolas" } },

            { text: "3. Closed-Form Metric Ground Coordinates (Z_road = 0):\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Intersecting the line of sight with the flat road surface at camera height ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "H_cam", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: " yields metric forward depth (Y_v) and lateral offset (X_v):\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   Y_v = H_cam / tan(β)\n", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "   X_v = Y_v · ((u_center - c_x) / f_x) · cos(θ_mount) + ΔX\n\n", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },

            { text: "• Invariant Property: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "The solution executes in O(1) constant time (<0.02 ms/target) with zero iterative root-finding, eliminating need for neural depth inference.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 1.25,
            valign: "top", margin: 0, wrap: true
        });

        // Reserved Equation Slot on Left Card
        addEquationSlot(slide, 1.00, colY + colH - 1.00, colW - 0.40, 0.80, "Native Equation: Y_v = H / tan(β),  X_v = Y_v · (u - cx)/fx");

        // Right Column: Designated Diagram Placeholder
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "IPM Ground-Plane Raycasting Geometry",
            "Raycasting Schematic (Side & Top View):\n• Optical center at height H_cam (1.35m)\n• Downward pitch angle θ_mount (5.42°)\n• Line of sight ray passing through pixel v_bottom\n• Intersection with road asphalt plane (Z_road = 0)\n• Recovery of longitudinal metric distance Y_v\n• Lateral metric displacement X_v along camera HFOV",
            "Geometry Diagram Slot"
        );
    }

    // =========================================================================
    // SLIDE 4: IPM in Automotive ADAS - Heritage, Advantages & Pitfalls
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "IPM in Automotive ADAS: Decades of Heritage, Advantages & Pitfalls",
            "Why ground-plane projection powers automotive ADAS, and where its physical assumptions break down"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Industrial Heritage & Operational Strengths
        addCard(slide, 0.80, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "1. 30-Year Automotive ADAS Heritage:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Inverse Perspective Mapping was pioneered by Dickmanns (1988) and Bertozzi (1998) for autonomous highway driving (Prometheus project).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Core industrial foundation of commercial Lane Departure Warning (LDW) and front obstacle bounding systems (e.g. early MobilEye EyeQ chipsets).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Key Operational Advantages:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Ultra-Low Compute (O(1)):\n", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Requires only 3 trigonometric ops per target; operates at 50+ FPS on resource-constrained embedded edge processors without GPU acceleration.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Zero Neural Depth Hallucination:\n", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Monocular neural depth models (e.g. Depth Anything) suffer from scale ambiguity and heavy inference latency (~40-80ms). IPM is deterministic.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Metric Calibration Synergy:\n", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Directly consumes physical camera mount extrinsics [R | T] and pinhole intrinsics K.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Physical Pitfalls & Sensitivity Limits
        addCard(slide, 6.83, colY, colW, colH, COLOR_ROSE, "F8FAFC");
        slide.addText([
            { text: "3. Physical Vulnerabilities & Sensitivities:\n", options: { fontSize: 11, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "• Non-Flat Road Topology & Slopes (Z_road ≠ 0):\n", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Assumes a planar world. Uphill grades cause ray to strike earlier (distance over-estimation); downhill slopes delay intersection (under-estimation).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "• Dynamic Chassis Pitch Dynamics:\n", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Severe braking causes front chassis dive (-1.2°); hard acceleration causes rear squat (+1.5°). Uncompensated pitch distorts longitudinal range.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "• High-Range Pitch Sensitivity Divergence:\n", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Differentiating depth w.r.t pitch angle reveals quadratic error growth:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "     ∂Y / ∂θ ≈ - (Y² / H_cam)\n", options: { fontSize: 9.5, bold: true, color: COLOR_ROSE, fontFace: "Consolas" } },
            { text: "  At Y = 50m and H = 1.35m, a mere 0.5° pitch calibration error introduces a massive 16.2m longitudinal distance error!\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "• Horizon Singularity: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "As v_bottom approaches the optical horizon (v -> v_horizon), tan(β) -> 0 and depth diverges to infinity.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 7.03, y: colY + 0.20, w: colW - 0.40, h: colH - 1.15,
            valign: "top", margin: 0, wrap: true
        });

        // Reserved Equation Slot for Pitch Sensitivity Derivative
        addEquationSlot(slide, 7.03, colY + colH - 0.95, colW - 0.40, 0.75, "Native Equation: ∂Y / ∂θ ≈ - (Y² / H_cam)");
    }

    // =========================================================================
    // SLIDE 5: Modular Vision Pipeline & MCAP Integration
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Modular Geometric Vision Pipeline & MCAP Integration",
            "High-rate YOLO26 tracking, dual 3D bounding geometry, and non-destructive Foxglove topic injection"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Pipeline Architecture & 3D Geometry Sizing
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "1. Modular Tracking & Orientation Engine:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Ultralytics YOLO26 + ByteTrack: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Runs detection and multi-object tracking across vehicles, 2-wheelers, and pedestrians at 50+ FPS.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• MP4 180° Inversion Handler: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Parses container moov/tkhd matrix atoms, auto-rotating upside-down smartphone footage upright prior to inference.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Dual 3D Bounding Box Geometry:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Option A (Class Priors): ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Applies standardized automotive extents (Car: 1.8m x 1.5m x 4.2m, Pedestrian: 0.6m x 1.7m x 0.6m).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Option B (Camera-Derived Spans): ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Derives physical width and height directly from 2D pixel span:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   W_metric = (w_px / f_x) · Z_c,   H_metric = (h_px / f_y) · Z_c\n", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "• Road Elevation Anchor: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Box center is elevated to Z = H / 2, ensuring 3D cuboids rest naturally on the road surface rather than buried.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Non-Destructive MCAP Topic Injection:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Streams 3D vision cuboids to Foxglove topic ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "/vision/geo_tracks", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: " styled in Electric Magenta (rgba: 0.85, 0.10, 0.95, 0.75).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 100% Data Integrity: Preserves all 12 source channels and layout attachments byte-for-byte; source logs remain read-only.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated Video / Screenshot Showcase
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "Foxglove Studio 3D Perception Overlay",
            "Multi-Modal Perception Visualization:\n• Upright 1080p camera feed in Camera View panel\n• Cyan / Amber / Red TI mmWave radar tracks & Doppler vectors\n• Electric Magenta (/vision/geo_tracks) 3D vision cuboids\n• Billboard text labels showing ID, class, and metric range\n• Perfect co-registration on vehicle targets with <0.04px error",
            "Foxglove 3D Video Slot"
        );
    }

    // =========================================================================
    // SLIDE 6: IMU-Assisted Camera Calibration & Live Horizon Engine
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "IMU-Assisted Camera Calibration & Live Horizon Engine",
            "Eliminating pitch guesswork via gravity vector extraction, digital spirit level, and 25 Hz horizon follow"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Gravitational Physics & Studio UI Architecture
        addCard(slide, 0.80, colY, colW, colH, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "1. 1-Tap Gravitational Orientation Extraction:\n", options: { fontSize: 11, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• Magnetic-Immune Telemetry: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Leverages Sensor.TYPE_GAME_ROTATION_VECTOR (accel + gyro fusion), completely immune to EV motor and battery EMF distortion.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 1-Tap 'Sync Pitch' Button: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Extracts physical mount pitch (θ = arcsin(R_8)) and roll (φ = arcsin(R_7)) in <10 ms, removing manual angle guesswork.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Digital Clamp Spirit Level & ADAS Quality Badge:\n", options: { fontSize: 11, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• Digital Clamp Level: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Displays glowing green indicator when phone is clamped within ±0.8° of road level; displays guidance arrows if tilted.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• ADAS Pitch Quality Badge: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Classifies windshield tilt into OPTIMAL (3°-12°), TOO SHALLOW (<3°), TOO STEEP (>12°), or FACING UP (<0°).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. 25 Hz Live Horizon & Pillarbox Studio Layout:\n", options: { fontSize: 11, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• Dynamic Horizon Follow: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Projects artificial ground horizon line at 25 Hz tracking vehicle pitch/roll dynamics live on screen. [LOCK PITCH] seals the angle permanently into calibration JSON.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Natural Pillarbox Wings: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Utilizes 19.5:9 display's 210px side pillarbox wings to host pitch/yaw controls, leaving central 16:9 preview 100% unoccluded.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated Android UI Screenshot Slot
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "Fullscreen Calibration Studio UI",
            "RoadSense Android App Cockpit:\n• Top bar: Digital Clamp Spirit Level pill (±0.8°)\n• Pitch Quality Badge ('OPTIMAL PITCH 5.4°' in green)\n• Central 16:9 camera preview with live artificial horizon line\n• Left pillarbox wing: Camera Pitch (θ_cam) & 1-Tap Sync\n• Right pillarbox wing: Isolated Radar Bumper Yaw (ψ_rad)\n• Extrinsics Dialog for physical mount baseline entry",
            "Android App UI Slot"
        );
    }

    // =========================================================================
    // SLIDE 7: Master Sensor Decoupling & Coordinate System Architecture
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Master Sensor Alignment: Decoupling Camera Boresight from Radar Squint",
            "Eliminating cross-sensor distortion by establishing an explicit two-tier vehicle coordinate architecture"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: The Problem & Two-Tier Solution
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "1. The Cross-Sensor Conflation Defect (Bug #25):\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• The Defect: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "In earlier iterations, calibration conflated relative radar-camera offset into a single ambiguous 'yawDeg' parameter.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• The Impact: ", options: { fontSize: 9.5, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "When adjusting the slider to compensate for a +1.0° front bumper radar bracket squint, downstream tooling rotated the camera_optical /tf frame by +1.0°, causing a 17-pixel lateral skew in 3D vision tracks!\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. The Two-Tier Sensor-to-Vehicle Architecture:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Tier 1: Sensor-to-Vehicle Boresight: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Separately defines Camera Boresight (cameraYawDeg, cameraPitchDeg) and Radar Mounting Squint (radarSquintDeg) relative to vehicle base_link.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Tier 2: Cross-Sensor Alignment: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Camera heading defaults to 0.0° (pointing straight ahead). Bumper bracket adjustments are attributed strictly to radarSquintDeg (+1.0°).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Master Frame Standard Alignment:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• base_link: ISO 8855 FLU (+X Forward, +Y Left, +Z Up)\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• radar_link: TI Native RFU (+X Right, +Y Forward, +Z Up)\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• camera_optical: OpenCV RDF (+X Right, +Y Down, +Z Optical Forward)", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Coordinate Transform Table & Reserved Equation Slot
        addCard(slide, 6.83, colY, colW, 2.50, COLOR_CARD_BORDER, "F8FAFC");
        const coordRows = [
            [
                { text: "Target Frame", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "+X Axis", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "+Y Axis", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "+Z Axis", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } }
            ],
            [
                { text: "Vehicle (base_link)", options: { fontSize: 8, bold: true, color: COLOR_PRIMARY } },
                { text: "Forward", options: { fontSize: 8, color: COLOR_TEXT_MAIN } },
                { text: "Left", options: { fontSize: 8, color: COLOR_TEXT_MUTED } },
                { text: "Up", options: { fontSize: 8, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "Radar (radar_link)", options: { fontSize: 8, bold: true, color: COLOR_SECONDARY } },
                { text: "Right", options: { fontSize: 8, color: COLOR_TEXT_MUTED } },
                { text: "Forward", options: { fontSize: 8, color: COLOR_TEXT_MAIN } },
                { text: "Up", options: { fontSize: 8, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "Camera (camera_optical)", options: { fontSize: 8, bold: true, color: COLOR_PURPLE } },
                { text: "Right", options: { fontSize: 8, color: COLOR_TEXT_MUTED } },
                { text: "Down", options: { fontSize: 8, color: COLOR_TEXT_MUTED } },
                { text: "Forward (Depth)", options: { fontSize: 8, color: COLOR_TEXT_MAIN } }
            ]
        ];
        slide.addTable(coordRows, {
            x: 7.03, y: colY + 0.15, w: colW - 0.40,
            colW: [1.80, 1.15, 1.15, 1.20],
            border: { type: "solid", pt: 1, color: "CBD5E1" },
            fill: "FFFFFF", valign: "middle"
        });

        // Bottom Right Placeholder: Visual Decoupling Schematic Slot
        addPlaceholder(
            slide,
            6.83, colY + 2.65, colW, colH - 2.65,
            "Sensor Boresight vs. Squint Alignment Diagram",
            "Schematic Overview:\n• Centerline vehicle driving axis (+X_base)\n• Camera optical axis mounted straight ahead (ψ_cam = 0.0°)\n• TI mmWave radar bumper bracket squint (ψ_radar = +1.0°)\n• Independent coordinate transforms into unified base_link\n• Zero frustum tilt on video overlays and 3D cuboids",
            "Decoupling Diagram Slot"
        );
    }

    // =========================================================================
    // SLIDE 8: Radar-Vision Track Association & Spatial Benchmarking
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Radar-to-Vision Track Association & Spatio-Temporal Evaluation",
            "Automated Hungarian bipartite matching and distance-binned error metrics against mmWave radar ground truth"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Matching Pipeline & Metrics
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "1. Spatio-Temporal Association Engine:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Temporal Matching Window: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Matches /vision/geo_tracks with /radar/tracks within a strict ±50 ms temporal gate using master UTC nanosecond timestamps.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Hungarian Bipartite Assignment: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Constructs a 2D Euclidean distance cost matrix on the ground plane, solved via Jonker-Volgenant algorithm (lap.lapjv).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Spatial Gating Cutoff: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Enforces a 3.5-meter distance threshold to prevent false associations with roadside clutter or adjacent lane vehicles.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Distance-Binned Quantitative Metrics:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Performance is segmented into 3 operational distance zones:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   - Near-Field (0-15m): High optical pixel resolution, maximum tracking accuracy.\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   - Mid-Range (15-30m): Nominal ADAS braking horizon; balanced radar/vision.\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   - Far-Field (>30m): IPM pitch sensitivity begins to dominate; radar range truth.\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Computed Statistics: Longitudinal MAE/RMSE (ΔY), Lateral MAE/RMSE (ΔX), 2D spatial error, and overall track association percentage.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "• Automated Evaluation CLI: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "evaluate_vision_vs_radar.py generates machine-readable benchmark reports (benchmark_report.json) for fleet validation.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated Benchmarking Slot
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "Radar vs. Vision Ground-Plane Track Association Map",
            "Benchmarking Visualization:\n• Ground-plane scatter plot (X_v, Y_v) comparing sensors\n• Blue points: Vision IPM ground contacts\n• Red points: mmWave radar track centroids\n• Green lines: Hungarian association correspondences\n• Zone breakdown cards: Near / Mid / Far RMSE\n• Cumulative association match rate scorecard",
            "Benchmarking Chart Slot"
        );
    }

    // =========================================================================
    // SLIDE 9: Master Timeline Synchronizer & Nanosecond Clock Alignment
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Master Timeline Synchronizer: Nanosecond Multi-Sensor Alignment",
            "Resolving cumulative video phase lead (Bug #22) via hardware-sequence locking and linear interpolation"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Root Cause & Solution
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "1. The 2.16-Second Video Phase Lead (Bug #22):\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• The Symptom: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "In a 40-minute driving session, camera video moved 2.16 seconds before wheel speed, IMU acceleration, or radar tracks climbed.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Root Cause: ", options: { fontSize: 9.5, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "Hardware MediaRecorder captured 72,336 packets with 0 drops, while userspace dropped 91 shutter callbacks under CPU load. Sequential row indexing shifted later video packets 2.16s into the past!\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. The Hardware Sequence Mapping Invariant:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Hardware Camera Counter: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "CaptureResult.getFrameNumber() strictly increments by 1 for every exposure. The invariant:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   expected_hw_seq = video_start_seq_id + packet_idx\n", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "holds with 100% precision across entire sessions regardless of dropped userspace callbacks.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Linear Interpolation & /sync/frame_info Channel:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• When a callback is dropped, master_timeline_synchronizer.py interpolates its timestamp using the camera crystal clock (33.293 ms/frame).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Integrated Channel: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Injects /sync/frame_info into MCAP, stamping each frame with its matched radar frame ID and nanosecond delta (<15ms).", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated Timing Synchronization Graph Slot
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "Nanosecond Timeline Alignment Verification Plot",
            "Cross-Sensor Timing Correlation Graph:\n• Blue trace: Vehicle video presentation timestamp (PTS)\n• Red trace: TI mmWave radar frame arrival timestamps\n• Green trace: CAN wheel speed climb during acceleration\n• Phase lead verification: Zero drift across 40-minute drive\n• Verified sync delta: <14.88 ms at t = 720.96s (radar frame 21,032)",
            "Timing Synchronization Graph Slot"
        );
    }

    // =========================================================================
    // SLIDE 10: 100 Hz Zero-Jitter Kinematics & Android OS Hardening
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "100 Hz Zero-Jitter Kinematics Subsystem & Android OS Hardening",
            "Bypassing Android HAL decimation filters to achieve sub-millisecond IMU jitter at native 104 Hz"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Jitter Elimination & Kinematics Capabilities
        addCard(slide, 0.80, colY, colW, colH, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "1. The 18 ms Jitter Spike Elimination (Bug #19):\n", options: { fontSize: 11, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• The Quirk: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Requesting explicit microsecond periods (e.g. 10,000 µs) activated Android HAL software decimation, creating periodic 18 ms jumps (Δt_prev + Δt_spike = 30.0 ms).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• The Solution: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Switched to SENSOR_DELAY_FASTEST (0 µs) with an SPSC lock-free ring buffer, locking to ST LSM6DSL native 104 Hz rate with sub-millisecond jitter (σ < 1.0 ms).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Real-Time Gravity-Free Linear Acceleration:\n", options: { fontSize: 11, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• Sensor.TYPE_LINEAR_ACCELERATION: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Continuously separates and removes Earth gravity (9.81 m/s²) from total acceleration in real time on the phone.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Clean Dynamics: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Provides pure dynamic braking and cornering forces without tilt bias.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Magnetic-Immune 6-DOF Game Rotation Vector:\n", options: { fontSize: 11, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• Derives orientation quaternion (x,y,z,w) using solely accelerometer and gyro integration, ignoring the magnetic compass.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• EV Hardening: ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Guarantees heading stability around high-voltage EV motor inverters and heavy electrical wiring harnesses.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated IMU Histograms Slot
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "100 Hz IMU Jitter Distribution & Kinematics Acceleration Curves",
            "Kinematics Telemetry Plots:\n• Inter-frame interval histogram centered at 9.615 ms (104 Hz)\n• Jitter distribution showing σ < 0.99 ms and zero 18 ms spikes\n• Linear acceleration curves during aggressive vehicle braking\n• Game rotation vector Euler angles (Pitch, Roll, Yaw)\n• Microsecond monotonic timestamp alignment with video",
            "IMU Kinematics Chart Slot"
        );
    }

    // =========================================================================
    // SLIDE 11: Deep-Tech Diagnostics - On-Device Perfetto Kernel Tracing via ADB
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Deep-Tech Diagnostics: On-Device Perfetto Kernel Tracing via ADB",
            "Zero-overhead nanosecond Linux kernel scheduler and hardware IRQ profiling on target hardware"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Profiling Architecture & Results
        addCard(slide, 0.80, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "1. The Zero-Overhead Tracing Architecture:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• The Diagnostic Dilemma: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Evaluating microsecond timing anomalies with userspace loggers introduces CPU overhead, altering system behavior (Heisenberg effect).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• The Perfetto Toolchain: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Scripts (record_perfetto_trace.py & analyze_perfetto_trace.py) configure Android's kernel-level ftrace daemon via ADB shell with a 32-64MB circular buffer (<1% CPU impact) on unrooted hardware.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Tracked Subsystems & Application Slices:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Linux CFS Scheduler: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "sched/sched_switch, sched_wakeup, and big.LITTLE core migration.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Hardware Interrupts: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "irq/irq_handler_entry and exit tracking ST LSM6DSL physical interrupts.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Custom App Trace Slices: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "High-precision Trace.beginSection('RoadSense:onSensorChanged') markers.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Definitive Diagnostic Finding:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Proved that RoadSense-ImuThread processes samples in <0.05 ms with zero CPU starvation.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Conclusively demonstrated that 18 ms spikes originated from vendor HAL software decimation rather than OS scheduling delays.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated Perfetto UI Screenshot Slot
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "Perfetto System Trace Visualization (ui.perfetto.dev)",
            "Kernel Scheduler Trace Breakdown:\n• CPU 0-7 big.LITTLE scheduling swimlanes\n• RoadSense-ImuThread execution slices (<0.05 ms)\n• ST LSM6DSL hardware IRQ arrival pulses\n• Thread state timeline: Running vs. Runnable vs. Sleeping\n• Nanosecond correlation with imu_frames.csv ticks",
            "Perfetto UI Trace Slot"
        );
    }

    // =========================================================================
    // SLIDE 12: Data Delivery - Standalone Interactive HTML Audit Reports & HUD
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Data Delivery: Standalone Interactive HTML Audit Reports & HUD",
            "Zero-dependency offline inspection reports with synchronized 6-track zooming and jitter-free crosshair HUD"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Interactive Audit Architecture & HUD Innovation
        addCard(slide, 0.80, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "1. Zero-Dependency Standalone Architecture:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Solves Matplotlib Squish: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Static PNGs squished nominal 20 ms data into flat lines due to 500 ms camera startup spikes. HTML reports provide dynamic zooming.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Completely Offline (~1.2 MB): ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Inlines Plotly.js directly. Opens on any PC via file:/// without Python, internet, or local servers.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Foxglove-Style Mouse-Locked HUD:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• The Innovation (Bug #20): ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Standard Plotly tooltips violently jumped 80px vertically when hovering noisy 100 Hz signals. We decoupled tooltips from discrete points, locking a vertical crosshair to cursor clientX with O(log N) binary search.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Result: Completely jitter-free hover readout across all 6 sensor tracks.\n\n", options: { fontSize: 9, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },

            { text: "3. Sticky Navigation & Dual Certification Scorecards:\n", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• 1-Click View Toggles: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Sticky toolbar with 'Steady-State Focus', 'Show All Spikes', 'Startup Phase (0-5s)', and 'Teardown Phase'.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Dual Scorecards: ", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Side-by-side 8-Point Subsystem Health + 10-Point Cross-Sensor Sync verification table with CERTIFIED GOLD badges.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated Interactive HTML Report Slot
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "Interactive Multi-Sensor HTML Audit Dashboard",
            "HTML Audit Report UI (Plotly.js):\n• Coordinated 6-panel synchronized time series\n• Sticky navigation bar with 1-click view toggles\n• Foxglove-style mouse-locked vertical crosshair HUD\n• Zooming on one panel zooms all 6 tracks in lockstep\n• Executive Summary card with CERTIFIED GOLD certification\n• Complete 10-point cross-sensor sync audit checklist",
            "Interactive HTML Report Slot"
        );
    }

    // =========================================================================
    // SLIDE 13: Central Web Dashboard - Single Script Execution Platform
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Central Web Dashboard: Single Unified Script Execution Platform",
            "Zero-dependency local cockpit on :8088 running synchronization, conversion, health audits, and vision"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Central Dashboard Capabilities
        addCard(slide, 0.80, colY, colW, colH, COLOR_INDIGO, "F8FAFC");
        slide.addText([
            { text: "1. Zero-Dependency Local Web Server (:8088):\n", options: { fontSize: 11, bold: true, color: COLOR_INDIGO, fontFace: "Segoe UI" } },
            { text: "• Built using Python's standard http.server with Server-Sent Events (SSE) live streaming.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Zero external web framework dependencies (no Flask/Django/Node.js required for desktop analytics).\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Live ADB monitoring: Connected device serial, battery level, storage space, and session discovery.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. 1-Click Multi-Script Runner Pipeline:\n", options: { fontSize: 11, bold: true, color: COLOR_INDIGO, fontFace: "Segoe UI" } },
            { text: "• Single Unified Cockpit: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Replaces fragmented manual terminal commands with an automated 1-click execution pipeline:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   Raw Session Ingestion ──► Master Timeline Sync ──► 10,400 FPS MCAP Build ──► 8-Point Health Audit ──► Geometric Vision Ingestion\n", options: { fontSize: 8.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "• Real-Time Progress: Streams console stdout/stderr live to browser terminal.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Native OS & Artifact Integration:\n", options: { fontSize: 11, bold: true, color: COLOR_INDIGO, fontFace: "Segoe UI" } },
            { text: "• Native /api/open_folder: Opens Windows Explorer directly to session logs in 1 click.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Direct HTML Report Links: Launches interactive Plotly audit dashboards instantly.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Designated Web Cockpit UI Slot
        addPlaceholder(
            slide,
            6.83, colY, colW, colH,
            "RoadSense Central Web Dashboard Cockpit (:8088)",
            "Web Cockpit Interface Overview:\n• Dark-mode responsive automotive dashboard\n• ADB device health badge (Samsung Galaxy M30)\n• Session archive table with sensor availability tags\n• 1-Click 'Run All Audits' master action banner\n• Live Server-Sent Events (SSE) execution log modal\n• Direct 'Open Folder' and 'View Report' actions",
            "Web Cockpit UI Slot"
        );
    }

    // =========================================================================
    // SLIDE 14: System Hardening - Root Cause Analysis: Part 1 (Compact 6 Boxes)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Root Cause Analysis: Subsystem Hardening Post-Mortems (Part 1)",
            "Eliminating container metadata overflows, CAN telemetry shifts, schema rejections, and IMU jitter"
        );

        const cardW = 3.65;
        const cardH = 2.55;
        const colXs = [0.80, 4.84, 8.88];
        const rowYs = [1.65, 4.35];

        const bugsPart1 = [
            {
                title: "Bug #16: Large MP4 Metadata Overflow",
                symptom: "Recordings >500MB crashed MCAP video parser with 'moov atom not found'.",
                cause: "Parser used fixed 64KB tail buffer; moov atom scales with duration, growing to 2-8MB in long drives.",
                fix: "Implemented ISO box traversal jumping directly to moov header via box length offsets in O(1) zero-copy time."
            },
            {
                title: "Bug #17: 1-Byte Offset in TLV 6 FCW Telemetry",
                symptom: "Forward Collision Warning (FCW) target coordinates were severely misreported as TargetX.",
                cause: "Firmware packed a 1-byte counter before CAN payload, shifting bitfield decoding by 8 bits.",
                fix: "Corrected decoding byte offset and integrated color-coded FCW alerting (Green/Amber/Crimson) in Foxglove."
            },
            {
                title: "Bug #18: Foxglove Anonymous Schema Hash",
                symptom: "Foxglove Studio rejected custom TypeScript calibration overlays with anonymous hash (0bb4508b).",
                cause: "Returning standard JavaScript number[] arrays or generic types broke Protobuf schema resolution.",
                fix: "Imported official @foxglove/schemas and instantiated typed Float64Array matrix buffers."
            },
            {
                title: "Bug #19: 100 Hz IMU 18 ms Jitter Spikes",
                symptom: "IMU logging had periodic 18 ms timestamp jumps every ~1.0s (Δt_prev + Δt_spike = 30.0 ms).",
                cause: "Explicit microsecond delays (10,000 µs) activated Android HAL software decimation filters.",
                fix: "Enforced SENSOR_DELAY_FASTEST (0 µs) with SPSC lock-free ring buffer, locking to ST LSM6DSL 104 Hz (σ < 1.0 ms)."
            },
            {
                title: "Bug #20: Multi-Track Hover Tooltip Jitter",
                symptom: "Hovering over noisy 100 Hz signals caused tooltips to violently bounce 80px vertically.",
                cause: "Plotly default anchored tooltips to discrete (x,y) points rather than client cursor coordinates.",
                fix: "Decoupled HUD from discrete points; locked vertical crosshair to cursor clientX with O(log N) binary search."
            },
            {
                title: "Bug #21: Pre-Roll Shutter Video Desync",
                symptom: "Video frames drifted 900 ms out of phase with radar point clouds during replay.",
                cause: "Converters assumed MP4 packet 0 strictly mapped to CSV row 0, bypassing camera preview startup.",
                fix: "Structured synchronizer to ingest session_timeline.csv as ground truth, anchoring to record sequence start."
            }
        ];

        bugsPart1.forEach((b, idx) => {
            const col = idx % 3;
            const row = Math.floor(idx / 3);
            const x = colXs[col];
            const y = rowYs[row];

            addCard(slide, x, y, cardW, cardH, COLOR_ROSE, "F8FAFC");
            slide.addText([
                { text: b.title + "\n\n", options: { fontSize: 10, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
                { text: "• Symptom: ", options: { fontSize: 8.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.symptom + "\n", options: { fontSize: 8, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Root Cause: ", options: { fontSize: 8.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.cause + "\n", options: { fontSize: 8, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Solution: ", options: { fontSize: 8.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
                { text: b.fix, options: { fontSize: 8, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
            ], {
                x: x + 0.15, y: y + 0.12, w: cardW - 0.30, h: cardH - 0.24,
                valign: "top", margin: 0, wrap: true
            });
        });
    }

    // =========================================================================
    // SLIDE 15: System Hardening - Root Cause Analysis: Part 2 (Compact 6 Boxes)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Root Cause Analysis: Subsystem Hardening Post-Mortems (Part 2)",
            "Eliminating video lead, converter bottlenecks, optical inversions, and cross-sensor conflation"
        );

        const cardW = 3.65;
        const cardH = 2.55;
        const colXs = [0.80, 4.84, 8.88];
        const rowYs = [1.65, 4.35];

        const bugsPart2 = [
            {
                title: "Bug #22: 2.16s Video Lead via Dropped Callbacks",
                symptom: "Camera video led wheel speed and radar points by 2.16s in 40-minute driving sessions.",
                cause: "Android userspace dropped 91 callbacks under load, shifting sequential index mapping into the past.",
                fix: "MasterTimelineSynchronizer locks MP4 packets to hardware camera exposure IDs (S_start + idx); sync error < 15ms."
            },
            {
                title: "Bug #23: MCAP Converter Speed Collapse",
                symptom: "Conversion speed collapsed from >3,000 FPS to ~120 FPS with 0% GPU and <8% CPU load.",
                cause: "Uncached list comprehensions in frame lookups created 12.16B temporary Python objects, stalling GIL in GC.",
                fix: "Precomputed contiguous numpy arrays and O(log N) bisect lookups; throughput surged to 10,411 FPS (80x speedup)."
            },
            {
                title: "Bug #24: Optical Pitch Sign Inversion (Rx(-P))",
                symptom: "3D radar bounding boxes in Foxglove floated ~180-190px too high into the sky above road vehicles.",
                cause: "Python tooling wrote Rx with inverted signs (Rx(-P) transpose), pointing optical axis +5.4° UP instead of DOWN.",
                fix: "Corrected Rx rotation signs in compute_camera_optical_tf; overlays land natively on vehicles with zero user scripts."
            },
            {
                title: "Bug #25: Camera Yaw Decoupling from Radar Squint",
                symptom: "Compensating for radar mounting squint tilted camera optical frustum, skewing vision tracks by 17px.",
                cause: "Calibration parameters conflated sensor-to-vehicle angles with relative cross-sensor offsets into single yawDeg.",
                fix: "Established two-tier architecture: cameraYawDeg (0.0°) vs radarSquintDeg (+1.0°); verified zero cross-distortion."
            },
            {
                title: "Bug #12: Exynos Dual-Stream HAL Stalling",
                symptom: "Camera2 HAL deadlock and ANR crashes when activating dual analysis streams on Samsung Exynos.",
                cause: "Hardware ISP enforces strict concurrent surface output limits, starving ImageReader buffers.",
                fix: "Road AE samples downsampled 32x24 bitmaps directly from active TextureView on Dispatchers.Default; zero extra streams."
            },
            {
                title: "Bug #13 & #14: Foxglove Ghosting & Seek Freeze",
                symptom: "Past radar tracks persisted indefinitely on 3D canvas; video seek froze on blank screens.",
                cause: "Dynamic entity IDs per frame caused object accumulation; sparse keyframes lacked closed GOPs.",
                fix: "Static entity ID 'radar_tracks' for atomic updates; enforced 1-sec closed GOP 30 (-forced-idr 1) in transcoding."
            }
        ];

        bugsPart2.forEach((b, idx) => {
            const col = idx % 3;
            const row = Math.floor(idx / 3);
            const x = colXs[col];
            const y = rowYs[row];

            addCard(slide, x, y, cardW, cardH, COLOR_ROSE, "F8FAFC");
            slide.addText([
                { text: b.title + "\n\n", options: { fontSize: 10, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
                { text: "• Symptom: ", options: { fontSize: 8.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.symptom + "\n", options: { fontSize: 8, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Root Cause: ", options: { fontSize: 8.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.cause + "\n", options: { fontSize: 8, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Solution: ", options: { fontSize: 8.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
                { text: b.fix, options: { fontSize: 8, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
            ], {
                x: x + 0.15, y: y + 0.12, w: cardW - 0.30, h: cardH - 0.24,
                valign: "top", margin: 0, wrap: true
            });
        });
    }

    // =========================================================================
    // SLIDE 16: Milestone Handover & System Perfection Roadmap
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Milestone Handover & System Perfection Roadmap",
            "Consolidating Sprint 3 perception foundations and establishing disciplined hardware alignment targets"
        );

        const colW = 5.70;
        const colY = 1.60;
        const colH = 5.30;

        // Left Column: Sprint 3 Deliverables Complete
        addCard(slide, 0.80, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "Sprint 3 Deliverables: 100% Complete\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Perception, Spatial Decoupling & Toolchain Foundation\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },

            { text: "1. Inverse Perspective Mapping (IPM) Operational [✔]:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Closed-form flat-earth raycasting (50+ FPS) and non-destructive Foxglove MCAP topic injection (/vision/geo_tracks).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. IMU-Assisted Camera Calibration Operational [✔]:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   1-tap gravity pitch sync, digital clamp spirit level (±0.8°), ADAS quality badge, and 25 Hz live horizon tracking.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Master Sensor Decoupling Verified [✔]:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Two-tier architecture separating Camera Boresight from Radar Squint; Bug #25 lateral drift resolved.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "4. Master Timeline Synchronizer Active [✔]:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Hardware camera sequence locking eliminates 2.16s video lead (Bug #22); /sync/frame_info stream active.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "5. Deep-Tech Diagnostics Suite Operational [✔]:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   On-device Perfetto kernel tracing (<0.05ms app slice), offline interactive HTML reports, and 10,400 FPS converter.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "6. System Hardening Complete [✔]:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Bugs #11 through #25 diagnosed and permanently eliminated; 53 JVM unit tests passing clean.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: System Perfection Roadmap
        addCard(slide, 6.83, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Upcoming Priority: System Perfection\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Hardware Rigidity & Association Gates\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },

            { text: "1. The Current Physical Challenge:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Experimental vehicle mounts currently exhibit minor bracket vibration and mechanical pitch/yaw play during road tests, introducing dynamic sensor alignment variance.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Hardware Mount Hardening:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Fabricate rigidized mechanical mounting brackets for front bumper radar.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Implement vibration-damped windshield smartphone mount.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Perform static ground-truth extrinsic survey on certified level ground.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. The Strict Quantitative Association Gate:\n", options: { fontSize: 10, bold: true, color: COLOR_WARNING, fontFace: "Segoe UI" } },
            { text: "   • Target Milestone: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Achieve sustained ", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "> 66% radar-vision track association", options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: " across continuous drive cycles.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Disciplined Strategy: Advance to supervised model training and automated validation only AFTER the 66% physical association gate is reliably breached.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "Questions & Technical Discussion", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, align: "center", fontFace: "Segoe UI" } }
        ], {
            x: 7.03, y: colY + 0.18, w: colW - 0.40, h: colH - 0.36,
            valign: "top", margin: 0, wrap: true
        });
    }

    // Save PPTX Presentation to decks/ directory
    const outDeckPath = path.join(__dirname, "decks", "RoadSense_Week3_Executive_Progress.pptx");
    await pres.writeFile({ fileName: outDeckPath });
    console.log(`[RoadSense] Successfully generated 16-slide master presentation at: ${outDeckPath}`);
}

// CLI Execution entrypoint
if (process.argv[1] && process.argv[1].endsWith("generate_roadsense_week3_deck.js")) {
    generateRoadSenseWeek3Presentation().catch(err => {
        console.error("[RoadSense] Error generating Week 3 presentation:", err);
        process.exit(1);
    });
}
