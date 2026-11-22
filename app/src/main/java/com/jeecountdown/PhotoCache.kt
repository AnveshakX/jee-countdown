package com.jeecountdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * One shared, down-sampled decode of the user's background photo (kept private in app storage).
 * Two size classes (preview/widget vs full wallpaper) so a widget refresh never evicts the wallpaper's copy.
 */
object PhotoCache {
    const val FILE_NAME = "bg.jpg"
    const val TEMP_NAME = "bg.tmp"
    const val LARGE_SIDE = 2200
    const val SMALL_SIDE = 720
    private const val SMALL_LIMIT = 1024
    private const val TAG = "PhotoCache"

    private class Entry(val version: Long, val maxSide: Int, val bmp: Bitmap?)
    private var large: Entry? = null
    private var small: Entry? = null

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun get(ctx: Context, version: Long, maxSide: Int): Bitmap? {
        val isSmall = maxSide <= SMALL_LIMIT
        val slot = if (isSmall) small else large
        if (slot != null && slot.version == version && slot.maxSide == maxSide) return slot.bmp
        val e = Entry(version, maxSide, decode(file(ctx), maxSide))
        if (isSmall) small = e else large = e
        return e.bmp
    }

    @Synchronized
    fun release() {
        large = null
        small = null
    }

    /** Decodes at the smallest power-of-two sample size that keeps the longest side <= [maxSide]. */
    fun decode(f: File, maxSide: Int): Bitmap? {
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(f.path, opts)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory decoding background photo", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode background photo", e)
            null
        }
    }
}
