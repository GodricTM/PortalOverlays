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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class NowPlayingVisualizerView(context: Context) : View(context) {
    var accentColor: Int = 0xFF4C8DFF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var style: String = "waves"
        set(value) {
            field = value
            invalidate()
        }

    var playing: Boolean = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation() else invalidate()
        }

    /** When set and active, bars/rings move to live audio instead of the synthetic animation. */
    var reactor: SoundReactor? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    /** True while we should keep animating: either playing, or live audio is coming in. */
    private val animating get() = playing || (reactor?.active == true)

    // Per-bar displayed value, eased toward the target each frame so motion stays fluid even when the
    // reactor's band updates arrive in bursts (Portal's shared mic delivers choppy data).
    private val display = FloatArray(96)
    /** Brief boost when playback position jumps (phone skip) — decays each frame. */
    private var seekPulse = 0f

    /** Call when a large seek/skip is detected so the visualizer flares once. */
    fun pulseSeek() {
        seekPulse = 1f
        postInvalidateOnAnimation()
    }

    /** Energy 0..1 for a bar at position [frac]; live audio when reacting, else a synthetic wiggle. */
    private fun energy(frac: Float, t: Float, i: Int): Float {
        val r = reactor
        if (r != null && r.active) {
            val target = r.energyAt(frac)
            val idx = i.coerceIn(0, display.size - 1)
            display[idx] += (target - display[idx]) * 0.3f
            return display[idx]
        }
        val idle = if (playing) 1f else 0.22f
        val a = abs(sin(t * 2.4f + i * 0.29f))
        val b = abs(sin(t * 1.35f - i * 0.17f + 1.7f))
        val c = abs(sin(t * 3.1f + i * 0.071f))
        return (0.18f + 0.44f * a + 0.28f * b + 0.10f * c) * idle * (1f + seekPulse * 0.85f)
    }

    override fun onDraw(canvas: Canvas) {
        if (seekPulse > 0.02f) {
            seekPulse *= 0.90f
            if (animating) postInvalidateOnAnimation()
        } else seekPulse = 0f
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val t = SystemClock.uptimeMillis() / 1000f
        when (style) {
            "rings" -> drawRingsScene(canvas, w, h, t)
            "constellation" -> drawConstellationScene(canvas, w, h, t)
            "prism" -> drawPrismScene(canvas, w, h, t)
            "spectrum" -> drawSpectrumScene(canvas, w, h, t)
            else -> drawWavesScene(canvas, w, h, t)
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawWavesScene(canvas: Canvas, w: Float, h: Float, t: Float) {
        drawBaseBackdrop(canvas, w, h, t, 0xFF101620.toInt(), 0xFF07080B.toInt())
        drawAccentGlow(canvas, w * 0.72f, h * 0.42f, max(w, h) * 0.62f, 72, t)
        drawWaves(canvas, w, h, t)
        drawBars(canvas, w, h, t)
    }

    private fun drawRingsScene(canvas: Canvas, w: Float, h: Float, t: Float) {
        drawBaseBackdrop(canvas, w, h, t, 0xFF0A0E14.toInt(), 0xFF07080B.toInt())
        val cx = w * 0.5f
        val cy = h * 0.5f
        drawAccentGlow(canvas, cx, cy, max(w, h) * 0.56f, 86, t)

        paint.style = Paint.Style.STROKE
        for (i in 0..5) {
            val pulse = if (playing) 1f + 0.06f * sin(t * (1.2f + i * 0.18f) + i) else 0.96f
            val radius = min(w, h) * (0.12f + i * 0.065f) * pulse
            paint.strokeWidth = 2.5f + i * 1.4f
            paint.color = alpha(accentColor, 108 - i * 12)
            canvas.drawCircle(cx, cy, radius, paint)
        }

        for (i in 0 until 48) {
            val angle = (i / 48f) * 2f * PI.toFloat() + t * 0.35f
            val inner = min(w, h) * 0.17f
            val outer = inner + min(w, h) * (0.04f + 0.085f * abs(sin(t * 1.8f + i * 0.33f))) * if (playing) 1f else 0.35f
            val x1 = cx + cos(angle) * inner
            val y1 = cy + sin(angle) * inner
            val x2 = cx + cos(angle) * outer
            val y2 = cy + sin(angle) * outer
            paint.strokeWidth = dpf(2f)
            paint.color = alpha(Color.WHITE, 130)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawConstellationScene(canvas: Canvas, w: Float, h: Float, t: Float) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF05070A.toInt(), 0xFF0B1220.toInt(), 0xFF040507.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        drawAccentGlow(canvas, w * 0.32f, h * 0.34f, max(w, h) * 0.48f, 82, t)

        val nodes = 18
        val points = ArrayList<Pair<Float, Float>>(nodes)
        for (i in 0 until nodes) {
            val nx = w * (0.14f + (i % 6) * 0.14f) + w * 0.04f * sin(t * 0.42f + i)
            val ny = h * (0.22f + (i / 6) * 0.18f) + h * 0.05f * cos(t * 0.36f + i * 0.7f)
            points += nx to ny
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpf(1.4f)
        for (i in 0 until points.size) {
            val (x1, y1) = points[i]
            for (j in i + 1 until min(i + 4, points.size)) {
                val (x2, y2) = points[j]
                val alpha = if (playing) 62 else 28
                paint.color = alpha(accentColor, alpha)
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
        }

        paint.style = Paint.Style.FILL
        points.forEachIndexed { i, (x, y) ->
            val r = dpf(3f) + dpf(3f) * abs(sin(t * 1.6f + i * 0.55f)) * if (playing) 1f else 0.4f
            paint.color = alpha(Color.WHITE, 190)
            canvas.drawCircle(x, y, r, paint)
            paint.color = alpha(accentColor, 110)
            canvas.drawCircle(x, y, r * 2.6f, paint)
        }

        drawBars(canvas, w, h, t)
    }

    private fun drawPrismScene(canvas: Canvas, w: Float, h: Float, t: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF090B10.toInt(), 0xFF15111A.toInt(), 0xFF07080B.toInt()),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        drawAccentGlow(canvas, w * 0.68f, h * 0.30f, max(w, h) * 0.54f, 78, t)

        paint.style = Paint.Style.FILL
        for (i in 0 until 9) {
            val size = min(w, h) * (0.08f + i * 0.032f)
            val cx = w * (0.22f + (i % 3) * 0.24f) + w * 0.03f * sin(t * 0.42f + i)
            val cy = h * (0.28f + (i / 3) * 0.16f) + h * 0.035f * cos(t * 0.36f + i)
            val tilt = t * 0.28f + i * 0.35f
            path.reset()
            for (corner in 0 until 4) {
                val angle = tilt + corner * (PI.toFloat() / 2f)
                val px = cx + cos(angle) * size
                val py = cy + sin(angle) * size * 0.66f
                if (corner == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            paint.color = alpha(accentColor, 28 + i * 10)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpf(1.8f)
            paint.color = alpha(Color.WHITE, 44 + i * 7)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }

        drawWaves(canvas, w, h, t)
    }

    private fun drawSpectrumScene(canvas: Canvas, w: Float, h: Float, t: Float) {
        drawBaseBackdrop(canvas, w, h, t, 0xFF0B0F18.toInt(), 0xFF07080B.toInt())
        val lvl = reactor?.let { if (it.active) it.level else 0f } ?: 0f
        drawAccentGlow(canvas, w * 0.5f, h * 0.5f, max(w, h) * (0.40f + 0.18f * lvl), 72, t)

        val count = 56
        val span = w * 0.84f
        val gap = w * 0.004f
        val barW = (span - gap * (count - 1)) / count
        val startX = (w - span) / 2f
        val midY = h * 0.52f
        val maxH = h * 0.34f
        for (i in 0 until count) {
            // Mirror around the centre so the spectrum reads low → high → low across the width.
            val frac = abs(i / (count - 1f) - 0.5f) * 2f
            val e = energy(frac, t, i).coerceIn(0.04f, 1f)
            val bh = maxH * e
            val x = startX + i * (barW + gap)
            rect.set(x, midY - bh, x + barW, midY - barW * 0.5f)
            paint.shader = LinearGradient(
                x, rect.top, x, rect.bottom,
                alpha(Color.WHITE, 210), alpha(accentColor, 95), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, barW * 0.5f, barW * 0.5f, paint)
            // Dim mirrored reflection below the centre line.
            rect.set(x, midY + barW * 0.5f, x + barW, midY + bh * 0.7f)
            paint.shader = LinearGradient(
                x, rect.top, x, rect.bottom,
                alpha(accentColor, 90), alpha(accentColor, 0), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, barW * 0.5f, barW * 0.5f, paint)
        }
        paint.shader = null
    }

    private fun drawBaseBackdrop(canvas: Canvas, w: Float, h: Float, t: Float, midColor: Int, endColor: Int) {
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF080A0D.toInt(), midColor, endColor),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawAccentGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, alphaBase: Int, t: Float) {
        val pulse = if (playing) 0.72f + 0.18f * sin(t * 1.4f) else 0.56f
        paint.shader = RadialGradient(
            cx + radius * 0.04f * sin(t * 0.33f),
            cy + radius * 0.05f * sin(t * 0.27f + 2f),
            radius,
            alpha(accentColor, (alphaBase * pulse).toInt()),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
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

        for (i in 0 until count) {
            val n = i / (count - 1f)
            val centerWeight = 1f - abs(n - 0.5f) * 0.9f
            val energy = energy(n, t, i) * centerWeight
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

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    private fun alpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
