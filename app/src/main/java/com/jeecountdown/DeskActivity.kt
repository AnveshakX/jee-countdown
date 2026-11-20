package com.jeecountdown

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

// AOD-style desk mode: screen stays on at very low brightness, black background, tap to exit
@Suppress("DEPRECATION")
class DeskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0.05f
        window.attributes = lp
        val v = CountdownView(this, true)
        v.setOnClickListener { finish() }
        setContentView(v)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
