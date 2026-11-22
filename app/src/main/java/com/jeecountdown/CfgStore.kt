package com.jeecountdown

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory cache + debounced persistence of [Cfg]. All app components share this one instance.
 * Writes are coalesced (slider drags / typing no longer hit disk per event) and flushed on pause.
 */
object CfgStore {
    private const val PERSIST_DELAY_MS = 300L
    private const val PREFS_NAME = "cfg"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cache: Cfg? = null
    @Volatile private var store: KeyValueStore? = null
    @Volatile private var pending: Cfg? = null
    private val persistRun = Runnable { flush() }

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun store(ctx: Context): KeyValueStore =
        store ?: synchronized(this) {
            store ?: PrefsStore(ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
                .also { store = it }
        }

    fun get(ctx: Context): Cfg {
        cache?.let { return it }
        val c = CfgCodec.decode(store(ctx).all())
        cache = c
        return c
    }

    /** Returns the sanitised config that is now active. */
    fun set(ctx: Context, cfg: Cfg): Cfg {
        val clean = CfgCodec.sanitize(cfg)
        store(ctx)
        cache = clean
        pending = clean
        main.removeCallbacks(persistRun)
        main.postDelayed(persistRun, PERSIST_DELAY_MS)
        changed()
        return clean
    }

    /** Writes any pending change immediately. Call from onPause/onStop. */
    fun flush() {
        main.removeCallbacks(persistRun)
        val c = pending ?: return
        pending = null
        store?.write(CfgCodec.encode(c))
    }

    fun changed() {
        for (l in listeners) l()
    }
}
