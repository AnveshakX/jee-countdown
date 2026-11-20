package com.jeecountdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.PowerManager
import androidx.core.graphics.ColorUtils
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun withAlpha(color: Int, a: Int): Int = (a shl 24) or (color and 0x00FFFFFF)

// Floating glow dots / twinkling stars
class Particles {
    private val n = 60
    private val px = FloatArray(n) { Random.nextFloat() }
    private val py = FloatArray(n) { Random.nextFloat() }
    private val r = FloatArray(n) { 0.004f + Random.nextFloat() * 0.012f }
    private val v = FloatArray(n) { 0.01f + Random.nextFloat() * 0.03f }
    private val ph = FloatArray(n) { Random.nextFloat() * 6.28f }
    private val sw = FloatArray(n) { 0.01f + Random.nextFloat() * 0.03f }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    fun update(dt: Float, mode: Int) {
        t += dt
        if (mode != 1) return
        for (i in 0 until n) {
            py[i] -= v[i] * dt
            if (py[i] < -0.05f) {
                py[i] = 1.05f
                px[i] = Random.nextFloat()
            }
        }
    }

    fun draw(c: Canvas, w: Float, h: Float, mode: Int, color: Int) {
        val s = minOf(w, h)
        if (mode == 1) {
            for (i in 0 until 26) {
                val x = (px[i] + sin(t * 0.5f + ph[i]) * sw[i]) * w
                val y = py[i] * h
                val pulse = 0.5f + 0.5f * sin(t * 0.9f + ph[i])
                val rad = (0.006f + r[i]) * s
                p.color = withAlpha(color, (20 + pulse * 30).toInt())
                c.drawCircle(x, y, rad * 2.4f, p)
                p.color = withAlpha(color, (90 + pulse * 110).toInt())
                c.drawCircle(x, y, rad * 0.6f, p)
            }
        } else {
            for (i in 0 until n) {
                val tw = 0.5f + 0.5f * sin(t * (1.2f + sw[i] * 40f) + ph[i])
                p.color = withAlpha(color, (50 + tw * 170).toInt())
                c.drawCircle(px[i] * w, py[i] * h, (0.0012f + r[i] * 0.25f) * s, p)
            }
        }
    }
}

class Renderer(private val ctx: Context) {
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.3f
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.2f
    }
    private val goalP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.25f
    }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.15f
    }

    private val parts = Particles()
    private var bgBmp: Bitmap? = null
    private var bgKey = ""
    private var photoSrc: Bitmap? = null
    private var photoVer = -1L
    private var fontIdx = -1
    private var lastNs = 0L

    private class St(
        val up: Upcoming?, val days: Long, val big: Long, val label: String, val accent: Int,
        val th: Theme, val lightBg: Boolean, val textCol: Int, val progress: Float,
        val nowT: LocalDateTime, val today: LocalDate
    )

    private fun powerSave(): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    // How long to wait before the next frame
    fun nextDelayMs(dim: Boolean): Long {
        if (dim) return 30_000L
        val cfg = CfgStore.get(ctx)
        return when {
            cfg.effect != 0 && !powerSave() -> 33L
            cfg.seconds -> 1_000L
            else -> 30_000L
        }
    }

    private fun state(cfg: Cfg): St {
        val today = LocalDate.now()
        val nowT = LocalDateTime.now()
        val up = Exams.next(ctx, today)
        val days = if (up == null) 0L
        else ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(up.exam.epochDay))
        val th = Themes.all[cfg.theme.coerceIn(0, Themes.all.size - 1)]
        val lightBg = when (cfg.bgMode) {
            0 -> th.light
            1 -> ColorUtils.calculateLuminance(cfg.solid) > 0.55
            else -> false
        }
        val textCol = when {
            lightBg -> 0xFF2B2A26.toInt()
            th.light -> 0xFFF1EFE4.toInt()
            else -> th.text
        }
        var accent = if (cfg.accent != 0) cfg.accent else th.accent
        if (lightBg && !th.light && cfg.accent == 0) accent = ColorUtils.blendARGB(accent, Color.BLACK, 0.45f)
        if (cfg.urgency && up != null && days <= 30) {
            accent = if (days <= 10) 0xFFE5735C.toInt() else 0xFFE0B25B.toInt()
            if (lightBg) accent = ColorUtils.blendARGB(accent, Color.BLACK, 0.2f)
        }
        var progress = 1f
        if (up != null) {
            val total = (up.exam.epochDay - up.startDay).coerceAtLeast(1L)
            val done = (today.toEpochDay() - up.startDay).coerceIn(0L, total)
            progress = done.toFloat() / total.toFloat()
        }
        val hoursLeft = if (up == null) 0L
        else Duration.between(nowT, LocalDate.ofEpochDay(up.exam.epochDay).atStartOfDay()).toHours().coerceAtLeast(0L)
        val big = when {
            up == null -> -1L
            cfg.unit == 1 -> days / 7
            cfg.unit == 2 -> hoursLeft
            else -> days
        }
        val en = up?.exam?.name ?: ""
        val unitName = when (cfg.unit) { 1 -> "WEEKS"; 2 -> "HOURS"; else -> "DAYS" }
        val lt = when {
            cfg.label.isNotBlank() -> cfg.label.trim().uppercase()
            up == null -> "ADD A COUNTDOWN"
            days == 0L && cfg.unit == 0 -> "$en IS TODAY"
            else -> "$unitName TO $en"
        }
        return St(up, days, big, lt, accent, th, lightBg, textCol, progress, nowT, today)
    }

    // ---------- Background layer (cached, drawn 1:1 each frame) ----------
    private fun bgLayer(iw: Int, ih: Int, cfg: Cfg, st: St): Bitmap {
        val key = "$iw,$ih,${cfg.theme},${cfg.bgMode},${cfg.solid},${cfg.dim},${cfg.blur},${cfg.photoVersion},${st.accent}"
        val cur = bgBmp
        if (cur != null && key == bgKey) return cur
        val b = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        when (cfg.bgMode) {
            1 -> cv.drawColor(cfg.solid)
            2 -> if (!drawPhoto(cv, iw, ih, cfg)) drawGradient(cv, iw, ih, st)
            else -> drawGradient(cv, iw, ih, st)
        }
        bgBmp = b
        bgKey = key
        return b
    }

    private fun drawGradient(c: Canvas, w: Int, h: Int, st: St) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val s = minOf(wf, hf)
        bg.shader = LinearGradient(0f, 0f, wf * 0.6f, hf, st.th.bg, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, bg)
        val ga = if (st.th.light) 0x30 else 0x55
        glow.shader = RadialGradient(
            wf * 0.88f, hf * 0.10f, s * 0.75f,
            withAlpha(st.accent, ga), withAlpha(st.accent, 0), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, glow)
        glow.shader = RadialGradient(
            wf * 0.08f, hf * 0.92f, s * 0.7f,
            withAlpha(st.accent, ga * 3 / 4), withAlpha(st.accent, 0), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, glow)
    }

    private fun drawPhoto(c: Canvas, w: Int, h: Int, cfg: Cfg): Boolean {
        if (photoVer != cfg.photoVersion || photoSrc == null) {
            photoSrc = BitmapFactory.decodeFile(File(ctx.filesDir, "bg.jpg").path)
            photoVer = cfg.photoVersion
        }
        var bmp = photoSrc ?: return false
        val f = 1f + cfg.blur * 0.3f
        if (f > 1.5f) bmp = softBlur(bmp, f)
        val scale = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        c.drawBitmap(bmp, null, dst, bmpPaint)
        c.drawColor(Color.argb((cfg.dim * 2.17f).toInt().coerceIn(0, 217), 0, 0, 0))
        return true
    }

    // Cheap blur: shrink a lot, then let bilinear scaling smooth it back up
    private fun softBlur(src: Bitmap, factor: Float): Bitmap {
        val tw = maxOf(8, (src.width / factor).toInt())
        val tH = maxOf(8, (src.height / factor).toInt())
        var b = src
        while (b.width > tw * 2) {
            b = Bitmap.createScaledBitmap(b, maxOf(tw, b.width / 2), maxOf(tH, b.height / 2), true)
        }
        return Bitmap.createScaledBitmap(b, tw, tH, true)
    }

    private fun applyFont(cfg: Cfg) {
        if (fontIdx != cfg.font) {
            fontIdx = cfg.font
            num.typeface = Typeface.create(Fonts.families[cfg.font.coerceIn(0, Fonts.families.size - 1)], Typeface.NORMAL)
        }
    }

    private fun fit(p: Paint, text: String, maxW: Float) {
        val w = p.measureText(text)
        if (w > maxW) p.textSize *= maxW / w
    }

    private fun numSize(r: Float, len: Int): Float = r * when {
        len <= 2 -> 1.15f
        len == 3 -> 0.85f
        len == 4 -> 0.68f
        else -> 0.55f
    }

    // ---------- Wallpaper / desk mode / screensaver ----------
    // dim = true gives the AOD-style version: pure black, thin, no big bright areas, no animation
    fun draw(c: Canvas, w: Float, h: Float, dim: Boolean) {
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw <= 0 || ih <= 0) return
        val cfg = CfgStore.get(ctx)
        val st = state(cfg)
        val up = st.up
        val s = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val cw = s * 0.86f
        val ch = s * 0.92f

        val nowNs = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = nowNs

        // Background + live particles
        if (dim) {
            c.drawColor(Color.BLACK)
        } else {
            c.drawBitmap(bgLayer(iw, ih, cfg, st), 0f, 0f, null)
            if (cfg.effect != 0 && !powerSave()) {
                parts.update(dt, cfg.effect)
                parts.draw(c, w, h, cfg.effect, if (cfg.effect == 1) st.accent else st.textCol)
            }
        }

        // Glass card
        if (cfg.card && !dim) {
            card.color = if (st.lightBg) 0x80FFFFFF.toInt() else 0x16FFFFFF
            cardEdge.color = if (st.lightBg) 0x22000000 else 0x3AFFFFFF
            val r = s * 0.07f
            val rect = RectF(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
            c.drawRoundRect(rect, r, r, card)
            c.drawRoundRect(rect, r, r, cardEdge)
        }

        // Vertical stack layout, centred in the card
        val ringR = cw * 0.28f
        val lh = s * 0.032f
        val ph = s * 0.075f
        val sh = s * 0.026f
        val gh = s * 0.028f
        val showPill = cfg.pill && up != null
        val showTime = cfg.timeLeft && up != null
        val goal = cfg.goal.trim().uppercase()
        val showGoal = goal.isNotEmpty()
        var total = lh + s * 0.045f + ringR * 2f
        if (showPill) total += s * 0.04f + ph
        if (showTime) total += s * 0.03f + sh
        if (showGoal) total += s * 0.03f + gh
        var y = cy - total / 2f
        val labelBase = y + lh * 0.85f
        y += lh + s * 0.045f
        val ringCy = y + ringR
        y += ringR * 2f

        // Label
        label.color = if (dim) withAlpha(st.accent, 0xC0) else st.accent
        label.textSize = lh
        fit(label, st.label, cw * 0.9f)
        c.drawText(st.label, cx, labelBase, label)

        // Progress ring
        val sw = s * (if (dim) 0.008f else 0.012f)
        if (cfg.ring) {
            track.strokeWidth = sw
            track.color = if (dim) 0x1AFFFFFF else if (st.lightBg) 0x22000000 else 0x26FFFFFF
            arc.strokeWidth = sw
            arc.color = if (dim) withAlpha(st.accent, 0xB0) else st.accent
            c.drawCircle(cx, ringCy, ringR, track)
            if (st.progress > 0.002f) {
                val oval = RectF(cx - ringR, ringCy - ringR, cx + ringR, ringCy + ringR)
                c.drawArc(oval, -90f, 360f * st.progress, false, arc)
            }
        }

        // Live seconds dot orbiting the ring
        if (cfg.seconds && !dim && up != null) {
            val sec = st.nowT.second + st.nowT.nano / 1_000_000_000f
            val ang = Math.toRadians((sec / 60f * 360f - 90f).toDouble())
            val dx = (cx + ringR * cos(ang)).toFloat()
            val dy = (ringCy + ringR * sin(ang)).toFloat()
            dot.color = withAlpha(st.accent, 0x40)
            c.drawCircle(dx, dy, sw * 2.4f, dot)
            dot.color = st.accent
            c.drawCircle(dx, dy, sw * 0.9f, dot)
        }

        // Big number
        applyFont(cfg)
        val text = if (st.big < 0) "--" else st.big.toString()
        num.color = if (dim) 0xFFB5B2A8.toInt() else st.textCol
        num.textSize = numSize(ringR, text.length)
        c.drawText(text, cx, ringCy + num.textSize * 0.36f, num)

        if (up == null) return

        // Date pill (plain text in dim mode)
        if (showPill) {
            y += s * 0.04f
            val pcy = y + ph / 2f
            y += ph
            val ds = LocalDate.ofEpochDay(up.exam.epochDay)
                .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)).uppercase()
            val pw = s * 0.40f
            pillText.textSize = s * 0.03f
            val fm = pillText.fontMetrics
            val textY = pcy - (fm.ascent + fm.descent) / 2f
            if (dim) {
                pillText.color = 0xFF9C998F.toInt()
            } else {
                val lightC = ColorUtils.blendARGB(st.accent, Color.WHITE, 0.35f)
                val darkC = ColorUtils.blendARGB(st.accent, Color.BLACK, 0.15f)
                val pr = RectF(cx - pw / 2f, pcy - ph / 2f, cx + pw / 2f, pcy + ph / 2f)
                pill.shader = LinearGradient(pr.left, 0f, pr.right, 0f, lightC, darkC, Shader.TileMode.CLAMP)
                c.drawRoundRect(pr, ph / 2f, ph / 2f, pill)
                val mid = ColorUtils.blendARGB(lightC, darkC, 0.5f)
                pillText.color = if (ColorUtils.calculateLuminance(mid) > 0.45) 0xFF14211A.toInt() else 0xFFFFFFFF.toInt()
            }
            c.drawText(ds, cx, textY, pillText)
        }

        // Time left today
        if (showTime) {
            y += s * 0.03f
            val secs = Duration.between(st.nowT, st.today.plusDays(1).atStartOfDay()).seconds.coerceAtLeast(0L)
            val hh = secs / 3600
            val mm = (secs % 3600) / 60
            val ss = secs % 60
            val tl = if (cfg.seconds && !dim) {
                String.format(Locale.ENGLISH, "%dH %02dM %02dS LEFT TODAY", hh, mm, ss)
            } else {
                String.format(Locale.ENGLISH, "%dH %02dM LEFT TODAY", hh, mm)
            }
            small.color = if (dim) 0x70F1EFE4 else withAlpha(st.textCol, 0x99)
            small.textSize = sh
            c.drawText(tl, cx, y + sh * 0.85f, small)
            y += sh
        }

        // Goal line
        if (showGoal) {
            y += s * 0.03f
            goalP.color = if (dim) withAlpha(st.accent, 0xA0) else st.accent
            goalP.textSize = gh
            fit(goalP, goal, cw * 0.85f)
            c.drawText(goal, cx, y + gh * 0.85f, goalP)
        }
    }

    // ---------- Home-screen widget ----------
    fun drawWidget(c: Canvas, w: Float, h: Float) {
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw <= 0 || ih <= 0) return
        val cfg = CfgStore.get(ctx)
        val st = state(cfg)
        val up = st.up

        val rr = h * 0.2f
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), rr, rr, Path.Direction.CW) })
        c.drawBitmap(bgLayer(iw, ih, cfg, st), 0f, 0f, null)
        c.restore()

        val ringR = h * 0.33f
        val cx = h * 0.62f
        val cy = h / 2f
        val sw = h * 0.05f
        if (cfg.ring) {
            track.strokeWidth = sw
            track.color = if (st.lightBg) 0x22000000 else 0x26FFFFFF
            arc.strokeWidth = sw
            arc.color = st.accent
            c.drawCircle(cx, cy, ringR, track)
            if (st.progress > 0.002f) {
                c.drawArc(RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR), -90f, 360f * st.progress, false, arc)
            }
        }
        applyFont(cfg)
        val text = if (st.big < 0) "--" else st.big.toString()
        num.color = st.textCol
        num.textSize = numSize(ringR, text.length)
        c.drawText(text, cx, cy + num.textSize * 0.36f, num)

        val x = cx + ringR + h * 0.2f
        val maxW = w - x - h * 0.12f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.2f
            color = st.accent
            textSize = h * 0.10f
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            color = st.textCol
            textSize = h * 0.16f
        }
        val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.15f
            color = withAlpha(st.textCol, 0xAA)
            textSize = h * 0.085f
        }
        val ds = if (up == null) "TAP TO SET UP"
        else LocalDate.ofEpochDay(up.exam.epochDay)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)).uppercase()
        val goal = cfg.goal.trim().uppercase()
        fit(labelPaint, st.label, maxW)
        fit(datePaint, ds, maxW)
        if (goal.isNotEmpty()) fit(goalPaint, goal, maxW)
        val gap = h * 0.06f
        val total = labelPaint.textSize + gap + datePaint.textSize +
            (if (goal.isNotEmpty()) gap + goalPaint.textSize else 0f)
        var y = cy - total / 2f
        c.drawText(st.label, x, y + labelPaint.textSize * 0.85f, labelPaint)
        y += labelPaint.textSize + gap
        c.drawText(ds, x, y + datePaint.textSize * 0.85f, datePaint)
        y += datePaint.textSize + gap
        if (goal.isNotEmpty()) c.drawText(goal, x, y + goalPaint.textSize * 0.85f, goalPaint)
    }
}
