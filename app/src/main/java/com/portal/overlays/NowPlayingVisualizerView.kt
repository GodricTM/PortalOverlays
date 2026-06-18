package com.portal.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class NowPlayingVisualizerView(context: Context) : View(context) {
    var accentColor: Int = 0xFF4C8DFF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var playing: Boolean = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation() else invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val t = SystemClock.uptimeMillis() / 1000f
        drawBackdrop(canvas, w, h, t)
        drawWaves(canvas, w, h, t)
        drawBars(canvas, w, h, t)

        if (playing) postInvalidateOnAnimation()
    }

    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float, t: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF080A0D.toInt(), 0xFF101620.toInt(), 0xFF07080B.toInt()),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        val pulse = if (playing) 0.72f + 0.18f * sin(t * 1.4f) else 0.56f
        paint.shader = RadialGradient(
            w * (0.72f + 0.035f * sin(t * 0.33f)),
            h * (0.42f + 0.045f * sin(t * 0.27f + 2f)),
            max(w, h) * 0.62f,
            alpha(accentColor, (72 * pulse).toInt()),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.72f, h * 0.42f, max(w, h) * 0.62f, paint)
        paint.shader = null
    }

    private fun drawWaves(canvas: Canvas, w: Float, h: Float, t: Float) {
        val mid = h * 0.69f
        for (layer in 0..2) {
            path.reset()
            val amp = h * (0.022f + layer * 0.012f) * if (playing) 1.0f else 0.38f
            val freq = 1.5f + layer * 0.55f
            var x = 0f
            path.moveTo(0f, mid)
            while (x <= w) {
                val y = mid +
                    amp * sin(((x / w) * freq * 2f * PI + t * (1.0f + layer * 0.28f)).toFloat()) +
                    amp * 0.55f * sin(((x / w) * (freq + 1.7f) * 2f * PI - t * 0.7f).toFloat())
                path.lineTo(x, y)
                x += 10f
            }
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f + layer * 1.1f
            paint.color = alpha(accentColor, 72 - layer * 17)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawBars(canvas: Canvas, w: Float, h: Float, t: Float) {
        val count = 64
        val span = w * 0.72f
        val gap = w * 0.0038f
        val barW = (span - gap * (count - 1)) / count
        val startX = w * 0.14f
        val baseY = h * 0.79f
        val maxH = h * 0.28f
        val idle = if (playing) 1f else 0.22f

        for (i in 0 until count) {
            val n = i / (count - 1f)
            val centerWeight = 1f - abs(n - 0.5f) * 0.9f
            val a = abs(sin(t * 2.4f + i * 0.29f))
            val b = abs(sin(t * 1.35f - i * 0.17f + 1.7f))
            val c = abs(sin(t * 3.1f + i * 0.071f))
            val energy = (0.18f + 0.44f * a + 0.28f * b + 0.10f * c) * centerWeight * idle
            val bh = maxH * energy.coerceIn(0.08f, 1f)
            val x = startX + i * (barW + gap)
            rect.set(x, baseY - bh, x + barW, baseY + bh * 0.18f)
            paint.shader = LinearGradient(
                x, rect.top, x, rect.bottom,
                alpha(Color.WHITE, 178),
                alpha(accentColor, 60 + (120 * energy).toInt().coerceIn(0, 120)),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, barW * 0.6f, barW * 0.6f, paint)
        }
        paint.shader = null
    }

    private fun alpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
