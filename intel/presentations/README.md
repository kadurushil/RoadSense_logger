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

## 3. Directory Layout & Key Files

```text
intel/presentations/
├── assets/
│   └── screenshots/                          <-- Raw sensor visualizer and app captures
├── decks/
│   ├── RoadSense_Week2_Executive_Progress.pptx  <-- Master 11-slide 16:9 presentation deck
│   └── RoadSense_Week2_Slide_by_Slide_Handover_Notes.md <-- Comprehensive speaker notes & copy-pasteable LaTeX formulas
├── generate_roadsense_week2_deck.js          <-- Primary presentation generator script (layout, cards & typography)
├── package.json                              <-- pptxgenjs and tooling dependencies
└── README.md                                 <-- This technical presentation engineering standard
```

---

## 4. Re-Generating Slide Layouts

To re-run the presentation generation script and update base layouts:

```powershell
# From the project root
node intel/presentations/generate_roadsense_week2_deck.js
```
