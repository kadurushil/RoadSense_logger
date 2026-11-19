package com.bajajauto.roadsense.ui.components

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.gnss.GnssLocationManager
import com.bajajauto.roadsense.gnss.GnssState
import com.bajajauto.roadsense.sync.LiveSyncTelemetry
import com.bajajauto.roadsense.ui.LiveMetricsSnapshot
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.SensorHudTone
import com.bajajauto.roadsense.ui.SensorReadinessEvaluator
import java.util.Locale

private class HudHitBounds {
    var syncLeft: Float = 0f
    var syncRight: Float = 0f
    var syncTop: Float = 0f
    var syncBottom: Float = 0f
    var canLeft: Float = 0f
    var canRight: Float = 0f
    var canTop: Float = 0f
    var canBottom: Float = 0f
}

/**
 * Single-Node Direct Canvas LiveMetricsBar:
 * - Reads `liveMetricsSnapshot` strictly inside the `Canvas` draw phase so 1 Hz system ticks
 *   bypass Compose Recomposition, Measure, Layout, and Accessibility/Semantics tree walks (`0 ms` layout cost).
 * - Uses pre-allocated `android.graphics.Paint` objects (`0` allocations per tick).
 * - Applies `Modifier.clearAndSetSemantics {}` to eliminate `checkForSemanticsChanges` stalls.
 */
@Composable
fun LiveMetricsBar(
    viewModel: RadarViewModel,
    onSyncClick: () -> Unit = {},
    onCanClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snapshotState = viewModel.liveMetricsSnapshot.collectAsState()
    LiveMetricsCanvasHud(
        snapshotProvider = { snapshotState.value },
        onSyncClick = onSyncClick,
        onCanClick = onCanClick,
        modifier = modifier
    )
}

@Composable
fun LiveMetricsBar(
    radarHz: Float,
    cameraFps: Float,
    gnssHz: Float,
    imuHz: Float = 0f,
    latestFix: GnssFix?,
    batteryPct: Int,
    batteryTempC: Float,
    cpuUsagePct: Int = 0,
    cpuTempC: Float? = null,
    syncTelemetry: LiveSyncTelemetry = LiveSyncTelemetry(),
    onSyncClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gnssState = if (latestFix != null) {
        GnssState.Active(latestFix, 1L)
    } else if (gnssHz > 0f) {
        GnssState.Searching
    } else {
        GnssState.Disabled
    }
    val snap = LiveMetricsSnapshot(
        radarHz = radarHz,
        cameraFps = cameraFps,
        gnssHz = gnssHz,
        imuHz = imuHz,
        satellitesUsed = latestFix?.satellitesUsed ?: 0,
        satellitesInView = latestFix?.satellitesInView ?: 0,
        accuracyMeters = latestFix?.accuracyMeters ?: 0f,
        batteryPct = batteryPct,
        batteryTempC = batteryTempC,
        cpuUsagePct = cpuUsagePct,
        cpuTempC = cpuTempC,
        syncGrade = syncTelemetry.liveGrade,
        syncDeltaMs = syncTelemetry.camToImuDeltaMs,
        isSyncActive = syncTelemetry.isCameraActive && syncTelemetry.isImuActive,
        radarPill = SensorReadinessEvaluator.evaluateRadar(
            connState = if (radarHz > 0f) RadarConnectionState.Connected("USB") else RadarConnectionState.Disconnected,
            radarHz = radarHz,
            isRecording = false,
            radarDroppedMidRecording = false
        ),
        camPill = SensorReadinessEvaluator.evaluateCamera(
            engineState = if (cameraFps > 0f) CameraEngineState.Closed else CameraEngineState.Closed,
            cameraFps = cameraFps
        ),
        gpsPill = SensorReadinessEvaluator.evaluateGnss(
            gnssState = gnssState,
            gnssHz = gnssHz
        ),
        imuPill = SensorReadinessEvaluator.evaluateImu(
            imuHz = imuHz,
            isMonitoring = imuHz > 0f,
            isRecording = false
        )
    )
    LiveMetricsCanvasHud(
        snapshotProvider = { snap },
        onSyncClick = onSyncClick,
        modifier = modifier
    )
}

@Composable
private fun LiveMetricsCanvasHud(
    snapshotProvider: () -> LiveMetricsSnapshot,
    onSyncClick: () -> Unit,
    onCanClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current.density
    val scaledDensity = LocalDensity.current.fontScale * density

    val bgPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0D1117.toInt()
            style = Paint.Style.FILL
        }
    }
    val labelPaint = remember(scaledDensity) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF90A4AE.toInt()
            textSize = 9.5f * scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val valuePaint = remember(scaledDensity) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.2f * scaledDensity
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }
    val bgRect = remember { RectF() }
    val hitBounds = remember { HudHitBounds() }

    val barHeight = if (isLandscape) 24.dp else 42.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clearAndSetSemantics {}
            .pointerInput(onSyncClick, onCanClick) {
                detectTapGestures { offset ->
                    if (offset.x in hitBounds.syncLeft..hitBounds.syncRight &&
                        offset.y in hitBounds.syncTop..hitBounds.syncBottom
                    ) {
                        onSyncClick()
                    } else if (offset.x in hitBounds.canLeft..hitBounds.canRight &&
                        offset.y in hitBounds.canTop..hitBounds.canBottom
                    ) {
                        onCanClick()
                    }
                }
            }
            .graphicsLayer()
    ) {
        val snap = snapshotProvider()
        val nc = drawContext.canvas.nativeCanvas
        val w = size.width
        val h = size.height
        val cornerPx = 4f * density
        bgRect.set(0f, 0f, w, h)
        nc.drawRoundRect(bgRect, cornerPx, cornerPx, bgPaint)

        val padX = 8f * density
        val pillGap = 10f * density
        val labelValGap = 3.5f * density

        val colorGreen = 0xFF00E676.toInt()
        val colorMint = 0xFF69F0AE.toInt()
        val colorAmber = 0xFFFFB300.toInt()
        val colorRed = 0xFFFF5252.toInt()
        val colorMuted = 0xFF78909C.toInt()
        val colorCyan = 0xFF80D8FF.toInt()

        fun toneColor(tone: SensorHudTone): Int = when (tone) {
            SensorHudTone.GREEN -> colorGreen
            SensorHudTone.YELLOW -> colorAmber
            SensorHudTone.CYAN -> colorCyan
            SensorHudTone.RED -> colorRed
        }

        val radarColor = toneColor(snap.radarPill.tone)
        val camColor = toneColor(snap.camPill.tone)
        val gpsColor = toneColor(snap.gpsPill.tone)
        val imuColor = toneColor(snap.imuPill.tone)
        val syncColor = when (snap.syncGrade) {
            "GOLD" -> colorGreen
            "SILVER" -> colorMint
            "WARN" -> colorAmber
            "STALL", "ERROR" -> colorRed
            else -> colorMuted
        }
        val syncText = if (snap.isSyncActive) {
            String.format(Locale.US, "%.1fms", snap.syncDeltaMs)
        } else {
            "IDLE"
        }

        val canColor = toneColor(snap.canPill.tone)

        val svColor = when {
            snap.satellitesUsed >= GnssLocationManager.MIN_SATELLITES_FOR_LOCK -> colorGreen
            snap.satellitesInView > 0 -> colorAmber
            else -> colorRed
        }
        val svText = "${snap.satellitesUsed}/${snap.satellitesInView}"

        val cpuColor = if (snap.cpuUsagePct > 80) colorRed else if (snap.cpuUsagePct > 40) colorAmber else colorGreen
        val cpuText = if (snap.cpuTempC != null) {
            String.format(Locale.US, "%d%% %.0f°C", snap.cpuUsagePct, snap.cpuTempC)
        } else {
            "${snap.cpuUsagePct}%"
        }

        val batColor = when {
            snap.batteryTempC >= 45f -> colorRed
            snap.batteryTempC >= 40f -> colorAmber
            else -> colorCyan
        }
        val batText = String.format(Locale.US, "BAT %d%% • %.1f°C", snap.batteryPct, snap.batteryTempC)

        fun measurePill(label: String, value: String): Float {
            return labelPaint.measureText(label) + labelValGap + valuePaint.measureText(value)
        }

        fun drawPill(x: Float, baselineY: Float, label: String, value: String, valColor: Int, gap: Float = pillGap): Float {
            nc.drawText(label, x, baselineY, labelPaint)
            val lw = labelPaint.measureText(label)
            val vx = x + lw + labelValGap
            valuePaint.color = valColor
            nc.drawText(value, vx, baselineY, valuePaint)
            val vw = valuePaint.measureText(value)
            return vx + vw + gap
        }

        val textOffsetY = (valuePaint.descent() + valuePaint.ascent()) / 2f

        if (isLandscape) {
            val baseY = (h / 2f) - textOffsetY
            val fullLandLeftWidth = padX +
                measurePill("RADAR", snap.radarPill.fullValue) +
                measurePill("CAM", snap.camPill.fullValue) +
                measurePill("GPS", snap.gpsPill.fullValue) +
                measurePill("IMU", snap.imuPill.fullValue) +
                measurePill("SYNC", syncText) +
                measurePill("CAN", snap.canPill.fullValue) +
                (5f * pillGap)
            val useFullLand = fullLandLeftWidth <= (w * 0.62f)
            val rText = if (useFullLand) snap.radarPill.fullValue else snap.radarPill.compactValue
            val cText = if (useFullLand) snap.camPill.fullValue else snap.camPill.compactValue
            val gText = if (useFullLand) snap.gpsPill.fullValue else snap.gpsPill.compactValue
            val iText = if (useFullLand) snap.imuPill.fullValue else snap.imuPill.compactValue
            val canValText = if (useFullLand) snap.canPill.fullValue else snap.canPill.compactValue

            var curX = padX
            curX = drawPill(curX, baseY, "RADAR", rText, radarColor)
            curX = drawPill(curX, baseY, "CAM", cText, camColor)
            curX = drawPill(curX, baseY, "GPS", gText, gpsColor)
            curX = drawPill(curX, baseY, "IMU", iText, imuColor)

            val syncStartX = curX
            curX = drawPill(curX, baseY, "SYNC", syncText, syncColor)
            hitBounds.syncLeft = syncStartX - 4f * density
            hitBounds.syncRight = curX
            hitBounds.syncTop = 0f
            hitBounds.syncBottom = h

            val canStartX = curX
            curX = drawPill(curX, baseY, "CAN", canValText, canColor)
            hitBounds.canLeft = canStartX - 4f * density
            hitBounds.canRight = curX
            hitBounds.canTop = 0f
            hitBounds.canBottom = h

            // Center-right SVs & ACC
            var midX = (w * 0.62f).coerceAtLeast(curX + pillGap)
            midX = drawPill(midX, baseY, "SVs", svText, svColor)
            if (snap.accuracyMeters > 0f) {
                val accColor = if (snap.accuracyMeters <= 2.5f) colorGreen else colorAmber
                val accText = if (snap.accuracyMeters >= 10f) {
                    String.format(Locale.US, "±%.0fm", snap.accuracyMeters)
                } else {
                    String.format(Locale.US, "±%.1fm", snap.accuracyMeters)
                }
                drawPill(midX, baseY, "ACC", accText, accColor)
            }

            // Right-aligned CPU & Battery
            valuePaint.color = batColor
            val batW = valuePaint.measureText(batText)
            val batX = w - padX - batW
            nc.drawText(batText, batX, baseY, valuePaint)

            val cpuLabelW = labelPaint.measureText("CPU")
            val cpuValW = valuePaint.measureText(cpuText)
            val cpuStartX = batX - pillGap - cpuLabelW - labelValGap - cpuValW
            drawPill(cpuStartX, baseY, "CPU", cpuText, cpuColor)
        } else {
            val row1Y = (h * 0.28f) - textOffsetY
            val row2Y = (h * 0.76f) - textOffsetY

            val fullRow1Sum = measurePill("RADAR", snap.radarPill.fullValue) +
                measurePill("CAM", snap.camPill.fullValue) +
                measurePill("GPS", snap.gpsPill.fullValue) +
                measurePill("IMU", snap.imuPill.fullValue) +
                measurePill("SYNC", syncText)
            val useFullRow1 = (2f * padX + fullRow1Sum + 4f * pillGap) <= w

            val rText = if (useFullRow1) snap.radarPill.fullValue else snap.radarPill.compactValue
            val cText = if (useFullRow1) snap.camPill.fullValue else snap.camPill.compactValue
            val gText = if (useFullRow1) snap.gpsPill.fullValue else snap.gpsPill.compactValue
            val iText = if (useFullRow1) snap.imuPill.fullValue else snap.imuPill.compactValue

            val compactRow1Sum = if (useFullRow1) fullRow1Sum else (
                measurePill("RADAR", rText) +
                    measurePill("CAM", cText) +
                    measurePill("GPS", gText) +
                    measurePill("IMU", iText) +
                    measurePill("SYNC", syncText)
                )
            val r1Gap = if (2f * padX + compactRow1Sum + 4f * pillGap <= w) {
                pillGap
            } else {
                ((w - 2f * padX - compactRow1Sum) / 4f).coerceAtLeast(3f * density)
            }

            var curX = padX
            curX = drawPill(curX, row1Y, "RADAR", rText, radarColor, r1Gap)
            curX = drawPill(curX, row1Y, "CAM", cText, camColor, r1Gap)
            curX = drawPill(curX, row1Y, "GPS", gText, gpsColor, r1Gap)
            curX = drawPill(curX, row1Y, "IMU", iText, imuColor, r1Gap)

            val syncStartX = curX
            val syncEndX = drawPill(curX, row1Y, "SYNC", syncText, syncColor, r1Gap)
            hitBounds.syncLeft = syncStartX - 4f * density
            hitBounds.syncRight = syncEndX
            hitBounds.syncTop = 0f
            hitBounds.syncBottom = h * 0.55f

            // Row 2: Right CPU/BAT anchored first, CAN positioned cleanly without colliding with SVs/ACC
            valuePaint.color = batColor
            val batW = valuePaint.measureText(batText)
            val batX = w - padX - batW
            nc.drawText(batText, batX, row2Y, valuePaint)

            val cpuLabelW = labelPaint.measureText("CPU")
            val cpuValW = valuePaint.measureText(cpuText)
            val cpuStartX = batX - pillGap - cpuLabelW - labelValGap - cpuValW
            drawPill(cpuStartX, row2Y, "CPU", cpuText, cpuColor)

            var r2X = padX
            r2X = drawPill(r2X, row2Y, "SVs", svText, svColor, 6f * density)

            val canFullW = measurePill("CAN", snap.canPill.fullValue)
            val useFullCan = (r2X + canFullW + pillGap <= cpuStartX)
            val canText = if (useFullCan) snap.canPill.fullValue else snap.canPill.compactValue
            val canTotalW = if (useFullCan) canFullW else measurePill("CAN", canText)
            val canStartX = (cpuStartX - pillGap - canTotalW).coerceAtLeast(r2X)

            if (snap.accuracyMeters > 0f) {
                val accColor = if (snap.accuracyMeters <= 2.5f) colorGreen else colorAmber
                val accText = if (snap.accuracyMeters >= 10f) {
                    String.format(Locale.US, "±%.0fm", snap.accuracyMeters)
                } else {
                    String.format(Locale.US, "±%.1fm", snap.accuracyMeters)
                }
                if (r2X + measurePill("ACC", accText) + 4f * density <= canStartX) {
                    drawPill(r2X, row2Y, "ACC", accText, accColor)
                }
            }

            val canEndX = drawPill(canStartX, row2Y, "CAN", canText, canColor)
            hitBounds.canLeft = canStartX - 4f * density
            hitBounds.canRight = canEndX
            hitBounds.canTop = h * 0.50f
            hitBounds.canBottom = h
        }
    }
}
