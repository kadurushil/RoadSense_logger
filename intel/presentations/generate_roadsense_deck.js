/**
 * RoadSense Executive Progress Presentation Generator
 * 
 * Generates a 10-slide, 100% native vector PowerPoint presentation (.pptx)
 * adhering to strict professional standards:
 * - Solid crisp white background (#FFFFFF).
 * - Vector architecture boxes, connection lines, and dataflow arrows.
 * - High-contrast automotive engineering typography (Deep Slate, Cobalt Blue, Forest Green).
 * - Standardized image/screenshot placeholder slots with dashed outlines.
 */

import pptxgen from "pptxgenjs";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export async function generateRoadSensePresentation() {
    const pres = new pptxgen();

    // 1. Define Modern 16:9 Widescreen (13.333" x 7.500")
    pres.defineLayout({ name: "SCREEN16x9_FULL", width: 13.333, height: 7.500 });
    pres.layout = "SCREEN16x9_FULL";
    pres.author = "RoadSense Core Engineering Team";
    pres.company = "Bajaj Auto Ltd. - Advanced Perception & Telemetry";
    pres.title = "RoadSense: Autonomous Automotive Multimodal Logger Progress";

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
    const COLOR_LINE = "94A3B8";        // Connecting lines and arrows

    // Helper: Standard Slide Header (Consistent across slides 2-10)
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
            x: 10.93, y: 0.45, w: 1.60, h: 0.36,
            fill: { color: "EFF6FF" },
            line: { color: "BFDBFE", width: 1 },
            rectRadius: 0.05
        });
        slide.addText("ROADSENSE ADAS", {
            x: 10.93, y: 0.45, w: 1.60, h: 0.36,
            fontSize: 9, bold: true, color: COLOR_PRIMARY, align: "center", fontFace: "Segoe UI",
            valign: "middle", margin: 0
        });

        // Header Divider Rule
        slide.addShape(pres.ShapeType.line, {
            x: 0.80, y: 1.52, w: 11.733, h: 0,
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
            { text: desc, options: { fontSize: 10, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } }
        ], {
            x: x + 0.20, y: y + 0.30, w: w - 0.40, h: h - 0.60,
            align: "center", valign: "middle", margin: 0
        });
    }

    // =========================================================================
    // SLIDE 1: Title & Executive Mission
    // =========================================================================
    {
        const slide = pres.addSlide();
        slide.background = { color: COLOR_BG };

        // Top Accent Bar
        slide.addShape(pres.ShapeType.rect, {
            x: 0.80, y: 0.60, w: 11.733, h: 0.08,
            fill: { color: COLOR_PRIMARY }
        });

        // Hero Category
        slide.addText("EXECUTIVE TECHNICAL PROGRESS REPORT | SEPTEMBER 2026", {
            x: 0.80, y: 0.85, w: 10.00, h: 0.30,
            fontSize: 11, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI",
            margin: 0
        });

        // Hero Title
        slide.addText("RoadSense: Multimodal Automotive ADAS Logger", {
            x: 0.80, y: 1.20, w: 11.733, h: 0.70,
            fontSize: 28, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI",
            margin: 0
        });

        // Hero Subtitle
        slide.addText("Hardware-Synchronized Radar, Vision, GNSS & CAN Ingestion Cockpit for Vehicle Dynamics & Perception Research", {
            x: 0.80, y: 1.95, w: 11.733, h: 0.35,
            fontSize: 13, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI",
            margin: 0
        });

        // 4 Sensor Modality Highlights (4 Horizontal Cards)
        const sensorStreams = [
            { name: "77 GHz Radar", metric: "3.125 Mbps", desc: "TI AWR1843BOOST mmWave raw point clouds & EKF tracks at 20 Hz", color: COLOR_PRIMARY },
            { name: "Camera2 Engine", metric: "1080p @ 30 FPS", desc: "Hyperfocal infinity lock, tap AF/AE, autonomous Road AE", color: COLOR_SECONDARY },
            { name: "CANedge2 Bus", metric: "2x CAN / CAN-FD", desc: "Autonomous 60s split MF4 staging, Wi-Fi sync & smart FIFO pruner", color: COLOR_SUCCESS },
            { name: "GNSS / GPS", metric: "1 Hz Fused + NMEA", desc: "Nanosecond-indexed latitude, longitude, speed & heading track", color: COLOR_WARNING }
        ];

        sensorStreams.forEach((s, i) => {
            const cardW = 2.70;
            const cardX = 0.80 + i * (cardW + 0.31);
            addCard(slide, cardX, 2.55, cardW, 2.30, s.color);

            slide.addText([
                { text: `${s.name}\n`, options: { fontSize: 13, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: `${s.metric}\n\n`, options: { fontSize: 16, bold: true, color: s.color, fontFace: "Segoe UI" } },
                { text: s.desc, options: { fontSize: 10, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI", lineSpacing: 14 } }
            ], {
                x: cardX + 0.20, y: 2.75, w: cardW - 0.40, h: 1.90,
                valign: "top", margin: 0, wrap: true
            });
        });

        // Executive Summary Bottom Card & Setup Placeholder
        addCard(slide, 0.80, 5.10, 6.80, 1.80, "BFDBFE", "EFF6FF");
        slide.addText([
            { text: "Core Engineering Achievement to Date\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Unified Cockpit: Single Android smartphone acts as an autonomous multi-sensor edge recorder.\n", options: { fontSize: 10.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Microsecond Alignment: Common monotonic time base eliminates cross-sensor clock drift.\n", options: { fontSize: 10.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Production Grade: Zero dropped radar packets, non-blocking Wi-Fi sync, and continuous diagnostics.", options: { fontSize: 10.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } }
        ], {
            x: 1.05, y: 5.25, w: 6.30, h: 1.50,
            valign: "top", margin: 0, wrap: true
        });

        addPlaceholder(slide, 7.80, 5.10, 4.733, 1.80, "Vehicle Rig & Cockpit Mount", "Insert photo of test vehicle with bumper radar and windshield smartphone clamp.", "Hardware Rig Photo");
    }

    // =========================================================================
    // SLIDE 2: End-to-End System Architecture (Vector Boxes & Flow Lines)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "System Architecture", "End-to-End Multimodal Data Pipeline", "From physical vehicle hardware to Android edge ingestion and PC analytics", 2);

        // 4 Pipeline Stages (Columns)
        const stages = [
            {
                title: "1. Sensor Layer",
                sub: "Automotive Hardware",
                color: COLOR_PRIMARY,
                items: ["• TI AWR1843 Radar (77 GHz)", "• Smartphone Camera2 Sensor", "• CSS Electronics CANedge2", "• Fused Multi-GNSS Constellation"]
            },
            {
                title: "2. Ingestion Engine",
                sub: "Android Background Services",
                color: COLOR_SECONDARY,
                items: ["• 3.125 Mbps FTDI USB UART", "• Non-blocking TextureView Preview", "• Sequential HTTP REST / Wi-Fi AP", "• Monotonic Clock: elapsedRealtimeNanos"]
            },
            {
                title: "3. Session Storage",
                sub: "On-Device Monotonic Index",
                color: COLOR_SUCCESS,
                items: ["• session_timeline.csv (Unified Spine)", "• radar_raw_stream.bin & frames.bin", "• camera_video.mp4 & frames.csv", "• can/*.MF4 (60s Split Chunks)"]
            },
            {
                title: "4. PC Analytics",
                sub: "ADB Extraction & Visualization",
                color: COLOR_WARNING,
                items: ["• Automated sync_and_process_logs.bat", "• Nearest-neighbor time interpolation", "• Web-based BEV Radar/Video Player", "• 7 Standalone diagnostic health tools"]
            }
        ];

        stages.forEach((stg, i) => {
            const colW = 2.65;
            const colX = 0.80 + i * (colW + 0.38);
            
            // Container Box
            addCard(slide, colX, 1.85, colW, 4.40, stg.color);

            // Column Header
            slide.addShape(pres.ShapeType.rect, {
                x: colX, y: 1.85, w: colW, h: 0.65,
                fill: { color: stg.color }
            });
            slide.addText([
                { text: `${stg.title}\n`, options: { fontSize: 11, bold: true, color: "FFFFFF", fontFace: "Segoe UI" } },
                { text: stg.sub, options: { fontSize: 8.5, color: "E0F2FE", fontFace: "Segoe UI" } }
            ], {
                x: colX + 0.10, y: 1.95, w: colW - 0.20, h: 0.50,
                align: "center", valign: "top", margin: 0
            });

            // Column Bullets
            slide.addText(stg.items.join("\n\n"), {
                x: colX + 0.20, y: 2.75, w: colW - 0.40, h: 3.30,
                fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI",
                valign: "top", margin: 0, lineSpacing: 14
            });

            // Connecting Arrow to next stage
            if (i < 3) {
                const arrowX = colX + colW + 0.05;
                slide.addShape(pres.ShapeType.rightArrow, {
                    x: arrowX, y: 3.80, w: 0.28, h: 0.35,
                    fill: { color: COLOR_LINE },
                    line: { color: COLOR_LINE }
                });
            }
        });

        // Bottom Callout Card
        addCard(slide, 0.80, 6.45, 11.733, 0.60, "CBD5E1", "F8FAFC");
        slide.addText("Deterministic Decoupling: Ingestion threads run on isolated IO and Default dispatchers to guarantee that heavy UI rendering or CAN HTTP downloads never block real-time 3.125 Mbps radar serial processing.", {
            x: 1.00, y: 6.55, w: 11.333, h: 0.40,
            fontSize: 9.5, bold: true, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI", margin: 0
        });
    }

    // =========================================================================
    // SLIDE 3: Monotonic Nanosecond Cross-Sensor Synchronization
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Core Innovation", "Monotonic Cross-Sensor Nanosecond Synchronization", "Solving physical clock domain jitter across Radar, Video, GNSS, and CAN", 3);

        // Left Column: The Clock Challenge & Solution
        addCard(slide, 0.80, 1.75, 5.65, 5.05);
        slide.addText([
            { text: "The Clock Domain Challenge in ADAS\n\n", options: { fontSize: 14, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Wall-Clock Flaws: System.currentTimeMillis() is prone to cellular NTP step adjustments, daylight savings shifts, and non-monotonic discontinuities. It CANNOT be used for sensor fusion.\n\n", options: { fontSize: 10.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• The Monotonic Guarantee: RoadSense uses SystemClock.elapsedRealtimeNanos(). It ticks continuously from CPU boot, has microsecond resolution, and NEVER steps backwards.\n\n", options: { fontSize: 10.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Unified Spine: session_timeline.csv records every cross-sensor event with an exact nanosecond monotonic index:\n\n", options: { fontSize: 10.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "  - [t0 + 35ms] RADAR_FRAME #1 (32 points, 2 tracks)\n  - [t0 + 40ms] CAMERA_FRAME #1 (PTS us -> Mono ns)\n  - [t0 + 90ms] GNSS_FIX #1 (Lat, Lon, Speed, Bearing)\n  - [t0 + 60s]  CAN_MF4_CHUNK (00000041_00000051.MF4)", options: { fontSize: 9.5, color: COLOR_PRIMARY, fontFace: "Consolas" } }
        ], {
            x: 1.05, y: 1.95, w: 5.15, h: 4.65,
            valign: "top", margin: 0, wrap: true
        });

        // Right Column: Monotonic Sequence Diagram & Visualizer Slot
        addPlaceholder(slide, 6.88, 1.75, 5.65, 5.05, "Timeline CSV Sync & Alignment Plot", "Insert plot or timeline diagram showing nanosecond nearest-neighbor interpolation between radar and camera frames.", "Sync Alignment Plot");
    }

    // =========================================================================
    // SLIDE 4: TI mmWave Radar Subsystem (3.125 Mbps High-Speed Driver)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Perception Subsystem", "TI mmWave Radar @ 3,125,000 Baud", "Zero-loss high-speed USB serial UART driver, TLV parser, and EKF hardware tracking", 4);

        // 3 Feature Cards
        const radarFeatures = [
            {
                title: "3.125 Mbps UART Pipeline",
                badge: "Zero-Copy Ring Buffer",
                color: COLOR_PRIMARY,
                desc: "Custom FTDI/CDC serial driver configured for 3.125 Mbps. Handles sustained 15 MB/min binary bursts with zero dropped bytes or framing corruptions."
            },
            {
                title: "Real-Time TLV Packet Parser",
                badge: "20 Hz Point Clouds",
                color: COLOR_SECONDARY,
                desc: "Decodes TI mmWave TLV binary streams in real time: Type 1 (Cartesian X, Y, Z + Doppler Velocity + SNR) and Type 7 (Hardware EKF Track IDs & Pos/Vel vectors)."
            },
            {
                title: "Dual Binary Session Logging",
                badge: "100% Bitstream Fidelity",
                color: COLOR_SUCCESS,
                desc: "Simultaneously writes raw UART byte streams (radar_raw_stream.bin) for replaying in TI mmWave Studio and structured frames (radar_frames.bin) for fast analytics."
            }
        ];

        radarFeatures.forEach((rf, i) => {
            const cardW = 3.65;
            const cardX = 0.80 + i * (cardW + 0.39);
            addCard(slide, cardX, 1.75, cardW, 2.45, rf.color);

            slide.addText([
                { text: `${rf.badge.toUpperCase()}\n`, options: { fontSize: 9, bold: true, color: rf.color, fontFace: "Segoe UI" } },
                { text: `${rf.title}\n\n`, options: { fontSize: 13, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: rf.desc, options: { fontSize: 10, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI", lineSpacing: 14 } }
            ], {
                x: cardX + 0.20, y: 1.95, w: cardW - 0.40, h: 2.05,
                valign: "top", margin: 0, wrap: true
            });
        });

        // Bottom Half: Specifications Table & BEV Canvas Placeholder
        addCard(slide, 0.80, 4.45, 6.00, 2.35);
        slide.addText([
            { text: "Radar Driver Technical Specifications\n\n", options: { fontSize: 12, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Sensor Model: Texas Instruments AWR1843BOOST (77-81 GHz)\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• CLI Control Baud: 115,200 bps | Data Stream Baud: 3,125,000 bps\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Frame Rate: 20 Hz (50 ms frame periodicity)\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Max Range: 50.0 meters | Velocity Resolution: 0.15 m/s\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Azimuth FOV: ±60° (120° total) | Elevation FOV: ±15°", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } }
        ], {
            x: 1.05, y: 4.60, w: 5.50, h: 2.05,
            valign: "top", margin: 0, wrap: true
        });

        addPlaceholder(slide, 7.15, 4.45, 5.383, 2.35, "Radar Bird's-Eye View (BEV) Display", "Insert screenshot of the real-time Radar Canvas showing point clusters and colored velocity tracks.", "Radar BEV Canvas Slot");
    }

    // =========================================================================
    // SLIDE 5: Camera2 Vision Engine & Autonomous Road AE
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Vision Subsystem", "Camera2 Engine & Autonomous Road AE", "Eliminating sky-bloom, windshield focus hunting, and preview touch glitches", 5);

        // 3 Column Cards
        const cameraCards = [
            {
                title: "Viewfinder Continuity",
                tag: "Zero-Glitch Lifecycle",
                color: COLOR_PRIMARY,
                items: [
                    "• Problem: UI tab swipes previously triggered transient recomposition tearing down preview surfaces.",
                    "• Solution: Decoupled viewfinder lifecycle from compose gestures; surfaces remain pinned to Camera HAL session.",
                    "• Result: Smooth, flicker-free tab navigation."
                ]
            },
            {
                title: "Hyperfocal Infinity Lock",
                tag: "Auto-Lock on Record",
                color: COLOR_SECONDARY,
                items: [
                    "• Problem: Windshield rain, reflections, and dust trick AF into hunting and focusing on glass.",
                    "• Solution: Smart Infinity Focus locks lens distance (LENS_FOCUS_DISTANCE = 0.0f).",
                    "• Interactive Tap AF/AE: User tap targets license plates with dedicated yellow reticle overlay."
                ]
            },
            {
                title: "Autonomous Road AE",
                tag: "Dual-Zone Photometrics",
                color: COLOR_SUCCESS,
                items: [
                    "• Problem: Bright daylight sky overexposes and drives camera ISP to plunge road surface into darkness.",
                    "• Approach 3: 4 Hz non-blocking 32x24 thumbnail analyzer computes sky-to-road contrast ratio R.",
                    "• Dynamic State: When R >= 1.8x, locks AE metering to bottom 65% + adds EV boost."
                ]
            }
        ];

        cameraCards.forEach((cc, i) => {
            const cardW = 3.65;
            const cardX = 0.80 + i * (cardW + 0.39);
            addCard(slide, cardX, 1.75, cardW, 2.70, cc.color);

            slide.addText([
                { text: `${cc.tag.toUpperCase()}\n`, options: { fontSize: 9, bold: true, color: cc.color, fontFace: "Segoe UI" } },
                { text: `${cc.title}\n\n`, options: { fontSize: 13, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: cc.items.join("\n\n"), options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI", lineSpacing: 13 } }
            ], {
                x: cardX + 0.20, y: 1.95, w: cardW - 0.40, h: 2.30,
                valign: "top", margin: 0, wrap: true
            });
        });

        // Bottom Placeholder & Status Badge Pill Detail
        addCard(slide, 0.80, 4.65, 5.65, 2.15);
        slide.addText([
            { text: "Road AE Dynamic State Transitions\n\n", options: { fontSize: 12, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• SKY_BLOOM (R >= 1.8x): Bottom 65% road metering + 0.5 EV boost (Status: • ROAD AE ☀️)\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• BALANCED (0.9x <= R < 1.8x): Full-frame matrix metering (Status: • ROAD AE)\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• NIGHT_TUNNEL (R < 0.9x): Sky rejection disabled to protect headlight visibility\n", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• TAP_LOCKED: User manual lock holds priority until [Reset Lock] is pressed.", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } }
        ], {
            x: 1.05, y: 4.80, w: 5.15, h: 1.85,
            valign: "top", margin: 0, wrap: true
        });

        addPlaceholder(slide, 6.88, 4.65, 5.65, 2.15, "Viewfinder UI with Road AE Badge", "Insert screenshot showing live camera preview with • ROAD AE ☀️ badge and focus controls.", "Camera Deck Screenshot");
    }

    // =========================================================================
    // SLIDE 6: CANedge2 Dual-Channel CAN Ingestion & Pruner
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "CAN Subsystem", "CANedge2 Dual-Channel Ingestion & Smart Pruner", "Autonomous Wi-Fi AP sync, 60s split MF4 handling, and single-connection protection", 6);

        // Left Side: Ingestion Architecture
        addCard(slide, 0.80, 1.75, 5.65, 5.05);
        slide.addText([
            { text: "CANedge2 Autonomous Ingestion Engine\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Cyclic 60s MF4 Splitting: CANedge2 continuously writes 1-minute split MF4 (MDF4) files over CAN1 (500 kbps) and CAN2 (CAN-FD).\n\n", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• 2-Folder Staging Pool (canedge_pool/): Maintains an active staging buffer of the two most recent folders, eliminating download bottlenecks during recording.\n\n", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Single-Connection MCU Protection (Bug #10 Solved): CANedge's ESP32 web server crashes under concurrent polling. RoadSense enforces sequential non-blocking downloads with 1.5s backoffs.\n\n", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Smart Auto-Pruner: Background FIFO cleans orphaned chunks while rescuing drive session files into permanent storage.", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } }
        ], {
            x: 1.05, y: 1.95, w: 5.15, h: 4.65,
            valign: "top", margin: 0, wrap: true
        });

        // Right Side: CAN File Explorer Placeholder
        addPlaceholder(slide, 6.88, 1.75, 5.65, 5.05, "CANedge 3-Column File Explorer Deck", "Insert screenshot of the CANedge dashboard showing the nested directory tree and synchronized file indicators.", "CANedge Explorer Screenshot");
    }

    // =========================================================================
    // SLIDE 7: Cockpit UI Suite & Continuous Flight Recorder
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Cockpit UX & Reliability", "Cockpit UI Suite & Continuous Flight Recorder", "6 dedicated telemetry tabs and dual-stream diagnostic logging", 7);

        // Left Side: Cockpit Tabs Breakdown
        addCard(slide, 0.80, 1.75, 5.65, 5.05);
        slide.addText([
            { text: "Multi-Tab Compose Cockpit Architecture\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Radar Tab: Real-time BEV canvas, point cloud density, EKF track list, and CLI configuration terminal.\n\n", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Side-by-Side (SBS) Tab: Synchronized dual-feed placing the 1080p camera viewfinder beside the 20 Hz Radar BEV display.\n\n", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Camera Tab: Lens switching (Wide, Ultra-Wide), resolution/FPS controls, infinity lock, and Road AE controls.\n\n", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• CANedge Tab: Wi-Fi AP status, staging pool health, and OneDrive-style 3-column file manager.\n\n", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "• Continuous Flight Recorder: Continuous app_system.log logs idle discovery and UI events; session_debug.log records active drives.", options: { fontSize: 10, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } }
        ], {
            x: 1.05, y: 1.95, w: 5.15, h: 4.65,
            valign: "top", margin: 0, wrap: true
        });

        // Right Side: SBS Cockpit Tab Placeholder
        addPlaceholder(slide, 6.88, 1.75, 5.65, 5.05, "Side-by-Side (SBS) Synchronized Cockpit", "Insert screenshot of the SBS Cockpit tab showing live camera preview and radar point cloud running concurrently.", "SBS Cockpit Screenshot");
    }

    // =========================================================================
    // SLIDE 8: PC Extraction & Offline Python Validation Pipeline
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Pipeline & Tooling", "PC Extraction & Offline Validation Tools", "Automated ADB sync, web visualizer player, and 7 standalone health-check scripts", 8);

        // 4 Tools Cards (2x2 Grid)
        const tools = [
            {
                title: "sync_and_process_sessions.py",
                badge: "Automated ADB Pipeline",
                color: COLOR_PRIMARY,
                desc: "One-click extraction: pulls completed sessions from phone, validates file integrity, and compiles multimodal data into web visualizer JSON."
            },
            {
                title: "Browser-Based Visualizer",
                badge: "Interactive Playback",
                color: COLOR_SECONDARY,
                desc: "Full browser UI with synchronized video scrubbing, 20 Hz radar point clouds, ground-truth speed graphs, and telemetry overlays."
            },
            {
                title: "session_health_check.py",
                badge: "Session Diagnostics",
                color: COLOR_SUCCESS,
                desc: "Audits folder structures, checks cross-sensor timeline continuity, verifies MF4 byte strides, and flags missing frames or dropped packets."
            },
            {
                title: "audit_cross_sensor_sync.py",
                badge: "Microsecond Auditing",
                color: COLOR_WARNING,
                desc: "Calculates cross-sensor latency offsets between radar frame headers and camera exposure PTS timestamps down to microsecond precision."
            }
        ];

        tools.forEach((t, i) => {
            const col = i % 2;
            const row = Math.floor(i / 2);
            const cardW = 3.65;
            const cardH = 2.35;
            const cardX = 0.80 + col * (cardW + 0.30);
            const cardY = 1.75 + row * (cardH + 0.35);

            addCard(slide, cardX, cardY, cardW, cardH, t.color);

            slide.addText([
                { text: `${t.badge.toUpperCase()}\n`, options: { fontSize: 8.5, bold: true, color: t.color, fontFace: "Segoe UI" } },
                { text: `${t.title}\n\n`, options: { fontSize: 11.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: t.desc, options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI", lineSpacing: 13 } }
            ], {
                x: cardX + 0.20, y: cardY + 0.20, w: cardW - 0.40, h: cardH - 0.40,
                valign: "top", margin: 0, wrap: true
            });
        });

        // Right Placeholder for Visualizer Screenshot
        addPlaceholder(slide, 8.70, 1.75, 3.833, 5.05, "Web Visualizer Playback Deck", "Insert screenshot of the browser-based multimodal visualizer showing synced radar and video.", "Web Visualizer Screenshot");
    }

    // =========================================================================
    // SLIDE 9: The Next Frontier: Radar-Camera Sensor Fusion (FUSION_STEPS)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Future Roadmap", "The Next Frontier: Radar-Camera Sensor Fusion", "Projecting metric 3D radar detections onto the 2D video viewfinder (FUSION_STEPS)", 9);

        // 3 Phase Pillars
        const fusionPhases = [
            {
                phase: "Phase 1: Spatial Calibration",
                sub: "Immediate Priority",
                color: COLOR_PRIMARY,
                items: [
                    "• Pinhole Intrinsics (K): Extracted via Camera2 API (fx, fy, cx, cy).",
                    "• 6-DOF Extrinsics [R | T]: Rigid rotation (pitch, yaw, roll) & translation offsets.",
                    "• Keystone HUD: Interactive on-screen sliders and +/- 0.1° nudge buttons.",
                    "• Target Pin & Snap: Drag floating radar reticle onto parked vehicle to auto-solve."
                ]
            },
            {
                phase: "Phase 2: Viewfinder Fusion",
                sub: "ADAS Overlay",
                color: COLOR_SECONDARY,
                items: [
                    "• Doppler Point Overlay: Real-time 20 Hz projection colored by radial velocity (Cyan/Green/Red).",
                    "• 3D Bounding Boxes: Perspective cuboids anchored to ground plane (Z = 0).",
                    "• Telemetry Pill: Floating ID, distance, speed, and Time-to-Collision (TTC).",
                    "• FCW Warning: Visual border flash when TTC < 2.0 seconds."
                ]
            },
            {
                phase: "Phase 3: Semantic AI Fusion",
                sub: "Deep Association",
                color: COLOR_SUCCESS,
                items: [
                    "• On-Device 2D Detection: Lightweight YOLOv8-nano running on NPU via TFLite.",
                    "• IoU & Mahalanobis Gating: Associating 2D vision boxes with 3D radar tracks.",
                    "• Complementary Advantage: Vision provides semantic class; Radar provides exact depth & speed."
                ]
            }
        ];

        fusionPhases.forEach((fp, i) => {
            const cardW = 3.65;
            const cardX = 0.80 + i * (cardW + 0.39);
            addCard(slide, cardX, 1.75, cardW, 4.40, fp.color);

            slide.addShape(pres.ShapeType.rect, {
                x: cardX, y: 1.75, w: cardW, h: 0.65,
                fill: { color: fp.color }
            });
            slide.addText([
                { text: `${fp.phase}\n`, options: { fontSize: 11, bold: true, color: "FFFFFF", fontFace: "Segoe UI" } },
                { text: fp.sub, options: { fontSize: 8.5, color: "E0F2FE", fontFace: "Segoe UI" } }
            ], {
                x: cardX + 0.10, y: 1.85, w: cardW - 0.20, h: 0.50,
                align: "center", valign: "top", margin: 0
            });

            slide.addText(fp.items.join("\n\n"), {
                x: cardX + 0.20, y: 2.65, w: cardW - 0.40, h: 3.30,
                fontSize: 9.5, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI",
                valign: "top", margin: 0, lineSpacing: 14
            });
        });

        // Bottom Callout
        addCard(slide, 0.80, 6.35, 11.733, 0.70, "CBD5E1", "EFF6FF");
        slide.addText("Flat-Earth Road-Plane Anchor: Enforcing a ground-plane constraint (Z_road = 0) overcomes the coarse elevation resolution of standard mmWave radar, preventing objects from falsely floating in the sky.", {
            x: 1.00, y: 6.45, w: 11.333, h: 0.50,
            fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI", margin: 0
        });
    }

    // =========================================================================
    // SLIDE 10: Milestones Summary & Trajectory
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(slide, "Executive Summary", "Project Milestones, Quality Metrics & Next Steps", "Consolidated deliverables, automated verification status, and upcoming roadmap", 10);

        // Left Side: Metrics Table
        const tableRows = [
            [
                { text: "Subsystem / Feature", options: { bold: true, color: "FFFFFF", fill: { color: COLOR_PRIMARY }, fontSize: 10 } },
                { text: "Engineering Status", options: { bold: true, color: "FFFFFF", fill: { color: COLOR_PRIMARY }, fontSize: 10 } },
                { text: "Verified Performance Metric", options: { bold: true, color: "FFFFFF", fill: { color: COLOR_PRIMARY }, fontSize: 10 } }
            ],
            [
                { text: "TI mmWave Radar", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN } },
                { text: "Production Ready", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS } },
                { text: "3.125 Mbps sustained; 0 dropped packets; 20 Hz", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "Monotonic Sync Spine", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN } },
                { text: "Production Ready", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS } },
                { text: "Microsecond alignment in session_timeline.csv", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "Camera2 Road AE & Focus", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN } },
                { text: "Production Ready", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS } },
                { text: "4 Hz dual-zone photometric analysis; auto infinity", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "CANedge2 Ingestion", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN } },
                { text: "Production Ready", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS } },
                { text: "2-folder pool; single-connection backoff; FIFO pruner", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "Continuous Flight Recorder", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN } },
                { text: "Production Ready", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS } },
                { text: "Continuous idle logging (app_system.log)", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED } }
            ],
            [
                { text: "Automated JVM Testing", options: { fontSize: 9.5, color: COLOR_TEXT_MAIN } },
                { text: "100% Pass Rate", options: { fontSize: 9.5, bold: true, color: COLOR_SUCCESS } },
                { text: "All unit tests clean; zero regression in master", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED } }
            ]
        ];

        slide.addTable(tableRows, {
            x: 0.80, y: 1.75, w: 6.80,
            colW: [2.00, 1.60, 3.20],
            border: { type: "solid", pt: 1, color: "CBD5E1" },
            fill: "F8FAFC",
            valign: "middle"
        });

        // Right Side: Immediate Next Steps Card
        addCard(slide, 7.85, 1.75, 4.683, 5.05, COLOR_PRIMARY);
        slide.addText([
            { text: "Immediate Next Steps & Roadmap\n\n", options: { fontSize: 13, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "1. Phase 1 Radar-Camera Calibration:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Implement Keystone HUD with sliders for pitch/yaw and build the Target Pin & Snap solver for 60-second field setup.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Live Viewfinder Overlay (Phase 2):\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Render real-time Doppler color-coded radar points and 3D cuboids onto the active Camera2 TextureView.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Native Kotlin DBC Decoding:\n", options: { fontSize: 10.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Extract vehicle speed and steering angle directly from CANedge MF4 files on the phone for ego-motion compensation.\n\n", options: { fontSize: 9.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "Questions & Technical Discussion", options: { fontSize: 11, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } }
        ], {
            x: 8.05, y: 1.95, w: 4.283, h: 4.65,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 11: System Architecture & Hardware Topology (Master Showcase)
    // =========================================================================
    {
        const slide = pres.addSlide();
        slide.background = { color: COLOR_BG };

        // 1. Top-Left Logo / Banner
        slide.addShape(pres.ShapeType.line, {
            x: 0.80, y: 0.50, w: 0.08, h: 1.10,
            line: { color: "0284C7", width: 4 }
        });
        slide.addText([
            { text: "ROAD", options: { fontSize: 24, bold: true, color: "0F172A", fontFace: "Segoe UI" } },
            { text: "SENSE\n", options: { fontSize: 24, bold: true, color: "0284C7", fontFace: "Segoe UI" } },
            { text: "MOBILE ADAS DATA LOGGER\n", options: { fontSize: 11, bold: true, color: "334155", fontFace: "Segoe UI" } },
            { text: "SYNC  •  RECORD  •  VISUALIZE  •  ANALYZE", options: { fontSize: 8.5, bold: true, color: "64748B", fontFace: "Segoe UI" } }
        ], {
            x: 0.95, y: 0.45, w: 3.50, h: 1.15,
            valign: "top", margin: 0
        });

        // 2. Top Center Card: TI AWR1843BOOST 77 GHz mmWave Radar
        addCard(slide, 4.40, 0.45, 5.00, 2.05, "38BDF8", "F0F9FF");
        slide.addText([
            { text: "TI AWR1843BOOST\n", options: { fontSize: 14, bold: true, color: "0F172A", fontFace: "Segoe UI" } },
            { text: "77 GHz mmWave Radar\n\n", options: { fontSize: 11, color: "0369A1", fontFace: "Segoe UI" } },
            { text: "• Raw Point Clouds & Micro-Doppler Velocity\n", options: { fontSize: 9.5, color: "1E293B", fontFace: "Segoe UI" } },
            { text: "• Hardware EKF Target Tracks & Clusters\n", options: { fontSize: 9.5, color: "1E293B", fontFace: "Segoe UI" } },
            { text: "• Dual UART: 3.125 Mbps Data + 115.2 kbps CLI", options: { fontSize: 9.5, color: "1E293B", fontFace: "Segoe UI" } }
        ], {
            x: 6.30, y: 0.60, w: 2.95, h: 1.75,
            valign: "top", margin: 0, wrap: true
        });
        // Embedded Radar Board Placeholder
        addCard(slide, 4.60, 0.65, 1.55, 1.65, "BAE6FD", "E0F2FE");
        slide.addText("[ RADAR BOARD ]\nAWR1843BOOST\nAntenna Array", {
            x: 4.60, y: 1.05, w: 1.55, h: 0.85,
            fontSize: 8.5, bold: true, color: "0369A1", align: "center", fontFace: "Segoe UI", margin: 0
        });

        // Downward Arrow from Radar to Phone
        slide.addShape(pres.ShapeType.downArrow, {
            x: 6.60, y: 2.55, w: 0.35, h: 0.50,
            fill: { color: "0284C7" },
            line: { color: "0284C7" }
        });
        slide.addText("High-Speed USB Serial UART (3.125 Mbps)", {
            x: 7.05, y: 2.62, w: 3.20, h: 0.35,
            fontSize: 9, bold: true, color: "0284C7", fontFace: "Segoe UI", margin: 0
        });

        // 3. Left Card: CSS Electronics CANedge2
        addCard(slide, 0.80, 2.85, 2.80, 3.45, "0284C7", "F0F9FF");
        slide.addText([
            { text: "CSS Electronics\n", options: { fontSize: 10, color: "0369A1", fontFace: "Segoe UI" } },
            { text: "CANedge2\n", options: { fontSize: 14, bold: true, color: "0F172A", fontFace: "Segoe UI" } },
            { text: "(Dual-Channel CAN / CAN-FD)\n\n", options: { fontSize: 9.5, color: "475569", fontFace: "Segoe UI" } }
        ], {
            x: 0.90, y: 2.95, w: 2.60, h: 0.90,
            align: "center", valign: "top", margin: 0
        });
        // Hardware Device Box
        addCard(slide, 1.00, 3.85, 2.40, 1.35, "BAE6FD", "E0F2FE");
        slide.addText("[ CANedge2 HARDWARE ]\nDual DB9 Enclosure\nSD Card Logging", {
            x: 1.00, y: 4.15, w: 2.40, h: 0.75,
            fontSize: 8.5, bold: true, color: "0369A1", align: "center", fontFace: "Segoe UI", margin: 0
        });
        addCard(slide, 0.95, 5.30, 2.50, 0.85, "CBD5E1", "FFFFFF");
        slide.addText("OBD-II / Vehicle Powertrain Bus,\nCyclic 1-min MF4 Split Logging", {
            x: 1.00, y: 5.40, w: 2.40, h: 0.65,
            fontSize: 8.5, color: "334155", align: "center", fontFace: "Segoe UI", margin: 0
        });

        // Arrow from CANedge2 to Phone
        slide.addShape(pres.ShapeType.rightArrow, {
            x: 3.65, y: 4.40, w: 0.45, h: 0.35,
            fill: { color: "0284C7" },
            line: { color: "0284C7" }
        });
        slide.addText("2.4 GHz Wi-Fi AP\nHTTP REST Sync", {
            x: 2.95, y: 3.85, w: 1.80, h: 0.50,
            fontSize: 8.5, bold: true, color: "0284C7", align: "center", fontFace: "Segoe UI", margin: 0
        });

        // 4. Center Main Device: Smartphone Cockpit Frame (Phone Mockup)
        addCard(slide, 4.25, 3.15, 5.30, 2.90, "0F172A", "0F172A");
        addCard(slide, 4.35, 3.25, 5.10, 2.70, "1E293B", "1E293B");
        // Status bar on phone
        slide.addText("16:14  •  RADAR 20.0 Hz  CAM 30.0 fps  GPS 1.0 Hz       CPU 13% | BAT 55% 36.2°C", {
            x: 4.45, y: 3.32, w: 4.90, h: 0.20,
            fontSize: 7.5, color: "94A3B8", fontFace: "Consolas", margin: 0
        });
        // Left viewport: Live Camera View
        addCard(slide, 4.45, 3.58, 2.40, 2.25, "334155", "0284C7");
        slide.addText("[ CAMERA FEED ]\n1080p Viewfinder\n• ROAD AE ☀️ Active\nHyperfocal Infinity", {
            x: 4.45, y: 4.20, w: 2.40, h: 1.00,
            fontSize: 8.5, bold: true, color: "FFFFFF", align: "center", fontFace: "Segoe UI", margin: 0
        });
        // Right viewport: Radar BEV
        addCard(slide, 6.95, 3.58, 2.40, 2.25, "334155", "0B132B");
        slide.addText("[ RADAR BEV SCOPE ]\n• 60m Scope Rings\n• Doppler Clusters\n• Track IDs & TTC", {
            x: 6.95, y: 4.20, w: 2.40, h: 1.00,
            fontSize: 8.5, bold: true, color: "38BDF8", align: "center", fontFace: "Segoe UI", margin: 0
        });

        // 5. Right Card: Built-in Smartphone Sensors
        addCard(slide, 9.85, 2.85, 2.683, 3.45, "A855F7", "FAF5FF");
        slide.addText("Built-in Smartphone Sensors", {
            x: 9.95, y: 2.95, w: 2.483, h: 0.35,
            fontSize: 11, bold: true, color: "6B21A8", align: "center", fontFace: "Segoe UI", margin: 0
        });

        // Camera sub-box
        addCard(slide, 10.00, 3.40, 2.383, 1.30, "E9D5FF", "FFFFFF");
        slide.addText([
            { text: "Camera (Camera2 API)\n", options: { fontSize: 10, bold: true, color: "6B21A8", fontFace: "Segoe UI" } },
            { text: "1080p H.264 MP4 Stream with Nanosecond Frame Shutter Timestamps", options: { fontSize: 8.5, color: "475569", fontFace: "Segoe UI" } }
        ], {
            x: 10.10, y: 3.50, w: 2.183, h: 1.10,
            valign: "top", margin: 0, wrap: true
        });

        // GPS sub-box
        addCard(slide, 10.00, 4.85, 2.383, 1.30, "A7F3D0", "FFFFFF");
        slide.addText([
            { text: "GPS / GNSS (Location)\n", options: { fontSize: 10, bold: true, color: "047857", fontFace: "Segoe UI" } },
            { text: "10 Hz NMEA Trajectory, Heading, Speed & Monotonic Fix Quality", options: { fontSize: 8.5, color: "475569", fontFace: "Segoe UI" } }
        ], {
            x: 10.10, y: 4.95, w: 2.183, h: 1.10,
            valign: "top", margin: 0, wrap: true
        });

        // Connecting Arrows from Internal Sensors to Phone
        slide.addShape(pres.ShapeType.leftArrow, {
            x: 9.58, y: 3.90, w: 0.25, h: 0.25,
            fill: { color: "A855F7" },
            line: { color: "A855F7" }
        });
        slide.addShape(pres.ShapeType.leftArrow, {
            x: 9.58, y: 5.35, w: 0.25, h: 0.25,
            fill: { color: "10B981" },
            line: { color: "10B981" }
        });

        // 6. Bottom Banner: RoadSense Data Logger Master Sync Engine
        addCard(slide, 3.60, 6.18, 6.60, 0.95, "BAE6FD", "F0F9FF");
        slide.addText("ROADSENSE DATA LOGGER (Master Sync Engine)", {
            x: 3.70, y: 6.22, w: 6.40, h: 0.25,
            fontSize: 10.5, bold: true, color: "0369A1", align: "center", fontFace: "Segoe UI", margin: 0
        });

        // 3 Bottom Feature Pills
        const bottomPills = [
            { text: "Monotonic Timeline Master\n(session_timeline.csv)", color: "0284C7" },
            { text: "Continuous Flight Recorder\n(app_system.log)", color: "E11D48" },
            { text: "Synchronized Live BEV HUD\n(20 Hz Side-by-Side)", color: "059669" }
        ];
        bottomPills.forEach((p, idx) => {
            const pillW = 2.00;
            const pillX = 3.80 + idx * 2.15;
            addCard(slide, pillX, 6.50, pillW, 0.55, "CBD5E1", "FFFFFF");
            slide.addText(p.text, {
                x: pillX + 0.05, y: 6.54, w: pillW - 0.10, h: 0.46,
                fontSize: 7.5, bold: true, color: p.color, align: "center", fontFace: "Segoe UI", margin: 0
            });
        });
    }

    // 3. Write Output PPTX
    const outputDir = path.join(__dirname, "decks");
    const outputPath = path.join(outputDir, "RoadSense_Executive_Progress.pptx");
    await pres.writeFile({ fileName: outputPath });
    console.log(`Successfully generated RoadSense presentation: ${outputPath}`);
}

generateRoadSensePresentation().catch((err) => {
    console.error("Error generating RoadSense presentation:", err);
    process.exit(1);
});
