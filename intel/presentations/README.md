# RoadSense Presentation & Slide Deck Engineering Standards

This directory contains generation scripts, assets, speaker notes, and build guidelines for executive and technical slide decks in the RoadSense project.

---

## 1. Technical & Mathematical Rigor Standard

> ⚠️ **MANDATORY POLICY FOR PRESENTATIONS:**  
> All presentations generated for RoadSense must feature deep technical and mathematical rigor. High-level bullet points alone are unacceptable when describing perception algorithms, sensor fusion geometry, kinematics, or hardware serialization.

Every technical slide describing core architecture or algorithms **must** provide the complete, uncompressed mathematical formulation:

1. **Explicit Coordinate Frame Conventions:**  
   Specify sensor origins, standard frame definitions (e.g. SAE/ISO vs. OpenCV/ROS Camera2), and axis orientations ($+X, +Y, +Z$).
2. **Deterministic Transforms & Axis Permutations:**  
   Provide exact transformation matrices, translation baselines ($\mathbf{T} = [\Delta X, \Delta Y, \Delta Z]^T$), and permutation matrices ($\mathbf{M}_{\text{axis}}$).
3. **Sensor Intrinsics & Projection Equations:**  
   Display complete pinhole matrices ($\mathbf{K}$), focal length scaling ($f_x, f_y$), principal offsets ($c_x, c_y$), and perspective division formulas.
4. **Closed-Form Solutions & Complexity:**  
   Whenever algorithmic shortcuts or calibrations are introduced (e.g., Reverse Touch Solver), document the closed-form inverse equations and analytical runtime ($O(1)$) rather than generic descriptions.

---

## 2. Equation Workflow: Native PowerPoint Equations (Manual Insertion)

> 🔴 **CRITICAL RULE FOR AI AGENTS & GENERATION SCRIPTS:**  
> **DO NOT insert equations as SVG images or raster graphics.**  
> Image/SVG-based equations lack professional polish, create font/scaling discrepancies, cannot be formatted with native Office typography, and clash with PowerPoint themes.

### Operating Workflow:
1. **Manual Equation Insertion by the User:**  
   The user will manually insert all mathematical equations directly in PowerPoint using native equation boxes (`Insert > Equation` or shortcut `Alt + =` with LaTeX / UnicodeMath mode). This produces native Office Math Markup Language (OMML) objects rendered in Cambria Math that are vector-sharp, fully editable, and visually consistent.
2. **Agent Responsibilities in Slide Generation:**  
   - Generate the clean card layouts, headers, explanatory text, bullet points, callouts, and clean reserved layout areas for the equations and screenshots.
   - Leave appropriate vertical and horizontal spacing in the cards so the user can place native equation boxes without overlapping text.
3. **Agent Responsibilities in Handover & Speaker Notes:**  
   - In [`RoadSense_Week2_Slide_by_Slide_Handover_Notes.md`](decks/RoadSense_Week2_Slide_by_Slide_Handover_Notes.md) and in chat responses, provide the exact, copy-pasteable LaTeX strings for each formula.
   - The user can simply copy the LaTeX string, hit `Alt + =` in PowerPoint, paste the string, and press `Enter` to convert it to a native equation.

---

## 3. Minimalist Slide Chrome Standard (Zero Visual Clutter)

> ⚠️ **MANDATORY POLICY: SKIP UNNECESSARY CHROME ELEMENTS**  
> To prevent executive visual fatigue and eliminate manual slide cleanup, generation scripts **MUST NOT** draw generic chrome elements.

### Elements Strictly Prohibited from Slides:
1. **NO Top-Left Category Eyebrows:** Do NOT draw category tags above the slide title (e.g., `"SYSTEM HARDENING & QUALITY ENGINEERING"`, `"PERCEPTION & VISION ENGINEERING"`).
2. **NO Top-Right Project Pill Badges:** Do NOT draw top-right project badges (e.g., `[ ROADSENSE • WEEK 3 ]`).
3. **NO Bottom-Left Confidentiality Footers:** Do NOT draw footer strings like `"CONFIDENTIAL • BAJAJ AUTO LTD. • ADVANCED PERCEPTION & TELEMETRY"`.
4. **NO Bottom-Right Slide Numbering:** Do NOT draw `"Slide X of Y"`. PowerPoint automatically handles presentation numbering in master templates.

### Permitted Slide Header Anatomy:
* **Slide Title:** Clean, bold title at $X = 0.80", Y = 0.55", W = 11.733", H = 0.45"$, font size $22\text{ pt}$.
* **Subtitle:** High-density descriptive sub-header at $X = 0.80", Y = 1.05", W = 11.733", H = 0.30"$, font size $11.5\text{ pt}$.
* **Accent Divider Line:** Single crisp horizontal divider at $X = 0.80", Y = 1.45", W = 11.733"$.
* **Body Content Envelope:** Unobstructed canvas from $Y = 1.65"$ down to $Y = 7.10"$.

---

## 4. Compact Bug Analysis & Post-Mortem Standard (6 Boxes per Slide)

> 📌 **COMPACT 3x2 GRID STANDARD FOR SYSTEM HARDENING:**  
> Instead of 4 oversized boxes per slide ($2 \times 2$), all bug post-mortem and system hardening slides **MUST use a compact 6-box grid ($3 \text{ columns} \times 2 \text{ rows}$)**.

### Layout Coordinates for 6-Box Grid ($W_{\text{canvas}} = 11.733"$):
* **Card Dimensions:** Width $W = 3.65"$, Height $H = 2.55"$.
* **Columns ($X$ Coordinates):**
  * Col 1: $X = 0.80"$
  * Col 2: $X = 4.84"$
  * Col 3: $X = 8.88"$
* **Rows ($Y$ Coordinates):**
  * Row 1: $Y = 1.65"$
  * Row 2: $Y = 4.35"$
* **Typography:**
  * Bug Title: $10.5\text{ pt}$ bold in Rose (`#BE123C`).
  * Symptom / Root Cause / Solution: $8.5\text{ pt}$ body text with concise, high-density bullets.

---

## 5. OpenXML Metadata XML Encoding (Preventing PowerPoint Repair Errors)

> 🔴 **CRITICAL ROOT CAUSE POST-MORTEM (PowerPoint XML Corruption):**  
> If PowerPoint displays the prompt:  
> *"PowerPoint found a problem with content in <file>.pptx. PowerPoint can attempt to repair the presentation..."*  
> **Root Cause:** PptxGenJS directly interpolates metadata properties (`pres.company`, `pres.title`, `pres.author`, `pres.subject`) into `docProps/app.xml` and `docProps/core.xml` **WITHOUT XML-escaping**.  
> An unescaped ampersand (`&`) (e.g. `<Company>Bajaj Auto Ltd. - Advanced Perception & Telemetry</Company>`) creates an invalid XML token on line 31 (`not well-formed (invalid token)`), triggering the repair prompt on launch.

### Mandatory Fix:
* **NEVER use raw `&` inside PptxGenJS presentation metadata properties.** Always write `"and"` (or explicitly `&amp;`):
  ```javascript
  // CORRECT
  pres.company = "Bajaj Auto Ltd. - Advanced Perception and Telemetry";
  pres.title = "RoadSense: Week 3 Progress - IPM, Decoupling and Hardening";

  // PROHIBITED (triggers OpenXML repair corruption)
  pres.company = "Bajaj Auto Ltd. - Advanced Perception & Telemetry";
  ```

---

## 6. Directory Layout & Key Files

```text
intel/presentations/
├── assets/
│   └── screenshots/                          <-- Raw sensor visualizer and app captures
├── decks/
│   ├── RoadSense_Week2_Executive_Progress.pptx  <-- Master Week 2 presentation deck
│   ├── RoadSense_Week2_Slide_by_Slide_Handover_Notes.md
│   ├── RoadSense_Week3_Executive_Progress.pptx  <-- Master Week 3 16:9 presentation deck
│   └── RoadSense_Week3_Slide_by_Slide_Handover_Notes.md <-- Speaker notes & copy-pasteable LaTeX
├── generate_roadsense_week3_deck.js          <-- Primary presentation generator script
├── generate_week3_deck.bat                   <-- 1-click batch runner
├── package.json                              <-- pptxgenjs and tooling dependencies
└── README.md                                 <-- This technical presentation engineering standard
```

---

## 7. Re-Generating Slide Layouts

To re-run the presentation generation script and update base layouts:

```powershell
# From the project root
node intel/presentations/generate_roadsense_week3_deck.js
# Or via batch file:
cmd /c intel\presentations\generate_week3_deck.bat
```
