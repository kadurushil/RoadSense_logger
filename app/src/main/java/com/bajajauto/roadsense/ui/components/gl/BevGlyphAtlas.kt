package com.bajajauto.roadsense.ui.components.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * Pure-Kotlin glyph UV & advance metrics for the 512×256 single-channel (`GL_R8`, 128 KB VRAM)
 * monospace GPU font atlas covering ASCII 32..126 + bullet `'•'` (`\u2022`).
 *
 * Grid layout: 16 columns × 8 rows of 32×32 px cells (96 glyphs occupy rows 0..5).
 */
class BevGlyphAtlasMetrics(
    val atlasWidth: Int = ATLAS_WIDTH,
    val atlasHeight: Int = ATLAS_HEIGHT,
    val cols: Int = GRID_COLS,
    val rows: Int = GRID_ROWS,
    val cellPx: Int = CELL_PX,
    val baseTextSizePx: Float = BASE_TEXT_SIZE_PX,
    val baselineInCellPx: Float = BASELINE_IN_CELL_PX,
    val glyphAdvancesPx: FloatArray = FloatArray(GLYPH_COUNT) { DEFAULT_ADVANCE_PX }
) {
    companion object {
        const val ATLAS_WIDTH = 512
        const val ATLAS_HEIGHT = 256
        const val GRID_COLS = 16
        const val GRID_ROWS = 8
        const val CELL_PX = 32
        const val FIRST_CHAR = 32
        const val LAST_CHAR = 126
        const val BULLET_CHAR = '\u2022'
        const val BULLET_INDEX = 95
        const val GLYPH_COUNT = 96
        const val BASE_TEXT_SIZE_PX = 22f
        const val BASELINE_IN_CELL_PX = 24f
        const val DEFAULT_ADVANCE_PX = 13.5f

        fun charForIndex(index: Int): Char = when {
            index in 0..(LAST_CHAR - FIRST_CHAR) -> (FIRST_CHAR + index).toChar()
            index == BULLET_INDEX -> BULLET_CHAR
            else -> '?'
        }

        fun indexForChar(ch: Char): Int = when {
            ch.code in FIRST_CHAR..LAST_CHAR -> ch.code - FIRST_CHAR
            ch == BULLET_CHAR -> BULLET_INDEX
            else -> '?'.code - FIRST_CHAR
        }

        fun defaultForTests(): BevGlyphAtlasMetrics = BevGlyphAtlasMetrics()
    }

    private val halfTexelU = 0.5f / atlasWidth.toFloat()
    private val halfTexelV = 0.5f / atlasHeight.toFloat()
    private val cellU = cellPx.toFloat() / atlasWidth.toFloat()
    private val cellV = cellPx.toFloat() / atlasHeight.toFloat()

    fun u0(glyphIdx: Int): Float = (glyphIdx % cols) * cellU + halfTexelU
    fun u1(glyphIdx: Int): Float = (glyphIdx % cols + 1) * cellU - halfTexelU
    fun v0(glyphIdx: Int): Float = (glyphIdx / cols) * cellV + halfTexelV
    fun v1(glyphIdx: Int): Float = (glyphIdx / cols + 1) * cellV - halfTexelV

    fun scaleForTextSize(desiredTextSizePx: Float): Float =
        desiredTextSizePx / baseTextSizePx

    fun advancePx(glyphIdx: Int, scale: Float): Float =
        glyphAdvancesPx[glyphIdx.coerceIn(0, GLYPH_COUNT - 1)] * scale

    fun measureWidth(text: CharSequence, desiredTextSizePx: Float): Float {
        val scale = scaleForTextSize(desiredTextSizePx)
        var total = 0f
        for (i in 0 until text.length) {
            total += advancePx(indexForChar(text[i]), scale)
        }
        return total
    }
}

/**
 * Android-side one-time GPU texture builder for [BevGlyphAtlasMetrics].
 * Called strictly on `GLThread` inside `onSurfaceCreated`.
 */
object BevGlyphAtlasUploader {

    data class UploadedAtlas(
        val textureId: Int,
        val metrics: BevGlyphAtlasMetrics
    )

    fun createAndUpload(): UploadedAtlas {
        val w = BevGlyphAtlasMetrics.ATLAS_WIDTH
        val h = BevGlyphAtlasMetrics.ATLAS_HEIGHT
        val cell = BevGlyphAtlasMetrics.CELL_PX
        val cols = BevGlyphAtlasMetrics.GRID_COLS
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        bitmap.eraseColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = BevGlyphAtlasMetrics.BASE_TEXT_SIZE_PX
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }

        val advances = FloatArray(BevGlyphAtlasMetrics.GLYPH_COUNT)
        val charBuf = CharArray(1)
        for (idx in 0 until BevGlyphAtlasMetrics.GLYPH_COUNT) {
            val ch = BevGlyphAtlasMetrics.charForIndex(idx)
            charBuf[0] = ch
            val adv = paint.measureText(charBuf, 0, 1).coerceIn(8f, cell.toFloat())
            advances[idx] = adv

            val col = idx % cols
            val row = idx / cols
            val cx = col * cell + cell * 0.5f
            val baselineY = row * cell + BevGlyphAtlasMetrics.BASELINE_IN_CELL_PX
            canvas.drawText(charBuf, 0, 1, cx, baselineY, paint)
        }

        val pixels = ByteBuffer.allocateDirect(w * h)
        bitmap.copyPixelsToBuffer(pixels)
        pixels.position(0)
        bitmap.recycle()

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            w,
            h,
            0,
            GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE,
            pixels
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        return UploadedAtlas(
            textureId = texId,
            metrics = BevGlyphAtlasMetrics(glyphAdvancesPx = advances)
        )
    }
}
