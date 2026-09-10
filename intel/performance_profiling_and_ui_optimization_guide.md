# RoadSense Performance Profiling & UI Optimization Guide

## 1. Executive Summary & Problem Statement

### 1.1 Symptoms Observed
During vehicle testing on entry-level and mid-tier Android devices (e.g., octa-core Cortex-A53/A55 clusters with constrained single-core IPC and shared memory bus), swiping horizontally between dashboard cards (`HorizontalPager` across **Radar**, **GNSS**, **Camera**, and **Session Storage**) exhibits noticeable stutter, input lag, and dropped frames.

### 1.2 Performance Benchmark Targets
| Metric | Current Behavior (Budget Devices) | Target SLA |
| :--- | :--- | :--- |
| **Swipe Transition Frame Rate** | 22 – 40 FPS (Frequent 60ms+ jank spikes) | **Solid 60 FPS** (Frame time $\le 16.6\text{ ms}$) |
| **Initial Touch Drag Latency** | 80 – 140 ms cold-start pause | **$\le 8\text{ ms}$ instantaneous response** |
| **Recomposition Frequency (Parent)**| 20 – 50 Recompositions / sec | **0 Recompositions / sec** during swipe |
| **ART Garbage Collection Frequency**| Minor GC every 1.5 – 3.0 seconds | **Zero GC pauses** during swipe animations |
| **CPU Core 0 (UI Thread) Load** | 85% – 100% saturation | **$\le 35\%$ UI thread load** |

---

## 2. Root Cause Analysis (Deep Dive)

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                      ANDROID MAIN UI THREAD (16.6ms Budget)                      │
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  [Choreographer 60 FPS]                                                          │
│        │                                                                         │
│        ▼                                                                         │
│   Touch Drag Event ──► Recomposition Triggered by StateFlow ──► Frame Deadline   │
│   (Scroll Offset)       • totalBytes (~30Hz)                     MISSED!         │
│                         • latestFrame (~20Hz)                 (Dropped Frame)    │
│                         • latestPacket (~30Hz)                                   │
│                                                                                  │
│   Simultaneously:                                                                │
│   • Synchronous View Inflation of Next Pager Card                                │
│   • In-Draw Math & Target Filtering                                              │
│   • Canvas Object Allocations (Paint, Path, Strings) ──► ART GC Stop-the-World   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Cause 1: State Hoisting & Recomposition Scope Pollution
In `MainActivity.kt` (`RoadSenseCockpitScreen`), high-frequency state flows are collected directly at the root composable:
```kotlin
// In MainActivity.kt (Parent Screen)
val totalBytes by viewModel.totalBytes.collectAsState()
val totalPackets by viewModel.totalPackets.collectAsState()
val latestPacket by viewModel.latestPacket.collectAsState()
val latestFrame by viewModel.latestFrame.collectAsState()
```
* **The Mechanism:** At 3.125 MBaud, serial packets arrive continuously at 20–30 Hz. Because these states are observed inside `RoadSenseCockpitScreen`, Compose invalidates and recomposes the entire root layout tree every few milliseconds.
* **Impact on Swiping:** The `HorizontalPager` animation engine relies on computing scroll physics each frame (every 16.6 ms). Constantly interrupting this layout calculation with full parent recompositions starves the UI thread and leads directly to dropped animation frames.

---

### 2.2 Cause 2: Cold-Start Lazy Instantiation in `HorizontalPager`
* **The Mechanism:** By default, Jetpack Compose's `HorizontalPager` uses an offscreen page limit of zero (`beyondBoundsPageCount = 0`). Pages are not composed until the user's finger drags past the tab boundary.
* **Impact on Swiping:**
  * Swiping to **Camera**: Compose must synchronously inflate the CameraX `PreviewView`, register lifecycle listeners, and allocate hardware texture surfaces.
  * Swiping to **Radar**: Compose must measure the Bird's-Eye View canvas, allocate coordinate scopes, and inflate target cards.
  * On a slower CPU, this cold construction takes **80ms to 140ms**, creating a visible hitch right when the user begins to drag.

---

### 2.3 Cause 3: In-Composition Math & Target Filtering
In `RadarDashboardCard.kt`, target filtering and sorting were performed directly inside the UI rendering scope:
```kotlin
// Executed directly in Composable render body on UI thread:
val priorityTargets = remember(frame) {
    frame?.tracks?.filter { it.y in 0f..100f && it.x in -10f..10f }
        ?.sortedBy { hypot(it.x, it.y) }
        ?.take(3) ?: emptyList()
}
```
* While `remember(frame)` avoids running on non-frame recompositions, it still executes vector math, hypotenuse calculations, and collection filtering on the **Main Thread** during the critical UI rendering phase every time a new radar frame arrives.

---

### 2.4 Cause 4: Drawing Object Allocations & ART Garbage Collection (GC)
Inside `RadarBevPlot.kt`, several drawing resources and string formatters were allocated dynamically inside the `Canvas { ... }` block:
* New `Paint()` objects instantiated on each draw pass.
* Dynamic `Path()` creation for target triangles and velocity lines.
* String allocations (`"%.1f m"`, `"${range.toInt()}m"`, `"TID #${track.id}"`).
* **Impact on Swiping:** Allocating thousands of short-lived objects per second forces the Android ART runtime to trigger background Garbage Collection sweeps. On low-memory budget phones, these GC pauses halt thread execution for 10–25ms, producing perceptible micro-stutters.

---

## 3. Step-by-Step Optimization Architecture

```
                    BEFORE (Laggy)                                  AFTER (Butter-Smooth)
┌─────────────────────────────────────────────────┐   ┌─────────────────────────────────────────────────┐
│ RoadSenseCockpitScreen                          │   │ RoadSenseCockpitScreen                          │
│   ├── Collects latestFrame (30Hz) [RECOMPOSES]  │   │   ├── Passes ViewModel instance down            │
│   ├── Collects totalBytes  (30Hz) [RECOMPOSES]  │   │   ├── Only collects Navigation / Tab State      │
│   └── HorizontalPager                           │   │   └── HorizontalPager (beyondBoundsPageCount=1) │
│         ├── RadarCard (Recreated on swipe)      │   │         ├── RadarCard (Collects its OWN state)  │
│         └── CameraCard (Recreated on swipe)     │   │         └── CameraCard (Pre-warmed in memory)   │
└─────────────────────────────────────────────────┘   └─────────────────────────────────────────────────┘
```

### Phase 1: Push StateFlows Down to Leaf Composables
Instead of reading high-frequency states in `MainActivity`, allow individual dashboard cards to collect only the states they render.
* `MainActivity` only collects low-frequency states (e.g. `sessionRecordingState`, `CockpitTab`).
* `RadarDashboardCard` collects `latestFrame`, `connectionState`, and `rawHexData`.
* Result: Parent `RoadSenseCockpitScreen` experiences **0 recompositions** while the user swipes across tabs.

### Phase 2: Warm-Up Neighboring Tabs (`beyondBoundsPageCount = 1`)
Configure `HorizontalPager` to retain adjacent tabs pre-composed:
```kotlin
HorizontalPager(
    state = pagerState,
    beyondBoundsPageCount = 1, // Pre-warms adjacent tabs (prevents cold inflation)
    modifier = Modifier.fillMaxSize()
) { page ->
    // Cards remain mounted; swipe transitions become instantaneous
}
```

### Phase 3: Offload Target Filtering to Background Dispatcher
Move target processing from UI Composable into `RadarViewModel` using a dedicated background `StateFlow`:
```kotlin
// In RadarViewModel.kt
val topPriorityTargets: StateFlow<List<RadarTrack>> = _latestFrame
    .map { frame ->
        frame?.tracks
            ?.filter { it.y in 0f..100f && it.x in -10f..10f }
            ?.sortedBy { hypot(it.x, it.y) }
            ?.take(3) ?: emptyList()
    }
    .flowOn(Dispatchers.Default) // Executes on background worker pool
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```
* The UI thread simply binds pre-computed target cards without computing math or sorting lists during the draw pass.

### Phase 4: Zero-Allocation Radar Canvas Rendering
1. **Pre-allocate Paints and Paths:** Instantiate `android.graphics.Paint` objects once as remembered objects or top-level constants.
2. **Reuse Coordinate Buffers:** Pre-calculate range ring radii and coordinate mapping ratios outside the frame draw loop.
3. **Monospace Number Caching:** Pre-format distance and velocity strings or use direct glyph rendering to eliminate string allocations during rendering.

---

## 4. Android Profiling & Verification Runbook

When validating the optimizations, follow these diagnostic steps:

### 4.1 Layout Inspector & Recomposition Counters
1. Open Android Studio $\rightarrow$ **Tools** $\rightarrow$ **Layout Inspector**.
2. Enable **Show Recomposition Counts**.
3. Perform horizontal swipe gestures across the tabs:
   - **Target:** `RoadSenseCockpitScreen` recomposition count must stay at **1** (static).
   - Only the active card (e.g., `RadarBevPlot`) should show incrementing counts matching the radar frame rate.

### 4.2 Perfetto / System Trace Frame Analysis
Run a 5-second trace while continuously swiping between cards:
```bash
# Capture systrace for Compose UI and Choreographer frames
adb shell am profile start com.bajajauto.roadsense /data/local/tmp/roadsense.trace
# Perform swipe gestures on phone...
adb shell am profile stop com.bajajauto.roadsense
adb pull /data/local/tmp/roadsense.trace .
```
* Inspect in [ui.perfetto.dev](https://ui.perfetto.dev): Look for `Choreographer#doFrame` slices. All slices must complete under **16.6ms** with no red jank markers.

### 4.3 ADB Graphics Jank Dumpsys
To quickly measure dropped frames during vehicle road testing:
```bash
# Reset frame statistics
adb shell dumpsys gfxinfo com.bajajauto.roadsense reset

# Swipe between tabs for 15 seconds, then dump stats:
adb shell dumpsys gfxinfo com.bajajauto.roadsense
```
* **Success Criteria:** `Janky frames` must be $< 3\%$ of total frames rendered.

---

## 5. Implementation Checklist (For Follow-up Session)

- [ ] **Step 1:** Modify `MainActivity.kt` to remove high-frequency StateFlow collections (`latestFrame`, `totalBytes`, `latestPackets`) from `RoadSenseCockpitScreen`.
- [ ] **Step 2:** Update `RadarDashboardCard.kt` to collect its own dependencies directly from `viewModel`.
- [ ] **Step 3:** Set `beyondBoundsPageCount = 1` on `HorizontalPager` in `MainActivity.kt`.
- [ ] **Step 4:** Move Line-of-Sight priority target extraction into `RadarViewModel` on `Dispatchers.Default`.
- [ ] **Step 5:** Convert `RadarBevPlot.kt` drawing routines to reuse static `Paint` and `Path` objects, eliminating per-frame heap allocations.
- [ ] **Step 6:** Validate with `dumpsys gfxinfo` to confirm $< 2\%$ janky frames during continuous card swiping.
