package com.jeecountdown

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

class CountdownWallpaperService : WallpaperService() {

    private companion object {
        const val TAG = "CountdownWallpaper"
        const val CHANGE_DEBOUNCE_MS = 50L   // coalesces slider drags into one redraw
        const val MAX_DRAW_ERRORS = 5
    }

    override fun onCreateEngine(): Engine = CountdownEngine()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) PhotoCache.release()
    }

    inner class CountdownEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = Renderer(this@CountdownWallpaperService.applicationContext)
        private var visible = false
        private var surfaceReady = false
        private var drawErrors = 0

        // The ONLY animation loop. Every start path removes any pending callback first, so loops never duplicate.
        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (visible && surfaceReady && drawErrors < MAX_DRAW_ERRORS) {
                    handler.postDelayed(this, renderer.nextDelayMs(false))
                }
            }
        }

        private val redraw = Runnable { restartLoop() }

        // Settings or countdowns changed in the app: redraw promptly (debounced)
        private val listener: () -> Unit = {
            handler.removeCallbacks(redraw)
            handler.postDelayed(redraw, CHANGE_DEBOUNCE_MS)
        }

        private fun restartLoop() {
            handler.removeCallbacks(frame)
            if (!surfaceReady) return
            if (visible) frame.run() else draw()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            CfgStore.listeners.add(listener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            restartLoop()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        // Screen off / launcher covered / another app on top => visible = false => the loop stops.
        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(frame)
            if (v) {
                drawErrors = 0
                if (surfaceReady) frame.run()
            }
        }

        override fun onDestroy() {
            visible = false
            surfaceReady = false
            handler.removeCallbacksAndMessages(null)
            CfgStore.listeners.remove(listener)
            renderer.release()
            super.onDestroy()
        }

        private fun draw() {
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas()
                if (c != null) renderer.draw(c, c.width.toFloat(), c.height.toFloat(), false)
            } catch (e: Exception) {
                drawErrors++
                Log.w(TAG, "draw failed ($drawErrors/$MAX_DRAW_ERRORS)", e)
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c)
                    } catch (e: Exception) {
                        Log.w(TAG, "unlockCanvasAndPost failed", e)
                    }
                }
            }
        }
    }
}
