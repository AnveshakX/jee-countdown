package com.jeecountdown

import android.app.DatePickerDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private class Preset(val name: String, val date: LocalDate?)

    // Dates verified from NTA / IIT Delhi announcements (Nov 2026); the rest ask for a date.
    private val presets = listOf(
        Preset("JEE MAIN", LocalDate.of(2027, 1, 20)),
        Preset("JEE MAIN S2", LocalDate.of(2027, 4, 2)),
        Preset("JEE ADVANCED", LocalDate.of(2027, 5, 23)),
        Preset("NEET UG", null),
        Preset("BOARD EXAMS", null),
        Preset("CUET UG", null),
        Preset("GATE", null),
        Preset("CAT", null),
        Preset("UPSC PRELIMS", null)
    )

    private val solids = listOf(
        0xFF14211A, 0xFF0B1020, 0xFF2A0F1F, 0xFF06202B, 0xFF000000,
        0xFF1C1C1E, 0xFF3B2A1A, 0xFF1F3B5A, 0xFFF4EFE6, 0xFFFFFFFF
    ).map { it.toInt() }

    private val accents = listOf(
        0xFFA9C98A, 0xFFFFB067, 0xFF8FA8FF, 0xFFFF9EC4, 0xFF6FE0D2,
        0xFFE0B25B, 0xFFC9A3FF, 0xFFFF7A7A, 0xFFFFFFFF
    ).map { it.toInt() }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    private lateinit var preview: CountdownView
    private lateinit var panel: LinearLayout
    private lateinit var cfg: Cfg

    private val handler = Handler(Looper.getMainLooper())
    private val widgetRun = Runnable { CountdownWidget.updateAll(applicationContext) }
    private val cfgListener: () -> Unit = {
        handler.removeCallbacks(widgetRun)
        handler.postDelayed(widgetRun, 400)
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) savePhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = CfgStore.get(this)
        CfgStore.listeners.add(cfgListener)

        val dm = resources.displayMetrics
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val aspect = dm.widthPixels.toFloat() / dm.heightPixels
        val maxW = dm.widthPixels * (if (land) 0.44f else 0.6f)
        val maxH = dm.heightPixels * (if (land) 0.8f else 0.4f)
        val pw = minOf(maxW, maxH * aspect)
        val ph = pw / aspect

        preview = CountdownView(this, false).apply {
            layoutParams = LinearLayout.LayoutParams(pw.toInt(), ph.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                }
            }
        }
        val cta = MaterialButton(this).apply {
            text = "Set as live wallpaper"
            isAllCaps = false
            setOnClickListener { setWallpaper() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (land) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(preview)
            addView(cta)
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        val root = LinearLayout(this).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        if (land) {
            root.addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))
            root.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        } else {
            root.addView(left, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        setContentView(root)
        build()

        // First run: ask what they are counting down to
        if (!cfg.onboarded && Exams.load(this).isEmpty()) {
            upd { it.copy(onboarded = true) }
            addCountdown(true)
        }
    }

    override fun onDestroy() {
        CfgStore.listeners.remove(cfgListener)
        handler.removeCallbacks(widgetRun)
        super.onDestroy()
    }

    // ---------- state ----------
    private fun upd(rebuild: Boolean = false, f: (Cfg) -> Cfg) {
        cfg = f(cfg)
        CfgStore.set(this, cfg)
        preview.invalidate()
        if (rebuild) panel.post { build() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- settings panel ----------
    private fun build() {
        panel.removeAllViews()

        panel.addView(title("Countdowns"))
        Exams.load(this).sortedBy { it.epochDay }.forEach { e ->
            panel.addView(outlined("${e.name}  ·  ${LocalDate.ofEpochDay(e.epochDay).format(fmt)}") { editDate(e) }.apply {
                setOnLongClickListener { confirmDelete(e); true }
            })
        }
        panel.addView(outlined("+ Add countdown") { addCountdown(false) })
        panel.addView(note("Tap to change a date, long-press to delete. The wallpaper shows the next upcoming one. Exam dates can change, so check the official website."))

        panel.addView(title("Theme"))
        panel.addView(row {
            Themes.all.forEachIndexed { i, t ->
                addView(swatch(t.bg, cfg.theme == i) { upd(true) { it.copy(theme = i) } })
            }
        })
        panel.addView(note(Themes.all[cfg.theme.coerceIn(0, Themes.all.size - 1)].name))

        panel.addView(title("Background"))
        panel.addView(segmented(listOf("Theme", "Colour", "Photo"), cfg.bgMode) { i ->
            upd(true) { it.copy(bgMode = i) }
        })
        if (cfg.bgMode == 1) {
            panel.addView(row {
                solids.forEach { col ->
                    addView(swatch(intArrayOf(col), cfg.solid == col) { upd(true) { it.copy(solid = col) } })
                }
            })
        }
        if (cfg.bgMode == 2) {
            panel.addView(outlined(if (cfg.photoVersion == 0L) "Choose photo" else "Change photo") {
                pickPhoto.launch("image/*")
            })
            panel.addView(sliderRow("Dim", cfg.dim) { v -> upd { it.copy(dim = v) } })
            panel.addView(sliderRow("Blur", cfg.blur) { v -> upd { it.copy(blur = v) } })
        }

        panel.addView(title("Accent colour"))
        panel.addView(segmented(listOf("Auto", "Custom"), if (cfg.accent == 0) 0 else 1) { i ->
            upd(true) { it.copy(accent = if (i == 0) 0 else accents[0]) }
        })
        if (cfg.accent != 0) {
            panel.addView(row {
                accents.forEach { col ->
                    addView(swatch(intArrayOf(col), cfg.accent == col) { upd(true) { it.copy(accent = col) } })
                }
            })
        }

        panel.addView(title("Number font"))
        panel.addView(segmented(Fonts.names, cfg.font, true) { i -> upd { it.copy(font = i) } })

        panel.addView(title("Count in"))
        panel.addView(segmented(listOf("Days", "Weeks", "Hours"), cfg.unit) { i -> upd { it.copy(unit = i) } })

        panel.addView(title("Show on wallpaper"))
        panel.addView(switchRow("Progress ring", cfg.ring) { b -> upd { it.copy(ring = b) } })
        panel.addView(switchRow("Glass card", cfg.card) { b -> upd { it.copy(card = b) } })
        panel.addView(switchRow("Date pill", cfg.pill) { b -> upd { it.copy(pill = b) } })
        panel.addView(switchRow("Time left today", cfg.timeLeft) { b -> upd { it.copy(timeLeft = b) } })
        panel.addView(switchRow("Live seconds", cfg.seconds) { b -> upd { it.copy(seconds = b) } })
        panel.addView(switchRow("Urgency colours (amber, then red, near the date)", cfg.urgency) { b ->
            upd { it.copy(urgency = b) }
        })

        panel.addView(title("Text"))
        panel.addView(textField("Custom label (optional)", cfg.label) { s -> upd { it.copy(label = s.take(30)) } })
        panel.addView(textField("Goal line, e.g. IIT BOMBAY CSE", cfg.goal) { s -> upd { it.copy(goal = s.take(30)) } })

        panel.addView(title("Live effect"))
        panel.addView(segmented(listOf("Off", "Glow dots", "Stars"), cfg.effect) { i -> upd { it.copy(effect = i) } })
        panel.addView(note("Animations use a little more battery and pause automatically in battery saver."))

        panel.addView(title("More ways to show it"))
        panel.addView(note("Add the Countdown widget from your home screen's widget picker."))
        panel.addView(outlined("Desk mode (always-on style)") {
            startActivity(Intent(this, DeskActivity::class.java))
        })
        panel.addView(outlined("Screensaver settings") { openDream() })
    }

    // ---------- UI helpers ----------
    private fun title(t: String) = TextView(this).apply {
        text = t.uppercase()
        textSize = 12f
        letterSpacing = 0.12f
        setTextColor(0xFFA9C98A.toInt())
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(0xFF9DB0A3.toInt())
        setPadding(0, dp(6), 0, 0)
    }

    private fun outlined(t: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        }

    private fun row(block: LinearLayout.() -> Unit): View {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        ll.block()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ll)
        }
    }

    private fun swatch(colors: IntArray, selected: Boolean, onClick: () -> Unit): View {
        val d = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (colors.size == 1) intArrayOf(colors[0], colors[0]) else colors
        )
        d.shape = GradientDrawable.OVAL
        d.setStroke(dp(if (selected) 3 else 1), if (selected) 0xFFF1EFE4.toInt() else 0x55FFFFFF)
        return View(this).apply {
            background = d
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(10), 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun segmented(opts: List<String>, selected: Int, scroll: Boolean = false, onPick: (Int) -> Unit): View {
        val g = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = opts.map { View.generateViewId() }
        opts.forEachIndexed { i, o ->
            g.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = ids[i]
                text = o
                isAllCaps = false
            })
        }
        g.check(ids[selected.coerceIn(0, opts.size - 1)])
        g.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) onPick(ids.indexOf(checkedId))
        }
        return if (scroll) HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(g)
        } else g
    }

    private fun switchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) = MaterialSwitch(this).apply {
        text = label
        isChecked = on
        textSize = 15f
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, b -> onChange(b) }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun sliderRow(label: String, v0: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFC9D3C4.toInt())
        })
        box.addView(Slider(this).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = v0.toFloat()
            addOnChangeListener { _, v, fromUser -> if (fromUser) onChange(v.toInt()) }
        })
        return box
    }

    private fun textField(h: String, v0: String, onChange: (String) -> Unit): View {
        val til = TextInputLayout(this).apply {
            hint = h
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        val et = TextInputEditText(til.context).apply {
            setText(v0)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            })
        }
        til.addView(et)
        return til
    }

    // ---------- countdowns ----------
    private fun pickDate(initial: Long, onPicked: (Long) -> Unit) {
        val d = LocalDate.ofEpochDay(initial)
        DatePickerDialog(this, { _, y, m, day ->
            onPicked(LocalDate.of(y, m + 1, day).toEpochDay())
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun editDate(e: Exam) {
        pickDate(e.epochDay) { d ->
            Exams.save(this, Exams.load(this).map { if (it == e) Exam(e.name, d) else it })
            build()
        }
    }

    private fun confirmDelete(e: Exam) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${e.name}?")
            .setPositiveButton("Delete") { _, _ ->
                Exams.save(this, Exams.load(this).filter { it != e })
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun finishAdd(name: String, epochDay: Long) {
        Exams.add(this, Exam(name, epochDay))
        build()
        preview.invalidate()
    }

    private fun addCountdown(first: Boolean) {
        val labels = presets.map { p ->
            if (p.date != null) "${p.name}  ·  ${p.date.format(fmt)}" else "${p.name}  ·  choose date"
        } + "Something else (birthday, trip, ...)"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (first) "What are you counting down to?" else "Add a countdown")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < presets.size) {
                    val p = presets[i]
                    if (p.date != null) {
                        finishAdd(p.name, p.date.toEpochDay())
                    } else {
                        pickDate(LocalDate.now().plusDays(60).toEpochDay()) { d -> finishAdd(p.name, d) }
                    }
                } else {
                    askCustomName()
                }
            }
            .setNegativeButton(if (first) "Skip" else "Cancel", null)
            .show()
    }

    private fun askCustomName() {
        val til = TextInputLayout(this).apply { hint = "Name (e.g. BIRTHDAY)" }
        val et = TextInputEditText(til.context).apply { setSingleLine() }
        til.addView(et)
        val box = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(til)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Name it")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val n = et.text.toString().trim().uppercase().ifEmpty { "MY DAY" }.take(24)
                pickDate(LocalDate.now().plusDays(30).toEpochDay()) { d -> finishAdd(n, d) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- photo ----------
    private fun savePhoto(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 2200) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return
            val rot = when (ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rot != 0f) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            }
            FileOutputStream(File(filesDir, "bg.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            upd(true) { it.copy(bgMode = 2, photoVersion = System.currentTimeMillis()) }
        } catch (e: Exception) {
            // ignore unreadable images
        }
    }

    // ---------- system hooks ----------
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
