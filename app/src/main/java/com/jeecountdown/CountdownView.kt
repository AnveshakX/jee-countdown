package com.jeecountdown

import android.content.Context
import android.graphics.Canvas
import android.view.View
import kotlin.random.Random

// Draws the countdown. Used for the live preview (dim = false) and desk mode / screensaver (dim = true).
// In dim mode it drifts a few pixels every 30s to avoid burn-in.
class CountdownView(ctx: Context, private val dim: Boolean) : View(ctx) {
    private val renderer = Renderer(ctx)
    private var dx = 0f
    private var dy = 0f
    private var lastDrift = 0L
    private val again = Runnable { invalidate() }

    override fun onDetachedFromWindow() {
        removeCallbacks(again)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (dim && now - lastDrift > 30_000L) {
            lastDrift = now
            val m = minOf(width, height) * 0.03f
            dx = (Random.nextFloat() * 2f - 1f) * m
            dy = (Random.nextFloat() * 2f - 1f) * m
        }
        canvas.save()
        canvas.translate(dx, dy)
        renderer.draw(canvas, width.toFloat(), height.toFloat(), dim)
        canvas.restore()
        removeCallbacks(again)
        postDelayed(again, renderer.nextDelayMs(dim))
    }
}
