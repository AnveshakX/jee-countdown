package com.jeecountdown

import android.app.Activity
import android.app.DatePickerDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val info = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER }
        fun target() = LocalDate.ofEpochDay(prefs.getLong("t", defaultTarget()))
        fun refresh() { info.text = "Counting down to ${target()}" }

        val pick = Button(this).apply {
            text = "Change exam date"
            setOnClickListener {
                val d = target()
                DatePickerDialog(this@MainActivity, { _, y, m, day ->
                    prefs.edit().putLong("t", LocalDate.of(y, m + 1, day).toEpochDay()).apply()
                    refresh()
                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }
        }

        val set = Button(this).apply {
            text = "Set as live wallpaper"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(this@MainActivity, CountdownWallpaperService::class.java)
                        )
                    )
                } catch (e: Exception) {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(info)
            addView(pick)
            addView(set)
        })
        refresh()
    }
}
