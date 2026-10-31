
package com.neoplusair.neo.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated holographic circular interface rendered on a [Canvas]:
 * rotating tick rings, sweeping radar arc, glowing core and HUD brackets.
 * Tony Stark / JARVIS inspired — black background, electric blue glow.
 */
class HoloDashboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var phase = 0f
    private var statsText = ""
    private val lines = mutableListOf<String>()

    private val cyan = Color.parseColor("#00E5FF")
    private val blue = Color.parseColor("#2979FF")
    private val dim = Color.parseColor("#0A2540")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dim; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; textSize = 24f; typeface = Typeface.MONOSPACE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF"); textSize = 26f
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private var sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; alpha = 160
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setStats(text: String) {
        statsText = text
        lines.clear()
        lines.addAll(text.lines().filter { it.isNotBlank() }.take(6))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        phase += 0.55f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.46f

        canvas.drawColor(Color.BLACK)
        drawRings(canvas, cx, cy, r)
        drawCore(canvas, cx, cy, r)
        drawHud(canvas, r)
        postInvalidateOnAnimation()
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // static rings
        canvas.drawCircle(cx, cy, r, dimPaint)
        canvas.drawCircle(cx, cy, r * 0.72f, ringPaint)
        canvas.drawCircle(cx, cy, r * 0.30f, dimPaint)

        // rotating tick ring (outer)
        val ticks = 60
        for (i in 0 until ticks) {
            val a = Math.toRadians((i * 6.0) + phase)
            val len = if (i % 5 == 0) r * 0.08f else r * 0.04f
            val x1 = cx + (r - len) * kotlin.math.cos(a).toFloat()
            val y1 = cy + (r - len) * sin(a).toFloat()
            val x2 = cx + r * kotlin.math.cos(a).toFloat()
            val y2 = cy + r * sin(a).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // counter-rotating dashed ring
        val dash = Path()
        val dr = r * 0.86f
        for (i in 0..360 step 4) {
            val a = Math.toRadians(i - phase * 2.0)
            val x = cx + dr * kotlin.math.cos(a).toFloat()
            val y = cy + dr * sin(a).toFloat()
            if (i == 0) dash.moveTo(x, y) else if (i % 16 < 4) dash.lineTo(x, y) else dash.moveTo(x, y)
        }
        canvas.drawPath(dash, dimPaint)

        // radar sweep
        sweepPaint.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, cyan, Color.TRANSPARENT),
            floatArrayOf(0f, ((phase * 2f) % 360f) / 360f, (((phase * 2f) % 360f) + 60f) / 360f))
        canvas.drawCircle(cx, cy, r * 0.72f, sweepPaint)
        sweepPaint.shader = null
    }

    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val pulse = (sin(phase / 18f) + 1f) / 2f
        // glow halo
        canvas.drawCircle(cx, cy, r * (0.16f + 0.05f * pulse), glowPaint)
        // core
        canvas.drawCircle(cx, cy, r * 0.10f, corePaint)
        canvas.drawCircle(cx, cy, r * 0.14f, ringPaint)
        // orbiting satellites
        for (i in 0..2) {
            val a = Math.toRadians(phase * 3.0 + i * 120.0)
            val sx = cx + r * 0.58f * kotlin.math.cos(a).toFloat()
            val sy = cy + r * 0.58f * sin(a).toFloat()
            canvas.drawCircle(sx, sy, 6f, corePaint)
        }
    }

    private fun drawHud(canvas: Canvas, r: Float) {
        val l = 42f
        val corners = arrayOf(
            floatArrayOf(l, l, l, l + l, l, l, l, l, l, l + l),
            floatArrayOf(width - l, l, width - l - l, l, width - l, l, width - l, l, width - l, l + l),
            floatArrayOf(l, height - l, l + l, height - l, l, height - l, l, height - l, l, height - l - l),
            floatArrayOf(width - l, height - l, width - l - l, height - l, width - l, height - l, width - l, height - l - l)
        )
        corners.forEach { c -> canvas.drawLine(c[0], c[1], c[2], c[3], tickPaint); canvas.drawLine(c[4], c[5], c[6], c[7], tickPaint) }

        var y = 84f
        lines.forEach { canvas.drawText(it, 84f, y, textPaint); y += 34f }
        canvas.drawText("NEO PLUS AIR // PERSONAL AI OS", width / 2f, height - 72f, titlePaint)
    }
}
