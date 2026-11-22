package com.projectreclaim.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Draws a fixed, portrait card for both the preview and the PNG. */
internal object ShareCardImage {
    private const val WIDTH = 720
    private const val HEIGHT = 900
    private const val INK = 0xFF1D302B.toInt()
    private const val MUTED = 0xFF52615B.toInt()
    private const val GREEN = 0xFF185C50.toInt()
    private const val MINT = 0xFFDDEDE5.toInt()

    fun render(summary: ShareSummary): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF5F6F2.toInt())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        fill.color = GREEN
        canvas.drawCircle(64f, 65f, 10f, fill)
        canvas.label("PROJECT RECLAIM", 88f, 73f, 24f, GREEN, bold = true)
        canvas.label(summary.scope, 54f, 125f, 34f, INK, bold = true)
        canvas.label(summary.dateLabel, 54f, 164f, 25f, MUTED)

        canvas.panel(fill, 190f, 420f, GREEN)
        canvas.label("SHORT-FORM USAGE", 76f, 239f, 22f, MINT, bold = true)
        canvas.label(summary.totalLabel, 76f, 332f, 78f, Color.WHITE, bold = true, maxWidth = 568f)
        canvas.label(summary.status, 76f, 388f, 25f, MINT, maxWidth = 568f)

        canvas.label("BREAKDOWN", 54f, 470f, 22f, GREEN, bold = true)
        canvas.panel(fill, 491f, 718f, Color.WHITE)
        summary.breakdown.forEachIndexed { index, (name, value) ->
            val baseline = 542f + index * 75f
            canvas.label(name, 76f, baseline, 27f, INK)
            canvas.label(value, 644f, baseline, 28f, INK, bold = true, rightAligned = true)
            if (index < 2) {
                fill.color = 0xFFDCE3DC.toInt()
                fill.strokeWidth = 2f
                canvas.drawLine(76f, baseline + 25f, 644f, baseline + 25f, fill)
            }
        }

        val notes = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textSize = 23f
        }
        canvas.save()
        canvas.translate(54f, 755f)
        StaticLayout.Builder.obtain(summary.measurementNote, 0, summary.measurementNote.length, notes, 612)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(6f, 1f)
            .build()
            .draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun Canvas.panel(paint: Paint, top: Float, bottom: Float, color: Int) {
        paint.color = color
        drawRoundRect(42f, top, 678f, bottom, 28f, 28f, paint)
    }

    private fun Canvas.label(
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        rightAligned: Boolean = false,
        maxWidth: Float = Float.MAX_VALUE,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = if (rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
            val width = measureText(text)
            if (width > maxWidth) textSize *= maxWidth / width
        }
        drawText(text, x, baseline, paint)
    }
}
