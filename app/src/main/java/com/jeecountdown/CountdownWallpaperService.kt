package com.jeecountdown

import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class CountdownWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CountdownEngine()

    inner class CountdownEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = Renderer(this@CountdownWallpaperService)
        private var visible = false
        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 30_000)
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(tick)
            if (v) tick.run()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(tick)
            super.onDestroy()
        }

        private fun draw() {
            val c = surfaceHolder.lockCanvas() ?: return
            try {
                renderer.draw(c, c.width.toFloat(), c.height.toFloat(), false)
            } finally {
                surfaceHolder.unlockCanvasAndPost(c)
            }
        }
    }
}
