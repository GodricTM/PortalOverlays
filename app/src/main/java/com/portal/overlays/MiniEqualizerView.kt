package com.portal.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * A tiny transparent equalizer: a few accent-coloured bars that pulse while audio is playing and
 * settle to short stubs when paused. Built for the slim Now Playing edge bar, so unlike
 * [NowPlayingVisualizerView] it never paints a background.
 */
class MiniEqualizerView(context: Context) : View(context) {
    var accentColor: Int = 0xFF4C8DFF.toInt()
        set(value) { field = value; invalidate() }

    var playing: Boolean = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation() else invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bars = 4

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gapRatio = 0.55f
        val barW = w / (bars + (bars - 1) * gapRatio)
        val gap = barW * gapRatio
        val radius = barW * 0.45f
        val t = SystemClock.uptimeMillis() / 1000f
        paint.color = accentColor

        for (i in 0 until bars) {
            val energy = if (playing)
                0.25f + 0.75f * abs(sin(t * 3.1f + i * 0.9f))
            else
                0.18f + 0.05f * i
            val bh = (h * energy).coerceIn(h * 0.12f, h)
            val x = i * (barW + gap)
            rect.set(x, h - bh, x + barW, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        if (playing) postInvalidateOnAnimation()
    }
}
