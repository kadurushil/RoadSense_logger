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
import fs from "fs";
import { fileURLToPath } from "url";
import { mathjax } from "mathjax-full/js/mathjax.js";
import { TeX } from "mathjax-full/js/input/tex.js";
import { SVG } from "mathjax-full/js/output/svg.js";
import { liteAdaptor } from "mathjax-full/js/adaptors/liteAdaptor.js";
import { RegisterHTMLHandler } from "mathjax-full/js/handlers/html.js";
import { AllPackages } from "mathjax-full/js/input/tex/AllPackages.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Initialize MathJax headless SVG renderer
const adaptor = liteAdaptor();
RegisterHTMLHandler(adaptor);
const mathjaxDoc = mathjax.document('', {
    InputJax: new TeX({ packages: AllPackages }),
    OutputJax: new SVG({ fontCache: 'local' })
});

function renderLatexToSvg(tex, filename, color = '#1D4ED8') {
    const node = mathjaxDoc.convert(tex, { display: true });
    let svg = adaptor.innerHTML(node);
    if (!svg.includes('xmlns="http://www.w3.org/2000/svg"')) {
        svg = svg.replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" ');
    }
    svg = svg.replace(/currentColor/g, color);

    const match = svg.match(/viewBox="([^"]+)"/);
    let aspectRatio = 4.0;
    if (match) {
        const parts = match[1].trim().split(/\s+/).map(Number);
        if (parts.length === 4 && parts[3] > 0) {
            aspectRatio = parts[2] / parts[3];
        }
    }

    const outDir = path.join(__dirname, 'assets', 'generated_math');
    if (!fs.existsSync(outDir)) {
        fs.mkdirSync(outDir, { recursive: true });
    }
    const filePath = path.join(outDir, filename);
    fs.writeFileSync(filePath, svg);
    return { filePath, aspectRatio };
}

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
    // SLIDE 3: 6-DOF Spatial Calibration & Perspective Projection Math
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Perception & Sensor Fusion Engineering",
            "6-DOF Spatial Calibration & Perspective Projection Math",
            "Rigorous mathematical formulation for cross-sensor extrinsic alignment and closed-form angular recovery",
            3
        );

        const colW = 5.75;
        const colY = 1.55;
        const colH = 5.40;

        // Render mathematical equation SVGs via MathJax
        const eqPvehicle = renderLatexToSvg(
            '\\mathbf{P}_{\\text{vehicle}} = \\mathbf{R}_{\\text{extrinsic}}(\\mathbf{P}_R - \\mathbf{T})',
            'eq_p_vehicle.svg',
            '#1D4ED8'
        );

        const eqMaxis = renderLatexToSvg(
            '\\mathbf{M}_{\\text{axis}} = \\begin{bmatrix} 1 & 0 & 0 \\\\ 0 & 0 & -1 \\\\ 0 & 1 & 0 \\end{bmatrix}',
            'eq_m_axis_matrix.svg',
            '#1D4ED8'
        );

        const eqPinholeMatrix = renderLatexToSvg(
            '\\begin{bmatrix} u \\cdot w \\\\ v \\cdot w \\\\ w \\end{bmatrix} = \\mathbf{K} \\cdot \\mathbf{P}_C = \\begin{bmatrix} f_x & 0 & c_x \\\\ 0 & f_y & c_y \\\\ 0 & 0 & 1 \\end{bmatrix} \\begin{bmatrix} X_C \\\\ Y_C \\\\ Z_C \\end{bmatrix}',
            'eq_pinhole_matrix.svg',
            '#0284C7'
        );

        const eqPerspectiveDiv = renderLatexToSvg(
            'u = \\frac{f_x \\cdot X_C}{Z_C} + c_x, \\qquad v = \\frac{f_y \\cdot Y_C}{Z_C} + c_y',
            'eq_perspective_division.svg',
            '#0284C7'
        );

        const eqNormRay = renderLatexToSvg(
            'u_{\\text{norm}} = \\frac{u_{\\text{tap}} - c_x}{f_x}, \\qquad v_{\\text{norm}} = \\frac{v_{\\text{tap}} - c_y}{f_y}',
            'eq_norm_ray.svg',
            '#0284C7'
        );

        const eqAngleRecovery = renderLatexToSvg(
            '\\text{pitch } \\theta = -\\arctan(v_{\\text{norm}}), \\qquad \\text{yaw } \\psi = \\arctan(u_{\\text{norm}})',
            'eq_angle_recovery.svg',
            '#0284C7'
        );

        // ---------------------------------------------------------------------
        // LEFT COLUMN: Coordinate Frame Conventions & Translation / Axis Permutation
        // ---------------------------------------------------------------------
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        const leftX = 1.00;
        const leftW = colW - 0.40;
        const leftCenterX = 0.80 + (colW / 2);

        // 1. Coordinate Frame Conventions
        slide.addText([
            { text: "1. Coordinate Frame Conventions:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "   • Radar Frame {R}: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "+X_R = Right,  +Y_R = Forward (boresight),  +Z_R = Up.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Camera Optical Frame {C}: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "+X_C = Right,  +Y_C = Down,  +Z_C = Optical Depth (Forward).", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: leftX, y: colY + 0.18, w: leftW, h: 0.95,
            valign: "top", margin: 0, wrap: true
        });

        // 2. Translation & Axis Transformation Intro
        slide.addText([
            { text: "2. Translation & Axis Transformation:\n", options: { fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "A point ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "P_R = [X_R, Y_R, Z_R]^T", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: " is translated by physical lever-arm offset ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "T", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: " and rotated by Euler angles (pitch θ, yaw ψ, roll φ):", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: leftX, y: colY + 1.20, w: leftW, h: 0.60,
            valign: "top", margin: 0, wrap: true
        });

        // Equation 1: P_vehicle = R_extrinsic (P_R - T)
        const eq1W = Math.min(3.80, Math.max(2.40, eqPvehicle.aspectRatio * 0.36));
        const eq1H = eq1W / eqPvehicle.aspectRatio;
        slide.addImage({
            path: eqPvehicle.filePath,
            x: leftCenterX - (eq1W / 2),
            y: colY + 1.85,
            w: eq1W,
            h: eq1H
        });

        // Text: M_axis application
        slide.addText([
            { text: "To align the vehicle/radar axes into camera optical frame {C}, we apply the permutation matrix ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "M_axis", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: ":", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: leftX, y: colY + 2.30, w: leftW, h: 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Equation 2: M_axis matrix
        const eq2H = 0.75;
        const eq2W = eq2H * eqMaxis.aspectRatio;
        slide.addImage({
            path: eqMaxis.filePath,
            x: leftCenterX - (eq2W / 2),
            y: colY + 2.75,
            w: eq2W,
            h: eq2H
        });

        // Text: mapping result
        slide.addText([
            { text: "which maps: ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "X_C = X_veh,  Y_C = -Z_veh", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: " (up becomes down),  ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "Z_C = Y_veh", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: " (forward becomes optical depth).", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: leftX, y: colY + 3.65, w: leftW, h: 0.50,
            valign: "top", margin: 0, wrap: true
        });

        // Key Architectural Benefit note
        slide.addText([
            { text: "• Zero-Calibration Rig Requirement: ", options: { fontSize: 8.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Rigid-body baseline offsets [ΔX, ΔY, ΔZ] are measured directly at installation, decoupling translation from orientation.", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: leftX, y: colY + 4.30, w: leftW, h: 0.70,
            valign: "top", margin: 0, wrap: true
        });

        // ---------------------------------------------------------------------
        // RIGHT COLUMN: Pinhole Perspective Projection & Reverse Touch Solver
        // ---------------------------------------------------------------------
        addCard(slide, 6.78, colY, colW, colH, COLOR_SECONDARY, "F8FAFC");
        const rightX = 6.98;
        const rightW = colW - 0.40;
        const rightCenterX = 6.78 + (colW / 2);

        // 3. Pinhole Perspective Projection Title
        slide.addText("3. Pinhole Perspective Projection:", {
            x: rightX, y: colY + 0.18, w: rightW, h: 0.28,
            fontSize: 11, bold: true, color: COLOR_SECONDARY, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });

        // Equation 3: [u*w, v*w, w]^T = K * P_C
        const eq3H = 0.65;
        const eq3W = eq3H * eqPinholeMatrix.aspectRatio;
        slide.addImage({
            path: eqPinholeMatrix.filePath,
            x: rightCenterX - (eq3W / 2),
            y: colY + 0.50,
            w: eq3W,
            h: eq3H
        });

        // Equation 4: u = (fx * X_C) / Z_C + cx, v = (fy * Y_C) / Z_C + cy
        const eq4H = 0.38;
        const eq4W = Math.min(rightW, eq4H * eqPerspectiveDiv.aspectRatio);
        slide.addImage({
            path: eqPerspectiveDiv.filePath,
            x: rightCenterX - (eq4W / 2),
            y: colY + 1.22,
            w: eq4W,
            h: eq4H
        });

        // CameraCharacteristics note
        slide.addText([
            { text: "where ", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "[fx, fy, cx, cy]", options: { fontSize: 8.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: " are retrieved programmatically from Android's ", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "CameraCharacteristics.LENS_INTRINSIC_CALIBRATION", options: { fontSize: 8.5, bold: true, color: COLOR_SECONDARY, fontFace: "Consolas" } },
            { text: ".", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: rightX, y: colY + 1.68, w: rightW, h: 0.35,
            valign: "top", margin: 0, wrap: true
        });

        // 4. Closed-Form Reverse Touch Solver Title & Lead Text
        slide.addText([
            { text: "4. Closed-Form Reverse Touch Solver (O(1) Calibration):\n", options: { fontSize: 11, bold: true, color: COLOR_SECONDARY, fontFace: "Segoe UI" } },
            { text: "Instead of iterative non-linear optimization or checkerboards, the user taps a vehicle bounding centroid ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "(u_tap, v_tap)", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: " at radar distance ", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "R", options: { fontSize: 9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: ":", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: rightX, y: colY + 2.15, w: rightW, h: 0.60,
            valign: "top", margin: 0, wrap: true
        });

        // Equation 5: u_norm, v_norm
        const eq5H = 0.38;
        const eq5W = Math.min(rightW, eq5H * eqNormRay.aspectRatio);
        slide.addImage({
            path: eqNormRay.filePath,
            x: rightCenterX - (eq5W / 2),
            y: colY + 2.80,
            w: eq5W,
            h: eq5H
        });

        // Equation 6: pitch theta = -arctan(v_norm), yaw psi = arctan(u_norm)
        const eq6H = 0.36;
        const eq6W = Math.min(rightW, eq6H * eqAngleRecovery.aspectRatio);
        slide.addImage({
            path: eqAngleRecovery.filePath,
            x: rightCenterX - (eq6W / 2),
            y: colY + 3.26,
            w: eq6W,
            h: eq6H
        });

        // Conclusion & Test Track Convergence Note
        slide.addText([
            { text: "• Instant Track Convergence: ", options: { fontSize: 8.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Recovers pitch and yaw in <1 ms with zero gradient descent or bundle adjustment. Guarantees instant, repeatable calibration right on the test track.", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: rightX, y: colY + 3.80, w: rightW, h: 0.55,
            valign: "top", margin: 0, wrap: true
        });

        // Storage & Monotonic sync note
        slide.addText([
            { text: "• Persistent JSON & Monotonic Sync: ", options: { fontSize: 8.5, bold: true, color: COLOR_SECONDARY, fontFace: "Segoe UI" } },
            { text: "Angles are saved to calibration/radar_camera_calib.json and attached directly to session MCAP containers for unified Foxglove playback.", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: rightX, y: colY + 4.40, w: rightW, h: 0.65,
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
            { text: "Zero-Dependency Local Web Server & Automation Engine\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "1. Zero External Python Packages:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Built exclusively on Python 3 standard library (http.server.ThreadingHTTPServer).\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Runs instantly out-of-the-box on Windows/Linux without pip dependency conflicts.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Port 8088 Service Architecture:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Accessible at http://localhost:8088 across local browser sessions.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Robust REST API endpoints (/api/status, /api/sessions, /api/run, /api/stop).\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Server-Sent Events (SSE) Live Streaming:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Endpoint: /api/stream delivers real-time execution progress directly to browser.\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Eliminates aggressive client polling loops; streams byte-level transfer progress.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Autonomous ADB Device Health Poller:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Continuous background health checks tracking device connection, battery %, battery temperature, and available phone storage space.", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.20, w: colW - 0.40, h: colH - 0.40,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column Top: Visual Dashboard Header Mockup
        addCard(slide, 6.88, colY, colW, 1.45, COLOR_SECONDARY, "0F172A");
        slide.addText("RoadSense Telemetry & Script Hub  •  http://127.0.0.1:8088", {
            x: 7.08, y: colY + 0.15, w: colW - 0.40, h: 0.25,
            fontSize: 10, bold: true, color: "38BDF8", fontFace: "Segoe UI", margin: 0
        });

        // 4 Status Badges on the Mockup Header
        const dashBadges = [
            { label: "ADB STATUS", val: "Connected (M21)", col: "10B981" },
            { label: "PHONE BATTERY", val: "55% • 36.2°C", col: "F59E0B" },
            { label: "PHONE STORAGE", val: "42.8 GB Free", col: "38BDF8" },
            { label: "DESKTOP GPU", val: "NVENC Active", col: "A855F7" }
        ];
        dashBadges.forEach((b, idx) => {
            const bx = 7.08 + (idx % 2) * 2.65;
            const by = colY + 0.48 + Math.floor(idx / 2) * 0.42;
            addCard(slide, bx, by, 2.50, 0.36, "334155", "1E293B");
            slide.addText(`${b.label}: ${b.val}`, {
                x: bx + 0.10, y: by + 0.08, w: 2.30, h: 0.20,
                fontSize: 8, bold: true, color: b.col, fontFace: "Segoe UI", margin: 0
            });
        });

        // Right Column Bottom: Designated Dashboard Visual Showcase Placeholder
        addPlaceholder(
            slide,
            6.88, colY + 1.65, colW, 3.50,
            "RoadSense Web Cockpit (http://localhost:8088)",
            "Live browser cockpit interface demonstrating:\n• 1-Click ADB log extraction, verification & MCAP compilation\n• Real-time SSE progress streaming with transfer bitrates\n• Session inventory table with sensor health indicators\n• 1-Click Foxglove desktop player launcher button",
            "Web Dashboard Visual Showcase"
        );
    }

    // =========================================================================
    // SLIDE 7: Foxglove MCAP Archiving & Zero-Cost 3D Orientation
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Data Architecture", "High-Throughput Foxglove MCAP Pipeline (>11,500 FPS)", "Mathematical coordinate alignment eliminating GPU transcoding overhead", 7);

        const colW = 5.65;
        const colY = 1.75;
        const colH = 5.15;

        // Left Column Top Card: Breakthrough & Mathematical /tf Roll Alignment
        addCard(slide, 0.80, colY, colW, 2.05, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Zero-Cost Mathematical /tf Frame Roll Alignment\n\n", options: { fontSize: 12, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• The Hardware Orientation Dilemma: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Android smartphones mounted in landscape record H.264 video with an internal 180° rotation matrix. Rendering this upright in 3D space previously required full video re-encoding.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Zero-Cost Bitstream Demuxing (>11,500 FPS): ", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "session_to_mcap.py inspects the MP4 tkhd matrix in O(1) time and folds 180° into the camera_optical /tf frame roll:\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "  tf_roll = 180.0°   |   tf_pitch = -mounting_pitch   |   tf_yaw = -mounting_yaw\n", options: { fontSize: 8.5, bold: true, color: COLOR_PRIMARY, fontFace: "Consolas" } },
            { text: "• Embedded Layout: ", options: { fontSize: 9.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Auto-embeds RoadSense_Cockpit_Layout.json for instant 3D visualization.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.15, w: colW - 0.40, h: 1.75,
            valign: "top", margin: 0, wrap: true
        });

        // Left Column Bottom: Quantitative Performance Benchmark Matrix
        const benchmarkRows = [
            [
                { text: "Conversion Mode", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Processing Speed", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "1-Min Video", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "GPU Load", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } },
                { text: "Bitstream Quality", options: { fontSize: 8.5, bold: true, color: "FFFFFF", fill: "1E293B" } }
            ],
            [
                { text: "Zero-Cost Demux (Default)", options: { fontSize: 8, bold: true, color: COLOR_SUCCESS } },
                { text: ">11,500 FPS", options: { fontSize: 8, bold: true, color: COLOR_SUCCESS } },
                { text: "~3.4 seconds", options: { fontSize: 8, bold: true, color: COLOR_SUCCESS } },
                { text: "0% GPU (Low CPU)", options: { fontSize: 8, color: COLOR_TEXT_MUTED } },
                { text: "100% Bit-Lossless Copy", options: { fontSize: 8, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "NVENC Transcode (--flip-video)", options: { fontSize: 8, bold: true, color: COLOR_PRIMARY } },
                { text: "~650 FPS", options: { fontSize: 8, bold: true, color: COLOR_PRIMARY } },
                { text: "~32 seconds", options: { fontSize: 8, bold: true, color: COLOR_PRIMARY } },
                { text: "NVENC Dedicated Block", options: { fontSize: 8, color: COLOR_TEXT_MUTED } },
                { text: "High-Bitrate (GOP 30 / IDR)", options: { fontSize: 8, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "CPU Software Transcode", options: { fontSize: 8, color: COLOR_TEXT_SUBTLE } },
                { text: "~85 FPS", options: { fontSize: 8, color: COLOR_TEXT_SUBTLE } },
                { text: "~240 seconds", options: { fontSize: 8, color: COLOR_TEXT_SUBTLE } },
                { text: "100% Multi-Core CPU", options: { fontSize: 8, color: COLOR_TEXT_SUBTLE } },
                { text: "libx264 profile", options: { fontSize: 8, color: COLOR_TEXT_SUBTLE } }
            ]
        ];

        slide.addTable(benchmarkRows, {
            x: 0.80, y: colY + 2.20, w: colW,
            colW: [1.55, 1.05, 0.95, 1.05, 1.05],
            border: { type: "solid", pt: 1, color: "CBD5E1" },
            fill: "F8FAFC",
            valign: "middle"
        });

        // Callout under table
        addCard(slide, 0.80, colY + 4.25, colW, 0.90, "BFDBFE", "EFF6FF");
        slide.addText([
            { text: "PERFORMANCE MULTIPLIER: ", options: { fontSize: 9, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Zero-cost demuxing delivers a 9.4x speedup over NVENC and 70x over CPU encoding, converting 1-hour sessions in under 3.5 minutes on standard laptops.", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.95, y: colY + 4.30, w: colW - 0.30, h: 0.80,
            valign: "middle", margin: 0, wrap: true
        });

        // Right Column: Designated Foxglove 3D Perception Visual Showcase
        addPlaceholder(
            slide,
            6.88, colY, colW, colH,
            "Foxglove Studio 3D Perception Cockpit",
            "Embedded RoadSense_Cockpit_Layout.json:\n• Upright 1080p camera feed with /tf optical roll alignment\n• 3D TI mmWave radar point cloud & Doppler velocity vectors\n• Persistent target tracks with velocity vectors & bounding boxes\n• Monotonic timeline scrubber with synchronized multi-sensor playback",
            "Foxglove 3D Perception Showcase"
        );
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
    // SLIDE 10: Phase 3 — Automated ML Validation & Sensor Fusion
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Strategic Direction • Phase 3", "Automated ML Validation & Multi-Modal Sensor Fusion Engine", "Correlating camera ground truth with radar point clouds to train superior perception models", 10);

        const colW = 3.65;
        const colY = 1.75;
        const colH = 5.15;

        // Pillar 1: Optical Ground Truth & Road Boundary Segmentation
        addCard(slide, 0.80, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Pillar 1: Optical Ground Truth\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Vision-to-3D Road Inversion\n\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },
            { text: "• Dense Semantic Detection:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  YOLOv8x/v11 detects dynamic vehicles (cars, motorcycles, auto-rickshaws, pedestrians) + road contact patches.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Road Boundary Segmentation:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Segments drivable corridor, curbs, guardrails, and roadside clutter (signposts, trees, barriers).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Inverse Pinhole Raycasting:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Projects bounding box road contacts onto ground plane (Z_road = 0) in closed form, calculating exact metric 3D positions (Xc, Yc, Zc).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Replaces ₹35L RTK-DGPS:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Zero incremental hardware cost; establishes ground truth for all surrounding traffic in uncontrolled public driving.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Output: vision_gt.json:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Microsecond synchronized 3D bounding boxes latched to session_timeline.csv.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.15, w: colW - 0.40, h: colH - 0.30,
            valign: "top", margin: 0, wrap: true
        });

        // Pillar 2: Spatial Point-Cloud Correlation & Anomaly Mining
        addCard(slide, 4.84, colY, colW, colH, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "Pillar 2: Point-Cloud Correlation\n", options: { fontSize: 13, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "Clutter vs. Target Discrepancy Mining\n\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },
            { text: "• 6-DOF Spatial Alignment:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Uses calibrated [R|T] and K to project 3.125M radar point clouds and EKF tracks into camera pixel space.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Noise vs. Vehicle Discrimination:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Correlates radar returns with optical objects to determine if reflections are genuine vehicles, multipath ghosts, or roadside clutter.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Self-Supervised Disagreement Rules:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  • Rule 1 (Phantom Ghost): Radar target present & Camera void (multipath ground bounce under trucks/plates).\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "  • Rule 2 (Radar Blindness): Camera target present & Radar void (low-RCS plastic scooters, pedestrians).\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Human-in-the-Loop Active Learning:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Extracts automated 5-second video triage clips (.mp4) for 1-click human verification of discrepant edge cases.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 5.04, y: colY + 0.15, w: colW - 0.40, h: colH - 0.30,
            valign: "top", margin: 0, wrap: true
        });

        // Pillar 3: Superior Fused Multimodal Model (Camera + Radar)
        addCard(slide, 8.88, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "Pillar 3: Fused Multimodal Model\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Surpassing Camera-Only Limits\n\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },
            { text: "• Cross-Modal Sensor Synergy:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Trains a multimodal network fusing radar Doppler velocity and metric depth with camera high-resolution visual semantics.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Surpasses Camera-Only Baseline:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  • Maintains tracking during heavy monsoon rain, thick fog, direct sun glare, and pitch darkness.\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "  • Resolves camera long-range pitch errors (±18m error at >60m resolved by direct radar range).\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Automated Reference Laboratory:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  The trained model serves as the automated ground truth validator, eliminating hundreds of manual video review hours.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• CLEAR MOT & ISO 15623 Benchmarking:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  Automated calculation of MOTA, MOTP, IDF1, and active safety pass/fail compliance scorecards.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 9.08, y: colY + 0.15, w: colW - 0.40, h: colH - 0.30,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 11: Milestone Handover: Phases 1 & 2 Complete, Phase 3 Launch
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Project Handover & Roadmap", "Milestone Handover: Phases 1 & 2 Complete, Phase 3 Launch", "Consolidating on-device perception breakthroughs and launching the validation pipeline", 11);

        const colW = 5.65;
        const colY = 1.75;
        const colH = 5.15;

        // Left Column: Sprint 2 Accomplishments (Phases 1 & 2 Complete)
        addCard(slide, 0.80, colY, colW, colH, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "Sprint 2 Deliverables: 100% Complete\n", options: { fontSize: 13, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "On-Device Perception, Kinematics & Toolchain Foundation\n\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },
            { text: "1. Phase 1: 6-DOF Spatial Calibration Complete (✅):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Full [R|T] extrinsics and Camera2 K intrinsics operational.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Reverse Touch Solver (O(1)) snaps pitch/yaw in <60 seconds without calibration rigs.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Phase 2: Live Viewfinder Overlays Complete (✅):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • RViz-inspired hybrid radar lollipops with 12-segment road contact footprints and vertical stems.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Painter's algorithm depth-sorting (sortByDescending) & staggered range rings (10m–120m).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. 100 Hz LSM6DSL Vehicle Kinematics (✅):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Gravity-free linear acceleration & magnetic-immune 6-DOF orientation with sub-2ms jitter.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. High-Throughput Desktop Toolchain (✅):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Zero-dependency web dashboard (:8088) with real-time SSE progress streaming.\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "   • Zero-cost MCAP demuxing at >11,500 FPS with embedded RoadSense_Cockpit_Layout.json.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "5. Production Engineering Hardening (✅):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Bugs #11 to #15 diagnosed and permanently eliminated; 24/24 JVM unit tests passing clean.", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 1.00, y: colY + 0.15, w: colW - 0.40, h: colH - 0.30,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Phase 3 Immediate Action Items & Sprint Plan
        addCard(slide, 6.88, colY, colW, colH, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Phase 3 Sprint Action Plan\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "Automated ML Validation & Dataset Engine\n\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },
            { text: "1. Offline Optical 3D Raycaster Engine (vision_gt.json):\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Deploy YOLOv8x/v11 on recorded drive sessions to extract 2D vehicle bounding boxes and road plane contact patches (Z_road = 0).\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Radar Point-Cloud & Clutter Correlator:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Correlate radar reflections against optical objects to separate valid vehicle tracks from noise bursts and roadside clutter.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Autonomous Discrepancy Mining Pipeline:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Implement Rules 1–3 to automatically flag phantom radar ghosts and low-RCS blindness into 5-second video triage clips.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "4. Active Learning Web Triage Interface:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Stand up a 1-click review module on the local Web Dashboard (localhost:8088) for rapid human verification of mined edge cases.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "5. Train Multimodal Benchmark Fusion Model:\n", options: { fontSize: 10, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   • Train the fused radar-vision network to automate fleet validation and generate automated CLEAR MOT scorecards.\n\n", options: { fontSize: 9, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "Questions & Technical Discussion", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } }
        ], {
            x: 7.08, y: colY + 0.15, w: colW - 0.40, h: colH - 0.30,
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
