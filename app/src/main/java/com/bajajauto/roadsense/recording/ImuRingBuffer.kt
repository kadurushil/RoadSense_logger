package com.bajajauto.roadsense.recording

import java.io.BufferedWriter
import java.util.Locale
import java.util.concurrent.locks.LockSupport

/**
 * High-performance, lock-free, zero-allocation Single-Producer Single-Consumer (SPSC)
 * ring buffer specifically engineered for high-rate (100–104 Hz) real-time IMU frame recording.
 *
 * Pre-allocates all memory slots at initialization to eliminate runtime Garbage Collector
 * allocations on the sensor capture thread.
 *
 * Capacity is fixed to 4,096 slots (~40 seconds of continuous buffering at 104 Hz), ensuring
 * zero frame drops even during prolonged Android I/O stalls or heavy system load.
 */
class ImuRingBuffer(
    val capacity: Int = 4096
) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "Capacity must be a power of 2, got $capacity"
        }
    }

    private val mask = capacity - 1

    /**
     * Pre-allocated mutable slot representing one IMU sample.
     */
    class Slot {
        var elapsedRealtimeNs: Long = 0L
        var deliveryNs: Long = 0L
        var wallTimeMs: Long = 0L
        var ax: Float = 0f
        var ay: Float = 0f
        var az: Float = 0f
        var gx: Float = 0f
        var gy: Float = 0f
        var gz: Float = 0f
        var qx: Float = 0f
        var qy: Float = 0f
        var qz: Float = 0f
        var qw: Float = 1f
        var linAx: Float = 0f
        var linAy: Float = 0f
        var linAz: Float = 0f

        fun formatCsv(sb: StringBuilder) {
            sb.setLength(0)
            sb.append(
                String.format(
                    Locale.US,
                    "%d,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f\n",
                    elapsedRealtimeNs, wallTimeMs,
                    ax, ay, az,
                    gx, gy, gz,
                    qx, qy, qz, qw,
                    linAx, linAy, linAz
                )
            )
        }
    }

    private val slots = Array(capacity) { Slot() }

    @Volatile
    private var head = 0L

    @Volatile
    private var tail = 0L

    @Volatile
    var consumerThread: Thread? = null

    @Volatile
    var droppedFrames = 0L
        private set

    /**
     * Enqueues an IMU sample into the ring buffer without allocating any memory.
     * Called exclusively by the producer (RoadSense-ImuThread).
     */
    fun enqueue(
        elapsedRealtimeNs: Long,
        deliveryNs: Long,
        wallTimeMs: Long,
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        linAx: Float, linAy: Float, linAz: Float
    ): Boolean {
        val currentHead = head
        val currentTail = tail
        if (currentHead - currentTail >= capacity) {
            droppedFrames++
            return false
        }

        val slot = slots[(currentHead and mask.toLong()).toInt()]
        slot.elapsedRealtimeNs = elapsedRealtimeNs
        slot.deliveryNs = deliveryNs
        slot.wallTimeMs = wallTimeMs
        slot.ax = ax
        slot.ay = ay
        slot.az = az
        slot.gx = gx
        slot.gy = gy
        slot.gz = gz
        slot.qx = qx
        slot.qy = qy
        slot.qz = qz
        slot.qw = qw
        slot.linAx = linAx
        slot.linAy = linAy
        slot.linAz = linAz

        head = currentHead + 1

        // Wake consumer thread if it is parked
        consumerThread?.let { LockSupport.unpark(it) }
        return true
    }

    /**
     * Returns count of unread frames available for writing.
     */
    fun availableToRead(): Int {
        val diff = (head - tail).toInt()
        return if (diff < 0) 0 else diff
    }

    /**
     * Drains available slots into the provided writer up to [maxBatch] items.
     * Called exclusively by the consumer (ImuSessionRecorder-Worker thread).
     *
     * @return Number of frames written in this batch
     */
    fun drainTo(
        writer: BufferedWriter,
        sb: StringBuilder,
        maxBatch: Int = 256,
        onFrameWritten: (Slot, Long) -> Unit
    ): Int {
        var count = 0
        val currentHead = head
        var currentTail = tail

        while (currentTail < currentHead && count < maxBatch) {
            val slot = slots[(currentTail and mask.toLong()).toInt()]
            slot.formatCsv(sb)
            writer.write(sb.toString())
            currentTail++
            count++
            onFrameWritten(slot, currentTail)
        }

        tail = currentTail
        return count
    }

    /**
     * Clears buffer state.
     */
    fun reset() {
        head = 0L
        tail = 0L
        droppedFrames = 0L
    }
}
