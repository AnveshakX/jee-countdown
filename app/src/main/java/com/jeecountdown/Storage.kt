package com.jeecountdown

import android.content.SharedPreferences
import android.util.Log

/** Minimal key-value abstraction so persistence logic can be unit-tested on the JVM without Android. */
interface KeyValueStore {
    fun all(): Map<String, Any?>
    /** Writes every entry; a null value removes the key. */
    fun write(values: Map<String, Any?>)
}

class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun all(): Map<String, Any?> = prefs.all

    override fun write(values: Map<String, Any?>) {
        val e = prefs.edit()
        for ((k, v) in values) {
            when (v) {
                null -> e.remove(k)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is String -> e.putString(k, v)
                else -> Log.w("PrefsStore", "Unsupported value type for key $k: ${v?.javaClass?.name}")
            }
        }
        e.apply()
    }
}
