package com.jeecountdown

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Decides whether continuous animation is allowed. Animations stop in battery saver and when the user has
 * turned system animations off ("remove animations" / animator duration scale = 0).
 * The result is cached briefly so it is cheap to query every frame.
 */
object MotionPolicy {
    private const val CACHE_MS = 2_000L
    private var checkedAt = Long.MIN_VALUE
    private var allowed = true

    fun animationsAllowed(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (checkedAt == Long.MIN_VALUE || now - checkedAt > CACHE_MS) {
            checkedAt = now
            val app = ctx.applicationContext
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val scale = try {
                Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            } catch (e: Exception) {
                1f
            }
            allowed = !pm.isPowerSaveMode && scale > 0f
        }
        return allowed
    }
}
