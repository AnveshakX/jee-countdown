package com.jeecountdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class Renderer(private val ctx: Context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x16FFFFFF }
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x3AFFFFFF
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
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
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.15f
    }

    private class Palette(val main: Int, val light: Int, val dark: Int)

    // Sage > 30 days, amber 11-30, warm red <= 10
    private fun palette(days: Long) = when {
        days > 30 -> Palette(0xFFA9C98A.toInt(), 0xFFD2E3B0.toInt(), 0xFF8FB07A.toInt())
        days > 10 -> Palette(0xFFE0B25B.toInt(), 0xFFF0D898.toInt(), 0xFFC4903A.toInt())
        else -> Palette(0xFFE5735C.toInt(), 0xFFF2A592.toInt(), 0xFFC4513D.toInt())
    }

    private fun withAlpha(color: Int, a: Int) = (a shl 24) or (color and 0x00FFFFFF)

    // dim = true gives the AOD-style version: pure black, thin, no big bright areas
    fun draw(c: Canvas, w: Float, h: Float, dim: Boolean) {
        val s = minOf(w, h)
        val today = LocalDate.now()
        val up = Exams.next(ctx, today)
        val days = if (up == null) 0L
        else ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(up.exam.epochDay))
        val pal = palette(if (up == null) 100 else days)

        var progress = 1f
        if (up != null) {
            val total = (up.exam.epochDay - up.startDay).coerceAtLeast(1L)
            val done = (today.toEpochDay() - up.startDay).coerceIn(0L, total)
            progress = done.toFloat() / total.toFloat()
        }

        val cx = w / 2f
        val cy = h / 2f
        val cw = s * 0.86f
        val ch = s * 0.92f

        // Background
        if (dim) {
            c.drawColor(Color.BLACK)
        } else {
            bg.shader = LinearGradient(
                0f, 0f, w * 0.6f, h,
                intArrayOf(0xFF14211A.toInt(), 0xFF223A2E.toInt(), 0xFF35503F.toInt()),
                null, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, bg)
            glow.shader = RadialGradient(
                w * 0.88f, h * 0.10f, s * 0.75f,
                withAlpha(pal.main, 0x55), withAlpha(pal.main, 0), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, glow)
            glow.shader = RadialGradient(
                w * 0.08f, h * 0.92f, s * 0.7f,
                withAlpha(pal.main, 0x40), withAlpha(pal.main, 0), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, glow)

            val r = s * 0.07f
            val rect = RectF(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
            c.drawRoundRect(rect, r, r, card)
            c.drawRoundRect(rect, r, r, cardEdge)
        }

        // Progress ring
        val ringR = cw * 0.34f
        val ringCy = cy - ch * 0.06f
        val sw = s * (if (dim) 0.008f else 0.012f)
        track.strokeWidth = sw
        track.color = if (dim) 0x1AFFFFFF else 0x26FFFFFF
        arc.strokeWidth = sw
        arc.color = if (dim) withAlpha(pal.main, 0xB0) else pal.main
        c.drawCircle(cx, ringCy, ringR, track)
        if (progress > 0.002f) {
            val oval = RectF(cx - ringR, ringCy - ringR, cx + ringR, ringCy + ringR)
            c.drawArc(oval, -90f, 360f * progress, false, arc)
        }

        // Days number
        val text = days.toString()
        num.color = if (dim) 0xFFB5B2A8.toInt() else 0xFFF1EFE4.toInt()
        num.textSize = cw * (if (text.length >= 3) 0.30f else 0.42f)
        c.drawText(text, cx, ringCy + num.textSize * 0.36f, num)

        // Label above the ring
        val lt = when {
            up == null -> "NO UPCOMING EXAM"
            days == 0L -> "EXAM DAY"
            else -> "DAYS TO ${up.exam.name}"
        }
        label.color = if (dim) withAlpha(pal.main, 0xC0) else pal.main
        label.textSize = s * 0.032f
        val lw = label.measureText(lt)
        if (lw > cw * 0.9f) label.textSize *= cw * 0.9f / lw
        c.drawText(lt, cx, ringCy - ringR - ch * 0.06f, label)

        if (up == null) return

        // Date pill (outlined text only in dim mode)
        val ds = LocalDate.ofEpochDay(up.exam.epochDay)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)).uppercase()
        val pw = s * 0.40f
        val ph = s * 0.075f
        val pcy = ringCy + ringR + ch * 0.10f
        pillText.textSize = s * 0.03f
        val fm = pillText.fontMetrics
        val textY = pcy - (fm.ascent + fm.descent) / 2f
        if (dim) {
            pillText.color = 0xFF9C998F.toInt()
        } else {
            val pr = RectF(cx - pw / 2f, pcy - ph / 2f, cx + pw / 2f, pcy + ph / 2f)
            pill.shader = LinearGradient(
                pr.left, 0f, pr.right, 0f, pal.light, pal.dark, Shader.TileMode.CLAMP
            )
            c.drawRoundRect(pr, ph / 2f, ph / 2f, pill)
            pillText.color = 0xFF1E2E26.toInt()
        }
        c.drawText(ds, cx, textY, pillText)

        // Time left today
        val mins = Duration.between(LocalDateTime.now(), today.plusDays(1).atStartOfDay()).toMinutes()
        small.color = if (dim) 0x70F1EFE4 else 0x99F1EFE4.toInt()
        small.textSize = s * 0.026f
        c.drawText("${mins / 60}H ${mins % 60}M LEFT TODAY", cx, pcy + ph / 2f + ch * 0.065f, small)
    }
}
