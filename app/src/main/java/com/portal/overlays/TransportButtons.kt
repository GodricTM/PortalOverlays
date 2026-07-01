package com.portal.overlays

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import kotlin.math.roundToInt

/** Vector transport controls for now-playing (edge bar, strip, full card, bubble badge). */
object TransportButtons {
    fun playPauseIcon(playing: Boolean): Int =
        if (playing) R.drawable.ic_transport_pause else R.drawable.ic_transport_play

    fun applyPlayState(view: ImageView?, playing: Boolean) {
        view?.setImageResource(playPauseIcon(playing))
    }

    fun makeButton(
        ctx: Context,
        iconRes: Int,
        diameterDp: Int,
        backgroundColor: Int,
        iconPaddingDp: Int = 0,
        accentRing: Boolean = false,
        onClick: () -> Unit,
    ): ImageView {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundColor)
            if (accentRing) setStroke(px(2), 0x55FFFFFF)
        }
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(iconRes)
            background = bg
            val pad = px(iconPaddingDp)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.72f
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }.also {
            it.layoutParams = android.view.ViewGroup.LayoutParams(px(diameterDp), px(diameterDp))
        }
    }

    fun makePillButton(
        ctx: Context,
        iconRes: Int,
        widthDp: Int,
        heightDp: Int,
        cornerDp: Int,
        backgroundColor: Int,
        iconPaddingDp: Int = 14,
        onClick: () -> Unit,
    ): ImageView {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(cornerDp).toFloat()
            setColor(backgroundColor)
        }
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(iconRes)
            background = bg
            val pad = px(iconPaddingDp)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.72f
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }.also {
            it.layoutParams = android.view.ViewGroup.LayoutParams(px(widthDp), px(heightDp))
        }
    }

    fun makeBadge(ctx: Context, diameterDp: Int): ImageView {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_transport_play)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAA000000.toInt())
            }
            val pad = px(6)
            setPadding(pad, pad, pad, pad)
            isClickable = false
            isFocusable = false
        }
    }
}
