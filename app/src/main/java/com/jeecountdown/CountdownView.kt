package com.jeecountdown

import android.content.Context
import android.graphics.Canvas
import android.view.View
import kotlin.random.Random

// Draws the countdown; in dim mode it drifts a few pixels every 30s to avoid burn-in
class CountdownView(ctx: Context, private val dim: Boolean) : View(ctx) {
    private val renderer = Renderer(ctx)
    private var dx = 0f
    private var dy = 0f

    private val tick = object : Runnable {
        override fun run() {
            if (dim) {
                val m = minOf(width, height) * 0.03f
                dx = (Random.nextFloat() * 2f - 1f) * m
                dy = (Random.nextFloat() * 2f - 1f) * m
            }
            invalidate()
            postDelayed(this, 30_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(dx, dy)
        renderer.draw(canvas, width.toFloat(), height.toFloat(), dim)
        canvas.restore()
    }
}
