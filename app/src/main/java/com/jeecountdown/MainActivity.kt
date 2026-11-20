package com.jeecountdown

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Exams"; textSize = 22f })
        root.addView(list)
        root.addView(btn("Add exam") { addExam() })
        root.addView(btn("Set as live wallpaper") { setWallpaper() })
        root.addView(btn("Desk mode (always-on style)") {
            startActivity(Intent(this, DeskActivity::class.java))
        })
        root.addView(btn("Screensaver settings") { openDream() })
        root.addView(TextView(this).apply {
            text = "Tap an exam to change its date, long-press to delete. The wallpaper always shows the next upcoming exam."
            textSize = 13f
        })
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        list.removeAllViews()
        Exams.load(this).sortedBy { it.epochDay }.forEach { e ->
            list.addView(Button(this).apply {
                text = "${e.name}  ·  ${LocalDate.ofEpochDay(e.epochDay).format(fmt)}"
                setOnClickListener {
                    pickDate(e.epochDay) { d ->
                        Exams.save(this@MainActivity, Exams.load(this@MainActivity).map {
                            if (it == e) Exam(e.name, d) else it
                        })
                        refresh()
                    }
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete ${e.name}?")
                        .setPositiveButton("Delete") { _, _ ->
                            Exams.save(this@MainActivity, Exams.load(this@MainActivity).filter { it != e })
                            refresh()
                        }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
            })
        }
    }

    private fun pickDate(initial: Long, onPicked: (Long) -> Unit) {
        val d = LocalDate.ofEpochDay(initial)
        DatePickerDialog(this, { _, y, m, day ->
            onPicked(LocalDate.of(y, m + 1, day).toEpochDay())
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun addExam() {
        val input = EditText(this).apply { hint = "Name (e.g. BOARDS)" }
        AlertDialog.Builder(this).setTitle("New exam").setView(input)
            .setPositiveButton("Next") { _, _ ->
                val n = input.text.toString().trim().uppercase().ifEmpty { "EXAM" }
                pickDate(LocalDate.now().plusDays(30).toEpochDay()) { d ->
                    Exams.save(this, Exams.load(this) + Exam(n, d))
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setWallpaper() {
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, CountdownWallpaperService::class.java)
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun openDream() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
    }
}
