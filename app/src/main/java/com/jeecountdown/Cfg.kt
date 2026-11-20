package com.jeecountdown

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

private fun c(v: Long) = v.toInt()

class Theme(val name: String, val bg: IntArray, val text: Int, val accent: Int, val light: Boolean)

object Themes {
    val all = listOf(
        Theme("Forest", intArrayOf(c(0xFF14211A), c(0xFF223A2E), c(0xFF35503F)), c(0xFFF1EFE4), c(0xFFA9C98A), false),
        Theme("Midnight", intArrayOf(c(0xFF0B1020), c(0xFF141B3A), c(0xFF1F2A5A)), c(0xFFE8ECFF), c(0xFF8FA8FF), false),
        Theme("Sunset", intArrayOf(c(0xFF2A0F1F), c(0xFF5A1F3A), c(0xFFB24A3C)), c(0xFFFFF1E6), c(0xFFFFB067), false),
        Theme("Ocean", intArrayOf(c(0xFF06202B), c(0xFF0B3C4F), c(0xFF14677A)), c(0xFFE6F7F7), c(0xFF6FE0D2), false),
        Theme("Rose", intArrayOf(c(0xFF2B1020), c(0xFF4A1D3A), c(0xFF7A2E55)), c(0xFFFFEAF2), c(0xFFFF9EC4), false),
        Theme("Paper", intArrayOf(c(0xFFF4EFE6), c(0xFFEDE6D8), c(0xFFE3DACA)), c(0xFF2B2A26), c(0xFF5C7F4A), true),
        Theme("AMOLED", intArrayOf(c(0xFF000000), c(0xFF000000), c(0xFF0A0A0A)), c(0xFFF2F2F2), c(0xFF9ADBC3), false)
    )
}

object Fonts {
    val names = listOf("Light", "Regular", "Condensed", "Serif", "Mono")
    val families = listOf("sans-serif-light", "sans-serif", "sans-serif-condensed", "serif", "monospace")
}

data class Cfg(
    val theme: Int = 0,
    val bgMode: Int = 0,          // 0 theme, 1 colour, 2 photo
    val solid: Int = c(0xFF14211A),
    val dim: Int = 35,
    val blur: Int = 20,
    val accent: Int = 0,          // 0 = auto (theme accent)
    val urgency: Boolean = true,
    val font: Int = 0,
    val ring: Boolean = true,
    val card: Boolean = true,
    val pill: Boolean = true,
    val timeLeft: Boolean = true,
    val seconds: Boolean = true,
    val unit: Int = 0,            // 0 days, 1 weeks, 2 hours
    val label: String = "",
    val goal: String = "",
    val effect: Int = 1,          // 0 off, 1 glow dots, 2 stars
    val onboarded: Boolean = false,
    val photoVersion: Long = 0L
)

object CfgStore {
    @Volatile private var cache: Cfg? = null
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun get(ctx: Context): Cfg {
        cache?.let { return it }
        val p = prefs(ctx)
        val d = Cfg()
        val cfg = Cfg(
            theme = p.getInt("theme", d.theme),
            bgMode = p.getInt("bgMode", d.bgMode),
            solid = p.getInt("solid", d.solid),
            dim = p.getInt("dim", d.dim),
            blur = p.getInt("blur", d.blur),
            accent = p.getInt("accent", d.accent),
            urgency = p.getBoolean("urgency", d.urgency),
            font = p.getInt("font", d.font),
            ring = p.getBoolean("ring", d.ring),
            card = p.getBoolean("card", d.card),
            pill = p.getBoolean("pill", d.pill),
            timeLeft = p.getBoolean("timeLeft", d.timeLeft),
            seconds = p.getBoolean("seconds", d.seconds),
            unit = p.getInt("unit", d.unit),
            label = p.getString("label", "") ?: "",
            goal = p.getString("goal", "") ?: "",
            effect = p.getInt("effect", d.effect),
            onboarded = p.getBoolean("onboarded", d.onboarded),
            photoVersion = p.getLong("photoVersion", d.photoVersion)
        )
        cache = cfg
        return cfg
    }

    fun set(ctx: Context, cfg: Cfg) {
        cache = cfg
        prefs(ctx).edit()
            .putInt("theme", cfg.theme).putInt("bgMode", cfg.bgMode).putInt("solid", cfg.solid)
            .putInt("dim", cfg.dim).putInt("blur", cfg.blur).putInt("accent", cfg.accent)
            .putBoolean("urgency", cfg.urgency).putInt("font", cfg.font)
            .putBoolean("ring", cfg.ring).putBoolean("card", cfg.card).putBoolean("pill", cfg.pill)
            .putBoolean("timeLeft", cfg.timeLeft).putBoolean("seconds", cfg.seconds)
            .putInt("unit", cfg.unit).putString("label", cfg.label).putString("goal", cfg.goal)
            .putInt("effect", cfg.effect).putBoolean("onboarded", cfg.onboarded)
            .putLong("photoVersion", cfg.photoVersion)
            .apply()
        changed()
    }

    fun changed() {
        listeners.forEach { it() }
    }
}
