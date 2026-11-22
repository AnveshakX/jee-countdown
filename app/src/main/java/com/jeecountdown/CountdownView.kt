package com.jeecountdown

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import kotlin.random.Random

/**
 * Draws the countdown. Used for the live preview (dim = false) and desk mode / screensaver (dim = true).
 * In dim mode it drifts a few pixels every 30s to avoid burn-in.
 * The redraw loop exists only while the view is drawn: it stops on its own when the view is hidden, and
 * is restarted when the window becomes visible again.
 */
class CountdownView(ctx: Context, private val dim: Boolean) : View(ctx) {
    private companion object {
        const val DRIFT_INTERVAL_MS = 30_000L
        const val DRIFT_FRACTION = 0.03f
    }

    private val renderer = Renderer(ctx)
    private var dx = 0f
    private var dy = 0f
    private var lastDrift = Long.MIN_VALUE
    private val again = Runnable { invalidate() }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate() // the loop pauses while hidden; kick it again
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(again)
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime() // monotonic: unaffected by wall-clock changes
        if (dim && (lastDrift == Long.MIN_VALUE || now - lastDrift > DRIFT_INTERVAL_MS)) {
            lastDrift = now
            val m = minOf(width, height) * DRIFT_FRACTION
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
