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
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.sync.LiveSyncTelemetry
import com.bajajauto.roadsense.ui.LiveMetricsSnapshot
import com.bajajauto.roadsense.ui.RadarViewModel
import java.util.Locale

private class HudHitBounds {
    var syncLeft: Float = 0f
    var syncRight: Float = 0f
    var syncTop: Float = 0f
    var syncBottom: Float = 0f
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
    modifier: Modifier = Modifier
) {
    val snapshotState = viewModel.liveMetricsSnapshot.collectAsState()
    LiveMetricsCanvasHud(
        snapshotProvider = { snapshotState.value },
        onSyncClick = onSyncClick,
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
        isSyncActive = syncTelemetry.isCameraActive && syncTelemetry.isImuActive
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
            .pointerInput(onSyncClick) {
                detectTapGestures { offset ->
                    if (offset.x in hitBounds.syncLeft..hitBounds.syncRight &&
                        offset.y in hitBounds.syncTop..hitBounds.syncBottom
                    ) {
                        onSyncClick()
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

        val radarColor = if (snap.radarHz > 15f) colorGreen else if (snap.radarHz > 0f) colorAmber else colorMuted
        val camColor = if (snap.cameraFps > 20f) colorGreen else if (snap.cameraFps > 0f) colorAmber else colorMuted
        val gpsColor = if (snap.gnssHz > 0.5f) colorGreen else colorMuted
        val imuColor = if (snap.imuHz > 80f) colorGreen else if (snap.imuHz > 0f) colorAmber else colorMuted
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

        val svColor = if (snap.satellitesUsed >= 6) colorGreen else colorAmber
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

        fun drawPill(x: Float, baselineY: Float, label: String, value: String, valColor: Int): Float {
            nc.drawText(label, x, baselineY, labelPaint)
            val lw = labelPaint.measureText(label)
            val vx = x + lw + labelValGap
            valuePaint.color = valColor
            nc.drawText(value, vx, baselineY, valuePaint)
            val vw = valuePaint.measureText(value)
            return vx + vw + pillGap
        }

        val textOffsetY = (valuePaint.descent() + valuePaint.ascent()) / 2f

        if (isLandscape) {
            val baseY = (h / 2f) - textOffsetY
            var curX = padX
            curX = drawPill(curX, baseY, "RADAR", String.format(Locale.US, "%.1f Hz", snap.radarHz), radarColor)
            curX = drawPill(curX, baseY, "CAM", String.format(Locale.US, "%.1f fps", snap.cameraFps), camColor)
            curX = drawPill(curX, baseY, "GPS", String.format(Locale.US, "%.1f Hz", snap.gnssHz), gpsColor)
            curX = drawPill(curX, baseY, "IMU", String.format(Locale.US, "%.1f Hz", snap.imuHz), imuColor)

            val syncStartX = curX
            curX = drawPill(curX, baseY, "SYNC", syncText, syncColor)
            hitBounds.syncLeft = syncStartX - 4f * density
            hitBounds.syncRight = curX
            hitBounds.syncTop = 0f
            hitBounds.syncBottom = h

            // Center-right SVs & ACC
            var midX = (w * 0.62f).coerceAtLeast(curX + pillGap)
            midX = drawPill(midX, baseY, "SVs", svText, svColor)
            if (snap.accuracyMeters > 0f) {
                val accColor = if (snap.accuracyMeters <= 2.5f) colorGreen else colorAmber
                drawPill(midX, baseY, "ACC", String.format(Locale.US, "±%.1fm", snap.accuracyMeters), accColor)
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
            var curX = padX
            curX = drawPill(curX, row1Y, "RADAR", String.format(Locale.US, "%.1f Hz", snap.radarHz), radarColor)
            curX = drawPill(curX, row1Y, "CAM", String.format(Locale.US, "%.1f fps", snap.cameraFps), camColor)
            curX = drawPill(curX, row1Y, "GPS", String.format(Locale.US, "%.1f Hz", snap.gnssHz), gpsColor)
            curX = drawPill(curX, row1Y, "IMU", String.format(Locale.US, "%.1f Hz", snap.imuHz), imuColor)

            val syncStartX = curX
            val syncEndX = drawPill(curX, row1Y, "SYNC", syncText, syncColor)
            hitBounds.syncLeft = syncStartX - 4f * density
            hitBounds.syncRight = syncEndX
            hitBounds.syncTop = 0f
            hitBounds.syncBottom = h * 0.55f

            // Row 2: Left SVs/ACC, Right CPU/BAT
            var r2X = padX
            r2X = drawPill(r2X, row2Y, "SVs", svText, svColor)
            if (snap.accuracyMeters > 0f) {
                val accColor = if (snap.accuracyMeters <= 2.5f) colorGreen else colorAmber
                drawPill(r2X, row2Y, "ACC", String.format(Locale.US, "±%.1fm", snap.accuracyMeters), accColor)
            }

            valuePaint.color = batColor
            val batW = valuePaint.measureText(batText)
            val batX = w - padX - batW
            nc.drawText(batText, batX, row2Y, valuePaint)

            val cpuLabelW = labelPaint.measureText("CPU")
            val cpuValW = valuePaint.measureText(cpuText)
            val cpuStartX = batX - pillGap - cpuLabelW - labelValGap - cpuValW
            drawPill(cpuStartX, row2Y, "CPU", cpuText, cpuColor)
        }
    }
}
