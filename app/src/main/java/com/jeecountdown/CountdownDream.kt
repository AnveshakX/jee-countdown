package com.jeecountdown

import android.service.dreams.DreamService

// Screensaver: shows the dim countdown while the tablet is charging/docked
class CountdownDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        setContentView(CountdownView(this, true))
    }
}
