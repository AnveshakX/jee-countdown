package com.jeecountdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun withAlpha(color: Int, a: Int): Int = (a shl 24) or (color and 0x00FFFFFF)

/** Floating glow dots / twinkling stars. No allocation while drawing. */
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

class Renderer(context: Context) {
    private val ctx: Context = context.applicationContext

    private companion object {
        const val FRAME_MS = 33L            // ~30 fps while a live effect is on
        const val SECOND_MS = 1_000L        // live seconds only
        const val IDLE_TICK_MS = 30_000L    // nothing moves faster than the minute text
        const val DIM_TICK_MS = 30_000L     // desk mode / screensaver
        const val MAX_DT_SEC = 0.1f         // clamp animation step after a pause
        const val BLUR_STEP = 0.3f
        const val BLUR_MIN_FACTOR = 1.5f
        const val MAX_DIM_ALPHA = 217       // photo dim slider 100% = 85% black
        const val SMALL_TARGET_PX = 1000    // previews/widgets use the small photo decode
    }

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
    // Widget-only paints (left aligned), created once
    private val wLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.2f
    }
    private val wDate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val wGoal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.15f
    }

    // Reused geometry (no RectF allocation per frame)
    private val rectCard = RectF()
    private val rectOval = RectF()
    private val rectPill = RectF()
    private val rectDst = RectF()
    private val rectWidget = RectF()

    private val parts = Particles()
    private var fontIdx = -1
    private var lastNs = 0L

    // ---- per-second frame cache: model + colours are recomputed at most once per second ----
    private class Frame(
        val model: CountdownModel, val th: Theme, val lightBg: Boolean,
        val textCol: Int, val accent: Int, val goal: String, val numberText: String
    )

    private var frame: Frame? = null
    private var frameSec = Long.MIN_VALUE
    private var frameCfg: Cfg? = null
    private var frameVer = -1

    private fun frame(cfg: Cfg): Frame {
        val sec = System.currentTimeMillis() / 1000L
        val ver = Exams.version(ctx)
        val f = frame
        if (f != null && sec == frameSec && cfg === frameCfg && ver == frameVer) return f
        val nf = buildFrame(cfg, ZonedDateTime.now())
        frame = nf
        frameSec = sec
        frameCfg = cfg
        frameVer = ver
        return nf
    }

    private fun buildFrame(cfg: Cfg, now: ZonedDateTime): Frame {
        val model = ModelBuilder.build(Exams.load(ctx), cfg, now)
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
        if (cfg.urgency && model.hasEvent && model.urgency != Urgency.NONE) {
            accent = if (model.urgency == Urgency.RED) 0xFFE5735C.toInt() else 0xFFE0B25B.toInt()
            if (lightBg) accent = ColorUtils.blendARGB(accent, Color.BLACK, 0.2f)
        }
        val numberText = if (model.bigNumber < 0L) "--" else model.bigNumber.toString()
        return Frame(model, th, lightBg, textCol, accent, cfg.goal.trim().uppercase(), numberText)
    }

    /** Frees large bitmaps. Call when the surface/view goes away. They are rebuilt lazily if needed again. */
    fun release() {
        bgBmp = null
        blurBmp = null
        frame = null
    }

    /** How long to wait before the next frame. Slow whenever nothing on screen needs to move faster. */
    fun nextDelayMs(dim: Boolean): Long {
        val now = System.currentTimeMillis()
        if (dim) return Cadence.alignedDelay(now, DIM_TICK_MS)
        val cfg = CfgStore.get(ctx)
        return when {
            cfg.effect != 0 && MotionPolicy.animationsAllowed(ctx) -> FRAME_MS
            // Aligned to the wall-clock grid: the seconds readout never skips, and midnight / minute changes
            // appear within ~20 ms instead of up to a full tick late.
            cfg.seconds -> Cadence.alignedDelay(now, SECOND_MS)
            else -> Cadence.alignedDelay(now, IDLE_TICK_MS)
        }
    }

    // ---------- Background layer: cached, reused bitmap, drawn 1:1 each frame ----------
    private var bgBmp: Bitmap? = null
    private var kW = -1
    private var kH = -1
    private var kTheme = -1
    private var kMode = -1
    private var kSolid = 0
    private var kDim = -1
    private var kBlur = -1
    private var kPhoto = -1L
    private var kAccent = 0

    private fun bgLayer(iw: Int, ih: Int, cfg: Cfg, f: Frame): Bitmap {
        val cur = bgBmp
        if (cur != null && kW == iw && kH == ih && kTheme == cfg.theme && kMode == cfg.bgMode &&
            kSolid == cfg.solid && kDim == cfg.dim && kBlur == cfg.blur &&
            kPhoto == cfg.photoVersion && kAccent == f.accent
        ) return cur

        // Reuse the existing bitmap when the size is unchanged (avoids 10-16 MB allocations while sliders move)
        val b = if (cur != null && cur.width == iw && cur.height == ih && cur.isMutable) cur
        else Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        when (cfg.bgMode) {
            1 -> cv.drawColor(cfg.solid)
            2 -> if (!drawPhoto(cv, iw, ih, cfg)) drawGradient(cv, iw, ih, f)
            else -> drawGradient(cv, iw, ih, f)
        }
        bgBmp = b
        kW = iw; kH = ih; kTheme = cfg.theme; kMode = cfg.bgMode; kSolid = cfg.solid
        kDim = cfg.dim; kBlur = cfg.blur; kPhoto = cfg.photoVersion; kAccent = f.accent
        return b
    }

    private fun drawGradient(c: Canvas, w: Int, h: Int, f: Frame) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val s = minOf(wf, hf)
        bg.shader = LinearGradient(0f, 0f, wf * 0.6f, hf, f.th.bg, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, bg)
        val ga = if (f.th.light) 0x30 else 0x55
        glow.shader = RadialGradient(
            wf * 0.88f, hf * 0.10f, s * 0.75f,
            withAlpha(f.accent, ga), withAlpha(f.accent, 0), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, glow)
        glow.shader = RadialGradient(
            wf * 0.08f, hf * 0.92f, s * 0.7f,
            withAlpha(f.accent, ga * 3 / 4), withAlpha(f.accent, 0), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, glow)
    }

    private var blurBmp: Bitmap? = null
    private var blurVersion = -1L
    private var blurAmount = -1
    private var blurSide = -1

    private fun drawPhoto(c: Canvas, w: Int, h: Int, cfg: Cfg): Boolean {
        val side = if (maxOf(w, h) <= SMALL_TARGET_PX) PhotoCache.SMALL_SIDE else PhotoCache.LARGE_SIDE
        val src = PhotoCache.get(ctx, cfg.photoVersion, side) ?: return false
        val bmp = blurred(src, cfg, side)
        val scale = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        rectDst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        c.drawBitmap(bmp, null, rectDst, bmpPaint)
        c.drawColor(Color.argb((cfg.dim * 2.17f).toInt().coerceIn(0, MAX_DIM_ALPHA), 0, 0, 0))
        return true
    }

    // The blur is computed once per (photo, amount) change - never per frame - and cached.
    private fun blurred(src: Bitmap, cfg: Cfg, side: Int): Bitmap {
        val factor = 1f + cfg.blur * BLUR_STEP
        if (factor <= BLUR_MIN_FACTOR) return src
        val cached = blurBmp
        if (cached != null && blurVersion == cfg.photoVersion && blurAmount == cfg.blur && blurSide == side) return cached
        val out = softBlur(src, factor)
        blurBmp = out
        blurVersion = cfg.photoVersion
        blurAmount = cfg.blur
        blurSide = side
        return out
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

    // Pill gradient + matching text colour are cached per (accent, geometry)
    private var pillShader: Shader? = null
    private var pillAccent = 0
    private var pillLeft = Float.NaN
    private var pillRight = Float.NaN
    private var pillTextColor = 0

    private fun pillShaderFor(accent: Int, left: Float, right: Float): Shader {
        val cur = pillShader
        if (cur != null && pillAccent == accent && pillLeft == left && pillRight == right) return cur
        val lightC = ColorUtils.blendARGB(accent, Color.WHITE, 0.35f)
        val darkC = ColorUtils.blendARGB(accent, Color.BLACK, 0.15f)
        val sh = LinearGradient(left, 0f, right, 0f, lightC, darkC, Shader.TileMode.CLAMP)
        val mid = ColorUtils.blendARGB(lightC, darkC, 0.5f)
        pillTextColor = if (ColorUtils.calculateLuminance(mid) > 0.45) 0xFF14211A.toInt() else 0xFFFFFFFF.toInt()
        pillShader = sh
        pillAccent = accent
        pillLeft = left
        pillRight = right
        return sh
    }

    // ---------- Wallpaper / desk mode / screensaver ----------
    // dim = true gives the AOD-style version: pure black, thin, no big bright areas, no animation
    fun draw(c: Canvas, w: Float, h: Float, dim: Boolean) {
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw <= 0 || ih <= 0) return
        val cfg = CfgStore.get(ctx)
        val f = frame(cfg)
        val m = f.model
        val s = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val cw = s * 0.86f
        val ch = s * 0.92f

        val nowNs = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1e9f).coerceIn(0f, MAX_DT_SEC)
        lastNs = nowNs

        // Background + live particles (particles only when motion is allowed)
        if (dim) {
            c.drawColor(Color.BLACK)
        } else {
            c.drawBitmap(bgLayer(iw, ih, cfg, f), 0f, 0f, null)
            if (cfg.effect != 0 && MotionPolicy.animationsAllowed(ctx)) {
                parts.update(dt, cfg.effect)
                parts.draw(c, w, h, cfg.effect, if (cfg.effect == 1) f.accent else f.textCol)
            }
        }

        // Glass card
        if (cfg.card && !dim) {
            card.color = if (f.lightBg) 0x80FFFFFF.toInt() else 0x16FFFFFF
            cardEdge.color = if (f.lightBg) 0x22000000 else 0x3AFFFFFF
            val r = s * 0.07f
            rectCard.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
            c.drawRoundRect(rectCard, r, r, card)
            c.drawRoundRect(rectCard, r, r, cardEdge)
        }

        // Vertical stack layout, centred in the card
        val ringR = cw * 0.28f
        val lh = s * 0.032f
        val ph = s * 0.075f
        val sh = s * 0.026f
        val gh = s * 0.028f
        val showPill = cfg.pill && m.hasEvent
        val showTime = cfg.timeLeft && m.hasEvent
        val showGoal = f.goal.isNotEmpty()
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
        label.color = if (dim) withAlpha(f.accent, 0xC0) else f.accent
        label.textSize = lh
        fit(label, m.label, cw * 0.9f)
        c.drawText(m.label, cx, labelBase, label)

        // Progress ring
        val sw = s * (if (dim) 0.008f else 0.012f)
        if (cfg.ring) {
            track.strokeWidth = sw
            track.color = if (dim) 0x1AFFFFFF else if (f.lightBg) 0x22000000 else 0x26FFFFFF
            arc.strokeWidth = sw
            arc.color = if (dim) withAlpha(f.accent, 0xB0) else f.accent
            c.drawCircle(cx, ringCy, ringR, track)
            if (m.progress > 0.002f) {
                rectOval.set(cx - ringR, ringCy - ringR, cx + ringR, ringCy + ringR)
                c.drawArc(rectOval, -90f, 360f * m.progress, false, arc)
            }
        }

        // Live seconds dot orbiting the ring (seconds-of-minute is zone-independent)
        if (cfg.seconds && !dim && m.hasEvent) {
            val sec = (System.currentTimeMillis() % 60_000L) / 1000f
            val ang = Math.toRadians((sec / 60f * 360f - 90f).toDouble())
            val dx = (cx + ringR * cos(ang)).toFloat()
            val dy = (ringCy + ringR * sin(ang)).toFloat()
            dot.color = withAlpha(f.accent, 0x40)
            c.drawCircle(dx, dy, sw * 2.4f, dot)
            dot.color = f.accent
            c.drawCircle(dx, dy, sw * 0.9f, dot)
        }

        // Big number
        applyFont(cfg)
        num.color = if (dim) 0xFFB5B2A8.toInt() else f.textCol
        num.textSize = numSize(ringR, f.numberText.length)
        c.drawText(f.numberText, cx, ringCy + num.textSize * 0.36f, num)

        if (!m.hasEvent) return

        // Date pill (plain text in dim mode)
        if (showPill) {
            y += s * 0.04f
            val pcy = y + ph / 2f
            y += ph
            val pw = s * 0.40f
            pillText.textSize = s * 0.03f
            val fm = pillText.fontMetrics
            val textY = pcy - (fm.ascent + fm.descent) / 2f
            if (dim) {
                pillText.color = 0xFF9C998F.toInt()
            } else {
                rectPill.set(cx - pw / 2f, pcy - ph / 2f, cx + pw / 2f, pcy + ph / 2f)
                pill.shader = pillShaderFor(f.accent, rectPill.left, rectPill.right)
                c.drawRoundRect(rectPill, ph / 2f, ph / 2f, pill)
                pillText.color = pillTextColor
            }
            c.drawText(m.dateText, cx, textY, pillText)
        }

        // Time left today
        if (showTime) {
            y += s * 0.03f
            small.color = if (dim) 0x70F1EFE4 else withAlpha(f.textCol, 0x99)
            small.textSize = sh
            c.drawText(if (cfg.seconds && !dim) m.timeLeftText else m.timeLeftNoSeconds, cx, y + sh * 0.85f, small)
            y += sh
        }

        // Goal line
        if (showGoal) {
            y += s * 0.03f
            goalP.color = if (dim) withAlpha(f.accent, 0xA0) else f.accent
            goalP.textSize = gh
            fit(goalP, f.goal, cw * 0.85f)
            c.drawText(f.goal, cx, y + gh * 0.85f, goalP)
        }
    }

    // ---------- Home-screen widget ----------
    fun drawWidget(c: Canvas, w: Float, h: Float) {
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw <= 0 || ih <= 0) return
        val cfg = CfgStore.get(ctx)
        val f = frame(cfg)
        val m = f.model

        val rr = h * 0.2f
        rectWidget.set(0f, 0f, w, h)
        c.save()
        c.clipPath(Path().apply { addRoundRect(rectWidget, rr, rr, Path.Direction.CW) })
        c.drawBitmap(bgLayer(iw, ih, cfg, f), 0f, 0f, null)
        c.restore()

        val ringR = h * 0.33f
        val cx = h * 0.62f
        val cy = h / 2f
        val sw = h * 0.05f
        if (cfg.ring) {
            track.strokeWidth = sw
            track.color = if (f.lightBg) 0x22000000 else 0x26FFFFFF
            arc.strokeWidth = sw
            arc.color = f.accent
            c.drawCircle(cx, cy, ringR, track)
            if (m.progress > 0.002f) {
                rectOval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
                c.drawArc(rectOval, -90f, 360f * m.progress, false, arc)
            }
        }
        applyFont(cfg)
        num.color = f.textCol
        num.textSize = numSize(ringR, f.numberText.length)
        c.drawText(f.numberText, cx, cy + num.textSize * 0.36f, num)

        val x = cx + ringR + h * 0.2f
        val maxW = w - x - h * 0.12f
        wLabel.color = f.accent
        wLabel.textSize = h * 0.10f
        wDate.color = f.textCol
        wDate.textSize = h * 0.16f
        wGoal.color = withAlpha(f.textCol, 0xAA)
        wGoal.textSize = h * 0.085f
        val ds = if (m.hasEvent) m.dateText else "TAP TO SET UP"
        fit(wLabel, m.label, maxW)
        fit(wDate, ds, maxW)
        val hasGoal = f.goal.isNotEmpty()
        if (hasGoal) fit(wGoal, f.goal, maxW)
        val gap = h * 0.06f
        val total = wLabel.textSize + gap + wDate.textSize + (if (hasGoal) gap + wGoal.textSize else 0f)
        var y = cy - total / 2f
        c.drawText(m.label, x, y + wLabel.textSize * 0.85f, wLabel)
        y += wLabel.textSize + gap
        c.drawText(ds, x, y + wDate.textSize * 0.85f, wDate)
        y += wDate.textSize + gap
        if (hasGoal) c.drawText(f.goal, x, y + wGoal.textSize * 0.85f, wGoal)
    }
}
