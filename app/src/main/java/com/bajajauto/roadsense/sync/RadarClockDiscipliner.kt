package com.bajajauto.roadsense.sync

import com.bajajauto.roadsense.models.RadarHeader

/**
 * High-precision Hardware Clock Discipliner for TI mmWave Radar.
 *
 * Eliminates USB transport jitter (CP2105 1ms frame quantization, Linux kernel interrupt coalescing,
 * and bulk transfer batching) by anchoring the radar's ultra-precise 600 MHz C674x DSP hardware cycle
 * counter (`timeCpuCycles`) to Android's `SystemClock.elapsedRealtimeNanos()`.
 *
 * Mathematical Principles:
 * 1. 32-bit uint32 cycle unwrap: Expands 4.29-billion cycle rollover (~7.158s) into a 64-bit continuous timeline.
 * 2. Phase-Locked Loop (PLL) / Exponential Moving Average (EMA): Tracks the slow crystal thermal drift
 *    (~10-50 ppm) without letting high-frequency USB latency noise corrupt the interframe timeline.
 * 3. Sequence Gap / Reboot Protection: Automatically re-anchors if a radar reboot or major sequence jump is detected.
 */
class RadarClockDiscipliner(
    private val nominalDspFreqHz: Double = DEFAULT_DSP_FREQ_HZ
) {
    companion object {
        const val DEFAULT_DSP_FREQ_HZ = 600_000_000.0 // TI AWR1843 C674x DSP nominal clock
        private const val NOMINAL_ALPHA_NS_PER_CYCLE = 1.0e9 / DEFAULT_DSP_FREQ_HZ // ~1.666667 ns/cycle
        private const val ALPHA_DRIFT_FILTER_GAIN = 0.005 // Slow low-pass filter gain (~200 frames / 10s)
        private const val REBOOT_SEQUENCE_GAP_THRESHOLD = 50L // Frames
    }

    data class DisciplinedTimestamp(
        val monotonicNs: Long,
        val wallClockMs: Long,
        val isAnchor: Boolean = false
    )

    private var anchorMonoNs: Long? = null
    private var anchorWallMs: Long = 0L
    private var anchorCycles: Long = 0L

    private var lastRawCycles: Long? = null
    private var wrapOffset: Long = 0L
    private var lastFrameNumber: Long = -1L

    private var filteredAlpha: Double = NOMINAL_ALPHA_NS_PER_CYCLE

    /**
     * Ingests a new radar frame's hardware DSP header and raw USB host timestamps,
     * returning a disciplined, jitter-free nanosecond and wall-clock timestamp.
     */
    @Synchronized
    fun discipline(
        header: RadarHeader,
        rawHostMonoNs: Long,
        rawHostWallMs: Long
    ): DisciplinedTimestamp {
        val frameNumber = header.frameNumber
        val rawCycles = header.timeCpuCycles

        // 1. Detect radar reboot or major disconnect/reconnect sequence break
        if (lastFrameNumber != -1L && (frameNumber < lastFrameNumber || frameNumber > lastFrameNumber + REBOOT_SEQUENCE_GAP_THRESHOLD)) {
            reset()
        }
        lastFrameNumber = frameNumber

        // 2. Unwrap 32-bit unsigned cycle counter into continuous 64-bit domain (~7.158s rollover)
        val prevRaw = lastRawCycles
        if (prevRaw != null && rawCycles < prevRaw && (prevRaw - rawCycles) > (1L shl 31)) {
            wrapOffset += (1L shl 32)
        }
        lastRawCycles = rawCycles
        val unwrappedCycles = rawCycles + wrapOffset

        // 3. Anchor on first frame or post-reboot
        val currentAnchorMono = anchorMonoNs
        if (currentAnchorMono == null) {
            anchorMonoNs = rawHostMonoNs
            anchorWallMs = rawHostWallMs
            anchorCycles = unwrappedCycles
            filteredAlpha = NOMINAL_ALPHA_NS_PER_CYCLE
            return DisciplinedTimestamp(rawHostMonoNs, rawHostWallMs, isAnchor = true)
        }

        // 4. Calculate elapsed cycles since anchor
        val elapsedCycles = unwrappedCycles - anchorCycles
        if (elapsedCycles <= 0L) {
            // Guard against duplicate / re-transmitted packet cycles or mock headers with constant cycles
            return DisciplinedTimestamp(rawHostMonoNs, rawHostWallMs)
        }

        // 5. Compute disciplined monotonic nanoseconds
        val disciplinedMonoNs = currentAnchorMono + (elapsedCycles * filteredAlpha).toLong()
        val disciplinedWallMs = anchorWallMs + ((disciplinedMonoNs - currentAnchorMono) / 1_000_000L)

        // 6. Slow adaptive crystal drift filter (active after 5 seconds / ~100 frames)
        if (elapsedCycles > (nominalDspFreqHz * 5.0).toLong()) {
            val observedAlpha = (rawHostMonoNs - currentAnchorMono).toDouble() / elapsedCycles.toDouble()
            // Bound observed alpha to realistic crystal tolerances (580 MHz to 620 MHz) to reject extreme USB glitches
            if (observedAlpha in 1.61..1.72) {
                filteredAlpha += (observedAlpha - filteredAlpha) * ALPHA_DRIFT_FILTER_GAIN
            }
        }

        return DisciplinedTimestamp(disciplinedMonoNs, disciplinedWallMs)
    }

    /**
     * Clears anchor and cycle tracking state. Call on session start or radar re-connection.
     */
    @Synchronized
    fun reset() {
        anchorMonoNs = null
        anchorWallMs = 0L
        anchorCycles = 0L
        lastRawCycles = null
        wrapOffset = 0L
        lastFrameNumber = -1L
        filteredAlpha = NOMINAL_ALPHA_NS_PER_CYCLE
    }
}
