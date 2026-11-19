package com.jeecountdown

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// JEE Main 2027 Session 1 starts 22 Jan 2027 (change in the app if NTA shifts it)
fun defaultTarget(): Long = LocalDate.of(2027, 1, 22).toEpochDay()

class CountdownWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CountdownEngine()

    inner class CountdownEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x16FFFFFF }
        private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x3AFFFFFF
        }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF1EFE4.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA9C98A.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.3f
        }
        private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E2E26.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.15f
        }
        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 30_000)
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(tick)
            if (v) tick.run()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(tick)
            super.onDestroy()
        }

        private fun draw() {
            val holder = surfaceHolder
            val c = holder.lockCanvas() ?: return
            try { render(c) } finally { holder.unlockCanvasAndPost(c) }
        }

        private fun render(c: Canvas) {
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            val s = minOf(w, h)

            val target = LocalDate.ofEpochDay(
                getSharedPreferences("p", MODE_PRIVATE).getLong("t", defaultTarget())
            )
            val days = ChronoUnit.DAYS.between(LocalDate.now(), target).coerceAtLeast(0)

            // Deep forest background
            bg.shader = LinearGradient(
                0f, 0f, w * 0.6f, h,
                intArrayOf(0xFF14211A.toInt(), 0xFF223A2E.toInt(), 0xFF35503F.toInt()),
                null, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, bg)

            // Soft sage glows for depth (like the blurred leaves)
            glow.shader = RadialGradient(
                w * 0.88f, h * 0.10f, s * 0.75f,
                0x55A9C98A, 0x00A9C98A, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, glow)
            glow.shader = RadialGradient(
                w * 0.08f, h * 0.92f, s * 0.7f,
                0x40A9C98A, 0x00A9C98A, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, glow)

            // Glass card
            val cw = s * 0.86f
            val ch = s * 0.92f
            val cx = w / 2f
            val cy = h / 2f
            val r = s * 0.07f
            val rect = RectF(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
            c.drawRoundRect(rect, r, r, card)
            c.drawRoundRect(rect, r, r, cardEdge)

            // Text
            num.textSize = cw * 0.42f
            label.textSize = s * 0.032f
            pillText.textSize = s * 0.03f

            val numBase = cy + num.textSize * 0.30f
            val numTop = numBase - num.textSize * 0.72f

            c.drawText(
                if (days == 0L) "EXAM DAY" else "DAYS TO JEE MAIN",
                cx, numTop - ch * 0.07f, label
            )
            c.drawText(days.toString(), cx, numBase, num)

            // Sage pill with the exam date
            val pw = s * 0.40f
            val ph = s * 0.075f
            val pcy = numBase + ch * 0.12f
            val pr = RectF(cx - pw / 2f, pcy - ph / 2f, cx + pw / 2f, pcy + ph / 2f)
            pill.shader = LinearGradient(
                pr.left, 0f, pr.right, 0f,
                0xFFD2E3B0.toInt(), 0xFF8FB07A.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRoundRect(pr, ph / 2f, ph / 2f, pill)
            val fm = pillText.fontMetrics
            c.drawText(
                target.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)).uppercase(),
                cx, pcy - (fm.ascent + fm.descent) / 2f, pillText
            )
        }
    }
}
