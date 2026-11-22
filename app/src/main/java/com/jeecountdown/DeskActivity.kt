package com.jeecountdown

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// AOD-style desk mode: screen stays on at very low brightness, black background, tap to exit.
// All window changes belong to this window only, so leaving the activity restores normal behaviour.
class DeskActivity : Activity() {
    private companion object {
        const val DESK_BRIGHTNESS = 0.05f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = DESK_BRIGHTNESS
        window.attributes = lp

        val v = CountdownView(this, true)
        v.setOnClickListener { finish() }
        setContentView(v)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
