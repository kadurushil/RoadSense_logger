/**
 * RoadSense Update 4 Executive & Technical Progress Presentation Generator
 *
 * Generates an 11-slide, 100% native vector + verified-visual PowerPoint presentation (.pptx)
 * covering one full month of systems, UI, hardware serial, CAN, IMU, MCAP parity, and
 * pitch-solver benchmarking milestones (50305c0 Oct 23 -> eb8adc5 Nov 23, 2026: 91 commits).
 *
 * Design Standards (intel/presentations/README.md):
 * - 16:9 Modern Widescreen (13.333" x 7.500", SCREEN16x9_FULL) on crisp #FFFFFF canvas.
 * - Minimalist Chrome: Zero top-left category tags, zero pill badges, zero footers, zero slide numbers.
 * - Zero SVG / bitmap equations: Reserved clean dashed slots + copy-pasteable Alt+= OMML LaTeX in Handover Notes.
 * - Empty dashed placeholders reserved strictly for live app / dashboard / Foxglove screenshots.
 * - Custom deterministic 300-DPI L->R diagrams and empirical data charts embedded cleanly.
 * - Compact 3x2 (6-card) Bug Root-Cause grids (Slides 08 & 09).
 * - OpenXML-safe metadata ("and" instead of raw "&" in pres.company / pres.title).
 */

import pptxgen from "pptxgenjs";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export async function generateRoadSenseUpdate4Presentation() {
    const pres = new pptxgen();

    pres.defineLayout({ name: "SCREEN16x9_FULL", width: 13.333, height: 7.500 });
    pres.layout = "SCREEN16x9_FULL";
    pres.author = "RoadSense Core Engineering Team";
    pres.company = "Bajaj Auto Ltd. - Advanced Perception and Telemetry";
    pres.title = "RoadSense: Update 4 - One-Month Systems, Perception and Pipeline Transformation";

    const COLOR_BG = "FFFFFF";
    const COLOR_CARD_BG = "F8FAFC";
    const COLOR_CARD_BORDER = "CBD5E1";
    const COLOR_PRIMARY = "1D4ED8";
    const COLOR_SECONDARY = "0284C7";
    const COLOR_SUCCESS = "15803D";
    const COLOR_WARNING = "D97706";
    const COLOR_TEXT_MAIN = "0F172A";
    const COLOR_TEXT_MUTED = "334155";
    const COLOR_TEXT_SUBTLE = "64748B";
    const COLOR_PURPLE = "7E22CE";
    const COLOR_INDIGO = "4338CA";
    const COLOR_ROSE = "BE123C";

    const ASSETS_DIR = path.join(__dirname, "assets");
    function assetPath(filename) {
        const full = path.join(ASSETS_DIR, filename);
        if (!fs.existsSync(full)) {
            throw new Error(`Required visual asset missing: ${full}`);
        }
        return full;
    }

    function addSlideHeader(slide, mainTitle, subTitle) {
        slide.background = { color: COLOR_BG };
        slide.addText(mainTitle, {
            x: 0.80, y: 0.50, w: 11.733, h: 0.44,
            fontSize: 21.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });
        slide.addText(subTitle, {
            x: 0.80, y: 0.98, w: 11.733, h: 0.28,
            fontSize: 11.2, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI",
            valign: "top", margin: 0
        });
        slide.addShape(pres.ShapeType.line, {
            x: 0.80, y: 1.38, w: 11.733, h: 0,
            line: { color: "E2E8F0", width: 1.2 }
        });
    }

    function addCard(slide, x, y, w, h, borderColor = COLOR_CARD_BORDER, fillColor = COLOR_CARD_BG) {
        slide.addShape(pres.ShapeType.roundRect, {
            x, y, w, h,
            fill: { color: fillColor },
            line: { color: borderColor, width: 1.2 },
            rectRadius: 0.06
        });
    }

    function addPlaceholder(slide, x, y, w, h, title, desc, tag = "SCREENSHOT PLACEHOLDER") {
        slide.addShape(pres.ShapeType.roundRect, {
            x, y, w, h,
            fill: { color: "F1F5F9" },
            line: { color: COLOR_PRIMARY, width: 1.5, dashType: "dash" },
            rectRadius: 0.06
        });
        slide.addText([
            { text: `[ ${tag.toUpperCase()} ]\n\n`, options: { fontSize: 9.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: title + "\n\n", options: { fontSize: 12, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: desc, options: { fontSize: 9, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } }
        ], {
            x: x + 0.18, y: y + 0.20, w: w - 0.36, h: h - 0.40,
            align: "center", valign: "middle", margin: 0, wrap: true
        });
    }

    function addEquationSlot(slide, x, y, w, h, label = "Native Equation Slot (Alt + =)") {
        slide.addShape(pres.ShapeType.roundRect, {
            x, y, w, h,
            fill: { color: "FFFFFF" },
            line: { color: "93C5FD", width: 1.2, dashType: "dash" },
            rectRadius: 0.04
        });
        slide.addText(`[ ${label.toUpperCase()} ]`, {
            x: x + 0.10, y: y + 0.05, w: w - 0.20, h: h - 0.10,
            fontSize: 8.5, color: "2563EB", align: "center", valign: "middle", fontFace: "Consolas", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 01: Title Slide (Executive Monthly Briefing)
    // =========================================================================
    {
        const slide = pres.addSlide();
        slide.background = { color: COLOR_BG };

        slide.addShape(pres.ShapeType.rect, {
            x: 0.80, y: 1.35, w: 0.12, h: 4.70,
            fill: { color: COLOR_PRIMARY },
            line: { color: COLOR_PRIMARY }
        });

        slide.addShape(pres.ShapeType.roundRect, {
            x: 1.15, y: 1.35, w: 4.55, h: 0.36,
            fill: { color: "EFF6FF" },
            line: { color: "BFDBFE", width: 1 },
            rectRadius: 0.05
        });
        slide.addText("UPDATE 4  •  OCT 23 – NOV 23, 2026  •  91 COMMITS", {
            x: 1.15, y: 1.35, w: 4.55, h: 0.36,
            fontSize: 9.0, bold: true, color: COLOR_PRIMARY, align: "center", fontFace: "Segoe UI",
            valign: "middle", margin: 0
        });

        slide.addText([
            { text: "RoadSense: Update 4 Progress\n", options: { fontSize: 33, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Zero-Loss Serial Radar, <1 ms SurfaceView UI, E2E CAN Sync and Pitch-Solved Validation", options: { fontSize: 15.5, color: COLOR_SECONDARY, fontFace: "Segoe UI" } }
        ], {
            x: 1.15, y: 1.90, w: 11.10, h: 1.30,
            valign: "top", margin: 0, wrap: true
        });

        slide.addShape(pres.ShapeType.line, {
            x: 1.15, y: 3.35, w: 11.383, h: 0,
            line: { color: "E2E8F0", width: 1.2 }
        });

        const heroCards = [
            {
                tag: "RADAR HARDWARE AND CLOCK",
                title: "3.125 Mbps Zero-Loss & Clock Lock",
                desc: "Eliminated CP2105 576 B FIFO stalls (7.67% -> 0.00% corruption) via 128 KB async DMA + salvage; 600 MHz DSP clock lock cut timing jitter 41x down to 0.21 ms.",
                color: COLOR_PRIMARY,
                bg: "F0F9FF"
            },
            {
                tag: "60 FPS UI, HUD AND GNSS/IMU",
                title: "<1 ms SurfaceView & EKF-ZUPT",
                desc: "Removed stale APK copy sabotage; dropped DRAW_BEV/OVERLAY from 10+ ms to 0.36/0.38 ms; shipped Immersive SBS, M325F ISP crop fix, Traffic HUD, GNSS isolation, and 99.7% EKF yaw lock.",
                color: COLOR_SUCCESS,
                bg: "F0FDF4"
            },
            {
                tag: "CAN SYNC, MCAP AND PITCH SOLVER",
                title: "E2E CAN, 100% Parity & Pitch Solver",
                desc: "Autonomous Android CAN watchdog + Tier-1/Tier-2 PC processing (77 CAN topics); 100.0% 8-category MCAP parity; DynamicPitchSolver detecting 0.995 deg error and doubling 15-30m match rate.",
                color: COLOR_PURPLE,
                bg: "FAF5FF"
            }
        ];

        heroCards.forEach((c, idx) => {
            const cardX = 1.15 + idx * 3.88;
            addCard(slide, cardX, 3.65, 3.62, 2.40, c.color, c.bg);
            slide.addText([
                { text: c.tag + "\n", options: { fontSize: 8.5, bold: true, color: c.color, fontFace: "Segoe UI" } },
                { text: c.title + "\n\n", options: { fontSize: 12.5, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: c.desc, options: { fontSize: 9.2, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
            ], {
                x: cardX + 0.18, y: 3.80, w: 3.26, h: 2.10,
                valign: "top", margin: 0, wrap: true
            });
        });

        slide.addText("Bajaj Auto Ltd.  •  Advanced Perception and Telemetry  (368 files changed, +44,780 / -6,242 lines)", {
            x: 1.15, y: 6.85, w: 8.40, h: 0.25,
            fontSize: 8.5, color: "94A3B8", fontFace: "Segoe UI"
        });
        slide.addText("November 2026  •  Update 4 Handover", {
            x: 9.00, y: 6.85, w: 3.533, h: 0.25,
            fontSize: 8.5, color: "94A3B8", align: "right", fontFace: "Segoe UI"
        });
    }

    // =========================================================================
    // SLIDE 02: One-Month Executive Summary (4 Pillars)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "One-Month Engineering Trajectory: 91 Commits Across App & PC Toolchain",
            "Consolidating hardware serial resilience, 60 FPS UI architecture, end-to-end CAN telemetry, and pitch-calibrated validation"
        );

        const pillars = [
            {
                x: 0.80, y: 1.58, color: COLOR_PRIMARY, bg: "F8FAFC",
                title: "1. Zero-Loss 3.125 Mbps Radar & 600 MHz Clock Lock",
                bullets: [
                    ["Serial Hardening (Bugs #28–#29): ", "Pre-launch URGENT_AUDIO (-19) reader thread + Linux async DMA read queue (128 x 1 KB = 409.6 ms headroom) + magic-word salvage eradicated 7.67% frame corruption -> 0.00%."],
                    ["600 MHz Hardware Clock Lock (Bug #30): ", "Unwraps 32-bit DSP timeCpuCycles to bypass 52% USB 64-byte coalescing bursts (71.8 / 28.2 ms), crushing live jitter 41x down to 0.21 ms std dev across 40,023 intervals."]
                ]
            },
            {
                x: 6.81, y: 1.58, color: COLOR_SUCCESS, bg: "F8FAFC",
                title: "2. <1 ms SurfaceView UI, Immersive SBS & Sensor HUD",
                bullets: [
                    ["60 FPS Compose & HWC Overhaul (Bug #31): ", "Fixed stale APK overwrite hook, eliminated 86.3 FPS idle redraw (21% -> 0% CPU), built single-node LiveMetricsCanvasHud (64.4 -> 0.28 ms), and migrated to persistent AutoFitSurfaceView (0 restarts)."],
                    ["SBS, ISP Parity & HUD/IMU EKF: ", "Added Immersive SBS (±60° FOV clamp), fixed SM-M325F cy=480->360 crop (7.5° offset), separated /gnss/network, and launched 2-state EKF-ZUPT cutting stationary yaw drift 99.7%."]
                ]
            },
            {
                x: 0.80, y: 4.34, color: COLOR_PURPLE, bg: "F8FAFC",
                title: "3. Autonomous CANedge2 Edge Watchdog & PC Tier-1 / Tier-2",
                bullets: [
                    ["On-Device Sync Engine: ", "Mdf4HeaderInspector (<=256 B IDBLOCK/UnFinMF), cross-pool+session dedup, live rollover clock fix (+60 s bug removed), CALIBRATING gate, 1 s watchdog & interactive HUD CAN pill."],
                    ["Modular tools/CAN_Processing/: ", "77 /can/* topics (1.58M msgs) aligned via Tier-1 FrameNumber_Act (-0.08 ms median, spread 220 -> 38.9 ms) and zero-radar Tier-2 rfft gyro-yaw sync (0.998 corr / 4.2σ SNR)."]
                ]
            },
            {
                x: 6.81, y: 4.34, color: COLOR_INDIGO, bg: "F8FAFC",
                title: "4. 100.0% MCAP Parity & Pitch-Solved Benchmark Overhaul",
                bullets: [
                    ["MCAP Integrity & 8-Category Parity: ", "Enriched Foxglove /vision/* topics + binary Protobuf IMU + trailing MP4 extrapolation; verify_conversion_parity.py jumped from 58.0% -> 100.0% (23,340 frames, 0 errors)."],
                    ["Benchmark Overhaul & DynamicPitchSolver: ", "Switched to front contact_patch + nearest matching (0-50 m, zero lateral filter) + clutter-immune pitch solver recovering 0.995° mount offset and doubling 15-30m match rate."]
                ]
            }
        ];

        pillars.forEach(p => {
            addCard(slide, p.x, p.y, 5.72, 2.58, p.color, p.bg);
            const runs = [
                { text: p.title + "\n\n", options: { fontSize: 12.2, bold: true, color: p.color, fontFace: "Segoe UI" } }
            ];
            p.bullets.forEach(([lead, body], bIdx) => {
                runs.push({ text: "• " + lead, options: { fontSize: 9.3, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } });
                runs.push({ text: body + (bIdx === 0 ? "\n\n" : ""), options: { fontSize: 9.0, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } });
            });
            slide.addText(runs, {
                x: p.x + 0.20, y: p.y + 0.16, w: 5.32, h: 2.26,
                valign: "top", margin: 0, wrap: true
            });
        });
    }

    // =========================================================================
    // SLIDE 03: Radar Pipeline & Timing Jitter (Visual Flow + Solutions + Jitter Chart)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "3.125 Mbps Radar Pipeline: Eliminating Bottlenecks & Timing Jitter",
            "Bypassing CP2105 576 B FIFO saturation, Exynos HAL preemption stalls, and USB 64-byte coalescing via async DMA and clock locking"
        );

        // Left Top Card: Pipeline Bottleneck Solution (Bugs #28 & #29)
        addCard(slide, 0.80, 1.56, 5.20, 2.74, COLOR_ROSE, "F8FAFC");
        slide.addText([
            { text: "1. Breaking the 1.84 ms Hardware FIFO Wall (Bugs #28–#29)\n\n", options: { fontSize: 11.2, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "• Physical Bottleneck: ", options: { fontSize: 9.0, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "At 3.125 Mbps (312.5 B/ms), the CP2105 USB bridge holds only 576 bytes (1.84 ms budget). Exynos Perfetto traced 5.18 ms Camera HAL stalls starving USB reads; truncated tails made the assembler swallow Frame N+1 ('Frankenstein frames', 7.67% loss).\n\n", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• How We Fixed It: ", options: { fontSize: 9.0, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Pre-promoted reader thread to URGENT_AUDIO (-19), expanded kernel async DMA queue (128 x 1 KB = 409.6 ms headroom), added preemptive TI_MAGIC salvage + zero-copy queue -> 0.00% corruption (+252 recovered frames).", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.98, y: 1.70, w: 4.84, h: 2.46,
            valign: "top", margin: 0, wrap: true
        });

        // Left Bottom Card: USB Coalescing & Hardware Clock Disciplining (Bug #30)
        addCard(slide, 0.80, 4.44, 5.20, 2.58, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "2. Defeating USB 64-Byte Coalescing Jitter (Bug #30)\n\n", options: { fontSize: 11.2, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Bimodal Arrival Spikes: ", options: { fontSize: 9.0, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "52.0% of radar frames hit exact 64-byte multiples, omitting USB short packets and flushing back-to-back (71.8 / 28.2 ms alternating waves).\n\n", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 600 MHz Hardware Clock Lock: ", options: { fontSize: 9.0, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "RadarClockDiscipliner unwraps the radar DSP's 600 MHz cycle counter, filters thermal drift, and logs rawMonoNs + usbDelayMs -> std dev collapsed 41x (8.73 -> 0.21 ms across 40,023 live frames).", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.98, y: 4.58, w: 4.84, h: 2.30,
            valign: "top", margin: 0, wrap: true
        });

        // Right Top: L->R Bottleneck Pipeline Diagram PNG (aspect 6.20 : 2.35)
        addCard(slide, 6.18, 1.56, 6.35, 2.42, COLOR_CARD_BORDER, "FFFFFF");
        slide.addImage({
            path: assetPath("radar_pipeline_bottleneck.png"),
            x: 6.26, y: 1.60, w: 6.20, h: 2.35
        });

        // Right Bottom: Empirical Live Jitter Comparison Chart PNG (aspect 6.20 : 2.85)
        addCard(slide, 6.18, 4.10, 6.35, 2.92, COLOR_CARD_BORDER, "FFFFFF");
        slide.addImage({
            path: assetPath("radar_clock_jitter_comparison.png"),
            x: 6.26, y: 4.14, w: 6.20, h: 2.85
        });
    }

    // =========================================================================
    // SLIDE 04: 60 FPS Compose UI & SurfaceView Overhaul (Generous Spotlight Win!)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "60 FPS Compose UI Overhaul: Cutting Draw Times from 10+ ms Down to <1 ms",
            "Eradicating Bug #31 stutter sources on Samsung Galaxy M32 (SM-M325F) via HWC SurfaceView, direct Canvas HUD, and VSync pacing"
        );

        // Left Column: How We Solved Every Stutter Source
        addCard(slide, 0.80, 1.56, 5.45, 5.46, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "How We Solved All 6 UI Stutter Root Causes\n\n", options: { fontSize: 12.2, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },

            { text: "1. Uncovered Stale APK Build Sabotage:\n", options: { fontSize: 9.4, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
            { text: "   Removed app/build.gradle.kts afterEvaluate copy hook that silently overwrote fresh APKs with a frozen Nov 6 binary on every build.\n\n", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "2. Killed 86.3 FPS Idle Self-Invalidation:\n", options: { fontSize: 9.4, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Mutating Path() inside Compose Canvas forced permanent VSync invalidation; switched to cached ImageBitmap grids & precomputed 3x3 matrix (21% -> 0% idle CPU).\n\n", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "3. Bypassed Samsung OneUI Semantics IPC Freezes:\n", options: { fontSize: 9.4, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Replaced 18 discrete Text nodes in LiveMetricsBar with a single isolated LiveMetricsCanvasHud + clearAndSetSemantics (64.4 -> 0.28 ms, -99.5%).\n\n", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },

            { text: "4. Persistent AutoFitSurfaceView (0 Restarts vs 1.48 s):\n", options: { fontSize: 9.4, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Replaced TextureView GPU copies with hardware HWC SurfaceView + off-tab Yellow STBY warm pause and placement-gated tabs (385.7 -> 55.3 ms tab switch).\n\n", options: { fontSize: 8.6, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },

            { text: "5. Choreographer VSync Lock & 1:1 Draw Deduplication:\n", options: { fontSize: 9.4, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "   Added RadarDisplayPacer (50.04 ms p50), demoted 7 disk writers to BACKGROUND, eliminated redundant 30 Hz CSV writes & double overlay passes.", options: { fontSize: 8.6, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.98, y: 1.70, w: 5.09, h: 5.18,
            valign: "top", margin: 0, wrap: true
        });

        // Right Top: Empirical UI Latency Chart PNG (6.05 x 4.49 preserving 6.20:4.60)
        addCard(slide, 6.42, 1.56, 6.11, 4.54, COLOR_CARD_BORDER, "FFFFFF");
        slide.addImage({
            path: assetPath("ui_latency_before_after.png"),
            x: 6.46, y: 1.59, w: 6.03, h: 4.47
        });

        // Right Bottom: Bold 3-Metric Highlight Strip
        const highlights = [
            { x: 6.42, label: "DRAW_BEV", val: "8.57 -> 0.36 ms", sub: "-95.8% Frame Draw", color: COLOR_PRIMARY },
            { x: 8.50, label: "DRAW_OVERLAY", val: "14.45 -> 0.38 ms", sub: "-97.4% (1:1 Dedup)", color: COLOR_SUCCESS },
            { x: 10.58, label: "FULLSCREEN / TAB", val: "1.48 s -> 0 Restarts", sub: "385.7 -> 55.3 ms Switch", color: COLOR_PURPLE }
        ];
        highlights.forEach(h => {
            addCard(slide, h.x, 6.22, 1.95, 0.80, h.color, "F8FAFC");
            slide.addText([
                { text: h.label + "\n", options: { fontSize: 7.5, bold: true, color: COLOR_TEXT_SUBTLE, fontFace: "Segoe UI" } },
                { text: h.val + "\n", options: { fontSize: 10.2, bold: true, color: h.color, fontFace: "Segoe UI" } },
                { text: h.sub, options: { fontSize: 7.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
            ], {
                x: h.x + 0.08, y: 6.27, w: 1.79, h: 0.70,
                align: "center", valign: "middle", margin: 0, wrap: true
            });
        });
    }

    // =========================================================================
    // SLIDE 05: Immersive SBS, ISP Intrinsics, Traffic HUD & GNSS/IMU Fusion (Merged 06+07)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Immersive SBS, SM-M325F ISP Parity, Traffic HUD & GNSS/IMU Fusion",
            "Unifying fullscreen dual-view UX, 16:9 stream intrinsics crop, 5-sensor readiness telemetry, satellite isolation, and 2-state EKF-ZUPT"
        );

        // Left Column: 3 Compact Stacked Technical Cards (w = 7.05)
        addCard(slide, 0.80, 1.54, 7.05, 1.66, COLOR_SECONDARY, "F8FAFC");
        slide.addText([
            { text: "1. Immersive Fullscreen SBS, ±60° FOV Scale & SM-M325F 16:9 ISP Crop\n", options: { fontSize: 10.6, bold: true, color: COLOR_SECONDARY, fontFace: "Segoe UI" } },
            { text: "• Immersive SBS (94d6ffc, Bug #33): ", options: { fontSize: 8.6, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Added fullscreen side-by-side preview, 15/30/60/100/150 m zoom presets, ±60° BEV lateral width clamping, and full-height grid arc continuation.\n", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Foxglove Intrinsics Parity (0d9840e, Bug #35): ", options: { fontSize: 8.6, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Applied 4:3-to-16:9 ISP crop inside getStreamIntrinsics(), fixing cy = 480 -> 360 (eliminating a 7.5° / 120 px vertical projection error on Galaxy M32).", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.96, y: 1.64, w: 6.73, h: 1.48,
            valign: "top", margin: 0, wrap: true
        });

        addCard(slide, 0.80, 3.30, 7.05, 1.78, COLOR_SUCCESS, "F8FAFC");
        slide.addText([
            { text: "2. Unified Traffic-Light Readiness HUD & GNSS Sky Hardening\n", options: { fontSize: 10.6, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• Live 5-Sensor HUD (11fbff7, 04bcc1c, a151e2f): ", options: { fontSize: 8.6, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "SensorReadinessEvaluator classifies CAN, CAM, RAD, GNSS, and IMU into GRAY/AMBER/GREEN/RED pills (+ Yellow STBY off-tab); fixed /4 heterogeneous IMU Hz bug (150 -> ~200 Hz primary accel rate) & Samsung LazyColumn key crash.\n", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Dual-Stream GNSS & XTRA (f6113d5, acaffa1): ", options: { fontSize: 8.6, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Isolated coarse network fixes to /gnss/network (removing 100% of 19 zero-speed drops & 49 >40 m/s velocity spikes); added pre-fix GnssStatus (used/inView) & A-GPS XTRA injection.", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.96, y: 3.40, w: 6.73, h: 1.60,
            valign: "top", margin: 0, wrap: true
        });

        addCard(slide, 0.80, 5.18, 7.05, 1.84, COLOR_PURPLE, "F8FAFC");
        slide.addText([
            { text: "3. IMU Vehicle Body Frame & 2-State GNSS-IMU EKF with ZUPT\n", options: { fontSize: 10.6, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "• ISO-FLU Mount Compensation (caec7a8): ", options: { fontSize: 8.6, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Modular tools/imu/ decouples raw /imu/data from mount-compensated /imu/vehicle: median pitch centered from 84.59° -> 0.26°, Az = +9.79 m/s² (+1G), removing gravity leakage into Ax.\n", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 2-State [psi, b_z] EKF-ZUPT (e055e7d): ", options: { fontSize: 8.6, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Zero-Velocity Updates + GPS Doppler fusion reduced 39 s stationary red-light yaw drift from -56.29° -> +0.18° (99.7% drift elimination) and stopped -2,053.8° (5.7 full spins) over 19.6 min.", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.96, y: 5.28, w: 6.73, h: 1.66,
            valign: "top", margin: 0, wrap: true
        });

        // Right Top: Empty Screenshot Placeholder (SBS + HUD)
        addPlaceholder(
            slide, 8.03, 1.54, 4.50, 3.54,
            "Immersive Fullscreen SBS & Traffic HUD",
            "Insert real SM-M325F capture showing persistent AutoFitSurfaceView viewfinder, ±60° BEV scope (100/150m), and interactive 5-pill CAN/CAM/RAD/GNSS/IMU readiness HUD.",
            "PHONE SCREENSHOT SLOT"
        );

        // Right Bottom: Reserved Equation Slot for ISO-FLU Transform & EKF State Update
        addCard(slide, 8.03, 5.18, 4.50, 1.84, COLOR_PRIMARY, "FFFFFF");
        slide.addText("Kinematic Mount Compensation & EKF State Model", {
            x: 8.18, y: 5.28, w: 4.20, h: 0.25,
            fontSize: 9.2, bold: true, color: COLOR_PRIMARY, align: "center", fontFace: "Segoe UI", margin: 0
        });
        addEquationSlot(
            slide, 8.18, 5.60, 4.20, 1.28,
            "Native Equation Slot 1 (Alt + =)\nR_base<-phone & EKF [psi, bz] Prediction + ZUPT"
        );
    }

    // =========================================================================
    // SLIDE 06: End-to-End CAN Logging, Tier-1/Tier-2 Sync & Improvements
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Autonomous CANedge2 Logging, Tier-1 / Tier-2 Alignment & Next Steps",
            "End-to-end CANedge2 AP ingestion, rollover clock stabilization, modular MF4-to-MCAP alignment, and recommended edge hardening"
        );

        // Top Wide Generated Diagram (11.733 x 2.38 matching 11.73 : 2.38 ratio)
        addCard(slide, 0.80, 1.54, 11.733, 2.46, COLOR_CARD_BORDER, "FFFFFF");
        slide.addImage({
            path: assetPath("can_end_to_end_pipeline.png"),
            x: 0.80, y: 1.58, w: 11.73, h: 2.38
        });

        // Bottom Left: Android Edge + PC Tier-1 / Tier-2 Verified Results
        addCard(slide, 0.80, 4.14, 5.75, 2.88, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Operational Edge Sync & Dual-Tier Post Alignment\n\n", options: { fontSize: 11.4, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Autonomous Android Watchdog (5ab1193, 329429c): ", options: { fontSize: 8.9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "1 s loop with SYNC_FASTPATH idle skip, Mdf4HeaderInspector (<=256 B UnFinMF read), cross-pool+session dedup, +60 s rollover fix, & CALIBRATING gate (125 JVM tests).\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Tier 1 — Radar FrameNumber_Act (40f7abb): ", options: { fontSize: 8.9, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "5,957 rolling matches eliminated +30.942 s RTC offset & -451.3 ppm drift (median delta -0.08 ms; spread 220.17 -> 38.90 ms) across 77 /can/* topics (1.58M msgs).\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Tier 2 — Zero-Radar IMU Gyro Sync (d2265c8): ", options: { fontSize: 8.9, bold: true, color: COLOR_PURPLE, fontFace: "Segoe UI" } },
            { text: "Dual-anchor FFT (rfft) yaw-rate sync hit 0.998 corr / 4.2σ SNR (119 s drive) & 0.900 corr / 11.9σ SNR recovering 57.6 s stale metadata + 10 s MCAP clamp.", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.98, y: 4.28, w: 5.39, h: 2.60,
            valign: "top", margin: 0, wrap: true
        });

        // Bottom Right: Suggested Further CAN Improvements
        addCard(slide, 6.78, 4.14, 5.75, 2.88, COLOR_WARNING, "FFFBEB");
        slide.addText([
            { text: "Suggested Further CAN Improvements (Next Iteration)\n\n", options: { fontSize: 11.4, bold: true, color: COLOR_WARNING, fontFace: "Segoe UI" } },
            { text: "1. Pre-Capture CANedge2 RTC Discipline (NTP / GPS-PPS):\n", options: { fontSize: 9.0, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Anchor CANedge2 RTC before drive start so logs remain bounded within <1 s at capture rather than correcting +30 s / -451 ppm post-drive.\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "2. Live Bus Health & DBC Canary in Android CAN Pill:\n", options: { fontSize: 9.0, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Surface CAN error frames, bus-off faults, or missing VCU arbitration directly as AMBER/RED pill alerts before leaving test bays.\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "3. Automatic DBC Fingerprint & VIN Manifest Binding:\n", options: { fontSize: 9.0, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "   Embed active DBC checksum and decoded VIN in session_metadata.json so PC CAN_Processing auto-selects vehicle scaling across prototypes.", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 6.96, y: 4.28, w: 5.39, h: 2.60,
            valign: "top", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 07: Vision-vs-Radar Benchmark Overhaul & DynamicPitchSolver
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Vision-vs-Radar Benchmark Overhaul & Clutter-Immune Pitch Solver",
            "Eliminating +length/2 evaluation bias, restoring 0-50 m unrestricted lateral evaluation, and recovering mount pitch errors automatically"
        );

        // Left Top Card: Benchmark Methodology Fixes
        addCard(slide, 0.80, 1.54, 5.95, 3.24, COLOR_PRIMARY, "F8FAFC");
        slide.addText([
            { text: "Evaluation Methodology Fixes (fd5a687)\n\n", options: { fontSize: 11.2, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI" } },
            { text: "• Contact Patch Anchoring: ", options: { fontSize: 8.9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Switched default vision anchor from volumetric box center (+length/2 longitudinal offset) to front ground-contact patch matching radar bumper clusters.\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Radius Existence & Unmatched Taxonomy: ", options: { fontSize: 8.9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Replaced greedy 1-to-1 lapjv scarcity drops with nearest-radius existence default + structured unmatched diagnostics (near_miss, empty_radar).\n\n", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• 0–50 m Scope & Hardware Track Dedup: ", options: { fontSize: 8.9, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Removed lateral cutoffs across all classes, exposed >50 m falloff bins (50_75m, 75m_plus), parsed real ID #<tid>, & deduplicated 22 Hz vs 30 FPS precision.", options: { fontSize: 8.5, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.98, y: 1.66, w: 5.59, h: 3.00,
            valign: "top", margin: 0, wrap: true
        });

        // Left Bottom: Native Equation Slot 2 (Pitch Solver Objective)
        addCard(slide, 0.80, 4.90, 5.95, 2.12, COLOR_INDIGO, "FFFFFF");
        slide.addText("DynamicPitchSolver: Clutter-Immune Vectorized Pitch Regression", {
            x: 0.96, y: 5.00, w: 5.63, h: 0.24,
            fontSize: 9.6, bold: true, color: COLOR_INDIGO, align: "center", fontFace: "Segoe UI", margin: 0
        });
        addEquationSlot(
            slide, 0.96, 5.30, 5.63, 1.56,
            "Native Equation Slot 2 (Alt + =)\nMutual-NN (r_fwd >= 4.0 m) Huber Loss + Longitudinal Bias Minimization"
        );

        // Right Top: Empty Screenshot Placeholder (Web Dashboard ANSI Benchmark Report)
        addPlaceholder(
            slide, 6.95, 1.54, 5.58, 2.80,
            "Web Dashboard ANSI Benchmark & Pitch Solver Run",
            "Insert capture of Vision Benchmark output (SSE live progress + color-coded class metrics & DynamicPitchSolver Pass 2 table).",
            "DASHBOARD SCREENSHOT SLOT"
        );

        // Right Bottom: Empirical Verification Table (session_20261120_112453, 40,024 frames)
        addCard(slide, 6.95, 4.46, 5.58, 2.56, COLOR_SUCCESS, "F8FAFC");
        slide.addText("Empirical Verification: session_20261120_112453 (40,024 Frames, 0–50 m)", {
            x: 7.10, y: 4.56, w: 5.28, h: 0.24,
            fontSize: 9.8, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI", margin: 0
        });

        const pitchTable = [
            [
                { text: "Metric (0–50 m, All Lateral)", options: { bold: true, fill: "E2E8F0", color: COLOR_TEXT_MAIN } },
                { text: "Cfg @ 5.232°", options: { bold: true, fill: "FEE2E2", color: COLOR_ROSE, align: "right" } },
                { text: "Solved @ 4.238°", options: { bold: true, fill: "DCFCE7", color: COLOR_SUCCESS, align: "right" } },
                { text: "Delta", options: { bold: true, fill: "DBEAFE", color: COLOR_PRIMARY, align: "right" } }
            ],
            ["15–30 m Match Rate", "5.7%", "10.5%", "1.84x (Doubled)"],
            ["30–50 m Match Rate", "0.5%", "1.6%", "3.2x (Tripled)"],
            ["Overall Fwd MAE / RMSE", "1.272 / 1.549 m", "1.136 / 1.422 m", "-0.136 m"],
            ["Mutual-NN Median Dist / Bias", "2.156 / -0.391 m", "1.897 / -0.002 m", "-0.259 m / ~0"],
            ["Bus / Truck Fwd Bias", "-0.640 / -0.351 m", "+0.012 / -0.006 m", "Zeroed"]
        ];
        slide.addTable(pitchTable, {
            x: 7.10, y: 4.88, w: 5.28, h: 2.02,
            fontSize: 8.2, fontFace: "Segoe UI", color: COLOR_TEXT_MAIN,
            border: { pt: 0.5, color: "CBD5E1" },
            fill: { color: "FFFFFF" },
            margin: [3, 4, 3, 4],
            colW: [1.80, 1.18, 1.20, 1.10]
        });
    }

    // =========================================================================
    // SLIDE 08: Root Cause Analysis Part 1 — Bugs #26 Through #31 (3x2 Grid)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Root Cause Analysis: Hardware, Serial & UI Bugs #26 Through #31",
            "Permanent systemic fixes verified on device and replay logs across perception geometry, serial ingestion, clocking, and Compose UI"
        );

        const cardW = 3.65, cardH = 2.60;
        const colXs = [0.80, 4.84, 8.88];
        const rowYs = [1.56, 4.32];

        const bugsPt1 = [
            {
                title: "Bug #26: 0°-Yaw Perspective Box Collapse",
                symptom: "Turning vehicles collapsed to minimum 0.4 m pencil widths and jumped laterally across lanes.",
                cause: "Experimental corner solver assumed 0° yaw (u_left -> Z_far, u_right -> Z_near), failing when yaw != 0°.",
                fix: "Reverted faulty 0°-yaw squeeze in box_dimensions.py; preserved valid +L/2 contact-patch longitudinal anchor."
            },
            {
                title: "Bug #27: Cross-Axis IMU Yaw Noise Steps",
                symptom: "Straight-line highway driving injected +/-1.7 deg/s yaw noise and 0.5-1.0 m/s lateral velocity jumps.",
                cause: "Uncalibrated chassis mount leaked roll/pitch suspension transients across a binary 0.015 rad/s deadband.",
                fix: "Added zero-phase centered smoothing + smooth Hermite soft-knee deadband (0.010-0.025 rad/s)."
            },
            {
                title: "Bug #28: CP2105 FIFO & Frankenstein Frames",
                symptom: "7.67% corruption (824 gaps in Oct 30 drive); dense point clouds dropped trailing TLV 3 tracks.",
                cause: "1.84 ms FIFO drops caused assembler in WAITING_FOR_PAYLOAD to swallow Frame N+1 headers.",
                fix: "Async ArrayBlockingQueue + URGENT_DISPLAY parser + preemptive TI_MAGIC salvage -> 0.00% corruption."
            },
            {
                title: "Bug #29: Exynos 5.18 ms Stall & -4% Baud",
                symptom: "Dense camera+UI load dropped Frame #528 (1,040 B loss) despite URGENT_AUDIO intent.",
                cause: "Thread priority applied post-start; 5.18 ms lib_4_worker HAL stall > 1.84 ms FIFO; -4.0% baud error.",
                fix: "Pre-launch ThreadFactory (-19), 128 x 1 KB async DMA queue (409.6 ms margin), & partial salvage."
            },
            {
                title: "Bug #30: USB 64B Coalescing (71 / 28 ms)",
                symptom: "52% of frames alternated 71.8 ms / 28.2 ms delays despite 0 packet losses, faking drop alerts.",
                cause: "Exact 64-byte multiples omitted USB Full-Speed short packets, delaying flushes to next chirp.",
                fix: "RadarClockDiscipliner locks onto 600 MHz DSP TSCL; live jitter plunged 41x (8.73 -> 0.21 ms std)."
            },
            {
                title: "Bug #31: 90 Hz UI Loop & Stale APK Hook",
                symptom: "86.3 FPS idle redraw (21% CPU), 64.4 ms 1 Hz ticks, 385 ms tab stalls; fixes seemed missing.",
                cause: "afterEvaluate hook overwrote fresh APKs with Nov 6 binary; Path() mutated inside Compose draw.",
                fix: "Removed broken Gradle hook, isolated LiveMetricsCanvasHud (0.28 ms), & persistent SurfaceView."
            }
        ];

        bugsPt1.forEach((b, idx) => {
            const x = colXs[idx % 3], y = rowYs[Math.floor(idx / 3)];
            addCard(slide, x, y, cardW, cardH, COLOR_ROSE, "F8FAFC");
            slide.addText([
                { text: b.title + "\n\n", options: { fontSize: 9.8, bold: true, color: COLOR_ROSE, fontFace: "Segoe UI" } },
                { text: "• Symptom: ", options: { fontSize: 8.3, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.symptom + "\n", options: { fontSize: 8.0, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Root Cause: ", options: { fontSize: 8.3, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.cause + "\n", options: { fontSize: 8.0, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Solution: ", options: { fontSize: 8.3, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
                { text: b.fix, options: { fontSize: 8.0, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
            ], {
                x: x + 0.14, y: y + 0.12, w: cardW - 0.28, h: cardH - 0.24,
                valign: "top", margin: 0, wrap: true
            });
        });
    }

    // =========================================================================
    // SLIDE 09: Root Cause Analysis Part 2 — Bugs #32-#35 & Pipeline Fixes (3x2 Grid)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Root Cause Analysis: Bugs #32–#35 & Pipeline Hardening Fixes",
            "Resolving landscape roll sign inversion, BEV FOV clipping, CSV/ISP MCAP breakages, CAN rollover skew, and benchmark biases"
        );

        const cardW = 3.65, cardH = 2.60;
        const colXs = [0.80, 4.84, 8.88];
        const rowYs = [1.56, 4.32];

        const bugsPt2 = [
            {
                title: "Bug #32: Display Roll Sign Inversion",
                symptom: "Viewfinder artificial horizon rotated WITH vehicle roll instead of counter-rotating, doubling error.",
                cause: "ROTATION_270 / 90 roll sign inverted across ImuManager.kt and ImuSample.kt display rotations.",
                fix: "Corrected orientation roll signs and migrated display compensation to unified rotation mapping."
            },
            {
                title: "Bug #33: SBS BEV Lateral Clipping & Gaps",
                symptom: "Outer BEV range rings clipped off fullscreen SBS sides while upper canvas stayed blank above maxRange.",
                cause: "Metric scale used vertical usableHeight only, ignoring ±60° lateral span (2*R*sin60°) bounds.",
                fix: "Bounded scale = min(H/R, halfW/(R*0.866)), clipped bounds, added 100/150 m presets & full-height arcs."
            },
            {
                title: "Bug #34: Missing MCAP Video w/o CSV",
                symptom: "Phase 3+ logs produced 0 /camera/video or /sync/frame_info messages even with valid 9,562-frame MP4.",
                cause: "Converter gated MP4 ingestion strictly on deprecated camera_frames.csv removed in Phase 3.",
                fix: "Updated convert_session_to_mcap.py gate to allow MasterTimelineSynchronizer; restored 9,562 frames."
            },
            {
                title: "Bug #35: SM-M325F cy=480 ISP Crop Shift",
                symptom: "Foxglove overlays floated ~7.5° (120 px) off phone calibration despite identical phone alignment.",
                cause: "getStreamIntrinsics() read uncropped 4:3 LENS_INTRINSIC_CALIBRATION (cy=480) on 720p 16:9 stream.",
                fix: "Applied active-array 4:3-to-16:9 ISP center crop (cy=360) in getStreamIntrinsics(); zeroed Foxglove offset."
            },
            {
                title: "CANedge +60 s Rollover & IMU /4 Illusion",
                symptom: "Live chunk rollover miscomputed +60 s RTC offset; 200 Hz IMU card displayed misleading 150 Hz.",
                cause: "Rollover matched currInFolder[0] vs active tail; IMU Hz divided summed callbacks by 4 streams (a151e2f).",
                fix: "Switched rollover to currInFolder.last(), added CALIBRATING gate & primary TYPE_ACCELEROMETER Hz."
            },
            {
                title: "Benchmark Bias, Trailing MP4 & UTF-8",
                symptom: "Box center Anchor biased errors +L/2; tail video frames used wall-clock fallback; cp1252 garbled HUD.",
                cause: "3D center compared to radar bumpers; unmapped MP4 tail used wall clock; Popen lacked UTF-8 pipe.",
                fix: "Defaulted contact_patch + Pitch Solver (fd5a687), extrapolated trailing PTS (d803909), forced UTF-8 pipes."
            }
        ];

        bugsPt2.forEach((b, idx) => {
            const x = colXs[idx % 3], y = rowYs[Math.floor(idx / 3)];
            addCard(slide, x, y, cardW, cardH, COLOR_INDIGO, "F8FAFC");
            slide.addText([
                { text: b.title + "\n\n", options: { fontSize: 9.8, bold: true, color: COLOR_INDIGO, fontFace: "Segoe UI" } },
                { text: "• Symptom: ", options: { fontSize: 8.3, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.symptom + "\n", options: { fontSize: 8.0, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Root Cause: ", options: { fontSize: 8.3, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
                { text: b.cause + "\n", options: { fontSize: 8.0, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
                { text: "• Solution: ", options: { fontSize: 8.3, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
                { text: b.fix, options: { fontSize: 8.0, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
            ], {
                x: x + 0.14, y: y + 0.12, w: cardW - 0.28, h: cardH - 0.24,
                valign: "top", margin: 0, wrap: true
            });
        });
    }

    // =========================================================================
    // SLIDE 10: MCAP Parity (100.0%) & End-to-End Quantitative Impact Scorecard
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "100.0% MCAP Multi-Sensor Parity & End-to-End Impact Scorecard",
            "Folding enriched Foxglove perception topics & 8-category conversion integrity into unified one-month KPIs and test coverage"
        );

        // Left Top: Empty Screenshot Slot for Foxglove 3D Cockpit
        addPlaceholder(
            slide, 0.80, 1.54, 5.45, 2.84,
            "Foxglove 3D Multi-Sensor Cockpit",
            "Insert Foxglove Studio capture showing synchronized /radar/points, TLV 2 /radar/clusters, /vision/geo_tracks, /vision/camera_frustum, /can/*, and CY=360 alignment.",
            "FOXGLOVE SCREENSHOT SLOT"
        );

        // Left Bottom: MCAP Enrichment & 100.0% Parity Details (Folded from Slide 11)
        addCard(slide, 0.80, 4.50, 5.45, 2.52, COLOR_INDIGO, "F8FAFC");
        slide.addText([
            { text: "MCAP Enrichment & 100.0% Conversion Parity\n\n", options: { fontSize: 10.8, bold: true, color: COLOR_INDIGO, fontFace: "Segoe UI" } },
            { text: "• 8-Category Parity Scorecard (verify_conversion_parity.py): ", options: { fontSize: 8.7, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "Lifted session_20261023_111120 (23,340 frames) from 58.0% -> 100.0% PASS (0 errors, 0 warnings), verifying TLV 6 FCW, TLV 5 17 CAN signals, TLV 2 /radar/clusters, and ENU/radian GNSS (0.0000° align).\n\n", options: { fontSize: 8.3, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Pipeline Speed & Data Integrity (bab850c, 07b7503, d803909): ", options: { fontSize: 8.7, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Enriched 5 /vision/* topics, encoded /imu/data & /imu/vehicle in binary Protobuf, capped /imu/pose at 50 Hz, fixed atomic writer close, & extrapolated trailing MP4 frame timestamps.", options: { fontSize: 8.3, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 0.96, y: 4.62, w: 5.13, h: 2.28,
            valign: "top", margin: 0, wrap: true
        });

        // Right Top: Full One-Month Before/After KPI Scorecard Table
        addCard(slide, 6.45, 1.54, 6.08, 4.28, COLOR_PRIMARY, "FFFFFF");
        slide.addText("End-to-End Quantified Impact Scorecard (Oct 23 -> Nov 23)", {
            x: 6.60, y: 1.64, w: 5.78, h: 0.25,
            fontSize: 10.5, bold: true, color: COLOR_PRIMARY, fontFace: "Segoe UI", margin: 0
        });

        const kpiRows = [
            [
                { text: "Subsystem / Metric", options: { bold: true, fill: "E2E8F0", color: COLOR_TEXT_MAIN } },
                { text: "Before", options: { bold: true, fill: "FEE2E2", color: COLOR_ROSE, align: "right" } },
                { text: "After", options: { bold: true, fill: "DCFCE7", color: COLOR_SUCCESS, align: "right" } },
                { text: "Gain", options: { bold: true, fill: "DBEAFE", color: COLOR_PRIMARY, align: "right" } }
            ],
            ["Radar Frame Corruption", "7.67% (824 gaps)", "0.00% (Salvaged)", "100% Fixed"],
            ["Live Radar Inter-Frame Jitter", "8.73 ms std", "0.21 ms std", "41x Tighter"],
            ["1 Hz Metrics HUD Tick", "26.6–64.4 ms", "0.28 ms", "-99.5% Latency"],
            ["Viewfinder Draw (BEV / OVL)", "8.57 / 14.45 ms", "0.36 / 0.38 ms", "-95.8% / -97.4%"],
            ["Fullscreen Exit Stall", "1,315–1,478 ms", "0 restarts", "Yellow STBY HWC"],
            ["Stationary IMU Yaw Drift (39s)", "-56.29° drift", "+0.18° bounded", "99.7% Cut (EKF)"],
            ["CAN Tier-1 Sync Spread", "220.17 ms", "38.90 ms (-0.08 med)", "5.7x Tighter"],
            ["MCAP Parity Scorecard", "58.0% (FAIL)", "100.0% (23,340 frm)", "0 Errors"],
            ["Vision-Radar 15–30 m Match", "5.7% (@ 5.23°)", "10.5% (@ 4.24°)", "1.84x (Doubled)"]
        ];
        slide.addTable(kpiRows, {
            x: 6.60, y: 1.96, w: 5.78, h: 3.74,
            fontSize: 8.1, fontFace: "Segoe UI", color: COLOR_TEXT_MAIN,
            border: { pt: 0.5, color: "CBD5E1" },
            fill: { color: "FFFFFF" },
            margin: [2, 4, 2, 4],
            colW: [2.18, 1.15, 1.35, 1.10]
        });

        // Right Bottom: Verification & Knowledge Organization Box
        addCard(slide, 6.45, 5.94, 6.08, 1.08, COLOR_SUCCESS, "F0FDF4");
        slide.addText([
            { text: "Automated Regression Gates & Structured Intelligence\n", options: { fontSize: 10.0, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "• 200+ Automated Tests Passing Clean: ", options: { fontSize: 8.7, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "125 Android JVM unit tests + 52+ Python suite tests + 16+ CAN_Processing tests + 7 Vision eval tests.\n", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } },
            { text: "• Documentation Architecture (d602ab2, eb8adc5): ", options: { fontSize: 8.7, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } },
            { text: "163 indexed plans/walkthroughs across 8 subsystem folders + all 10 context/ handover docs synced.", options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } }
        ], {
            x: 6.62, y: 6.02, w: 5.74, h: 0.92,
            valign: "middle", margin: 0, wrap: true
        });
    }

    // =========================================================================
    // SLIDE 11: Update 5 Strategic Roadmap (Hard Mounting #1 + DSP + >66% Gate)
    // =========================================================================
    {
        const slide = pres.addSlide();
        addSlideHeader(
            slide,
            "Update 5 Roadmap: Physical Hard Mounting, DSP Qualification & >66% Gate",
            "Software, UI, clocking, CAN sync, and parity are verified—our next critical bottleneck is rigid mechanical mounting and DSP qualification"
        );

        // Top Foundation Readiness Strip
        addCard(slide, 0.80, 1.54, 11.733, 0.72, COLOR_SUCCESS, "F0FDF4");
        slide.addText([
            { text: "UPDATE 4 VERIFIED:  ", options: { fontSize: 9.4, bold: true, color: COLOR_SUCCESS, fontFace: "Segoe UI" } },
            { text: "Zero-loss 3.125 Mbps radar  •  0.21 ms clock lock  •  <1 ms SurfaceView UI  •  E2E CAN sync  •  100.0% MCAP parity  •  DynamicPitchSolver", options: { fontSize: 9.1, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } }
        ], {
            x: 0.98, y: 1.62, w: 11.37, h: 0.56,
            valign: "middle", align: "center", margin: 0, wrap: true
        });

        const cols = [
            {
                x: 0.80, color: COLOR_ROSE, bg: "FFF1F2",
                badge: "PRIORITY #1 — BIGGEST CHALLENGE",
                title: "Physical Hard Mounting & Bracket Rigidity",
                sections: [
                    ["Observed Reality: ", "Flexible windshield suction & temporary bumper brackets induce ~1° dynamic pitch/roll shifts across bumps—confirmed empirically by our DynamicPitchSolver detecting a 0.995° mount offset (5.232° -> 4.238°) that halved 15–30 m match rates and added -0.64 m bus range bias."],
                    ["Physics Sensitivity: ", "Under flat-earth IPM (dY/dθ ≈ -Y²/H), a 1.0° bracket flex shifts targets at 40 m by >10 meters longitudinally."],
                    ["Deliverable: ", "Replace temporary suction mounts with machined aluminum rigid bumper/roof-rail fixtures, alignment dowel pins, and certified static level-ground calibration."]
                ]
            },
            {
                x: 4.84, color: COLOR_WARNING, bg: "FFFBEB",
                badge: "PRIORITY #2 — SENSOR QUALIFICATION",
                title: "AWR1843 Radar DSP Build A/B Evaluation",
                sections: [
                    ["Observed Reality: ", "On session_20261120_112453, even at solved ideal pitch (4.238°), 0–50 m association capped at 22.6% with 8,994 / 40,024 empty radar frames (22.5%) despite 0% UART serial drops."],
                    ["Diagnostic Separation: ", "Our zero-loss capture and clutter-immune benchmark now isolate radar tracker DSP quality cleanly from camera, sync, or serial bottlenecks."],
                    ["Deliverable: ", "Conduct controlled A/B test drives comparing legacy vs current AWR1843 tracker DSP firmware builds under identical rigid hard mounts."]
                ]
            },
            {
                x: 8.88, color: COLOR_PRIMARY, bg: "EFF6FF",
                badge: "PRIORITY #3 — EXIT MILESTONE GATE",
                title: "Online Pitch Trim & >66% Association Gate",
                sections: [
                    ["Closed-Loop Extrinsics: ", "Automate DynamicPitchSolver feedback + factory multi-axis IMU mounting alignment (Future_stuff.md Sec. 8) once hard mounts lock chassis geometry."],
                    ["Tunable Multi-Range Scoring: ", "Track 0–50 m and >50 m correlation stability across multiple test vehicles and CAN DBC profiles via the Web Dashboard benchmark."],
                    ["Strict >66% Validation Gate: ", "Achieve >66% sustained radar-vision association across continuous real-world drives BEFORE freezing datasets for supervised fusion model training."]
                ]
            }
        ];

        cols.forEach(c => {
            addCard(slide, c.x, 2.40, 3.65, 4.62, c.color, c.bg);
            slide.addShape(pres.ShapeType.roundRect, {
                x: c.x + 0.16, y: 2.54, w: 3.33, h: 0.30,
                fill: { color: c.color }, line: { color: c.color }, rectRadius: 0.04
            });
            slide.addText(c.badge, {
                x: c.x + 0.16, y: 2.54, w: 3.33, h: 0.30,
                fontSize: 8.0, bold: true, color: "FFFFFF", align: "center", valign: "middle", fontFace: "Segoe UI", margin: 0
            });

            const runs = [
                { text: c.title + "\n\n", options: { fontSize: 11.4, bold: true, color: COLOR_TEXT_MAIN, fontFace: "Segoe UI" } }
            ];
            c.sections.forEach(([k, v], i) => {
                runs.push({ text: "• " + k, options: { fontSize: 8.8, bold: true, color: c.color, fontFace: "Segoe UI" } });
                runs.push({ text: v + (i < 2 ? "\n\n" : ""), options: { fontSize: 8.4, color: COLOR_TEXT_MUTED, fontFace: "Segoe UI" } });
            });

            slide.addText(runs, {
                x: c.x + 0.16, y: 2.96, w: 3.33, h: 3.94,
                valign: "top", margin: 0, wrap: true
            });
        });
    }

    const outDeckPath = path.join(__dirname, "decks", "RoadSense_Update4_Executive_Progress.pptx");
    await pres.writeFile({ fileName: outDeckPath });
    console.log(`[RoadSense] Generated 11-slide Update 4 presentation at: ${outDeckPath}`);
}

if (process.argv[1] && process.argv[1].endsWith("generate_roadsense_update4_deck.js")) {
    generateRoadSenseUpdate4Presentation().catch(err => {
        console.error("[RoadSense] Error generating Update 4 presentation:", err);
        process.exit(1);
    });
}
