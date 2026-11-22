package com.jeecountdown

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

/** Pure (Android-free) encode/decode/validation of [Cfg]. Corrupt or out-of-range values fall back safely. */
object CfgCodec {
    const val SCHEMA = 2
    const val MAX_TEXT = 30
    private const val OPAQUE = -0x1000000 // 0xFF000000

    private fun Map<String, Any?>.int(k: String, def: Int): Int = (this[k] as? Int) ?: def
    private fun Map<String, Any?>.bool(k: String, def: Boolean): Boolean = (this[k] as? Boolean) ?: def
    private fun Map<String, Any?>.str(k: String): String = (this[k] as? String) ?: ""
    private fun Map<String, Any?>.long(k: String, def: Long): Long = when (val v = this[k]) {
        is Long -> v
        is Int -> v.toLong()
        else -> def
    }

    private fun cleanText(s: String): String =
        s.filter { !it.isISOControl() }.trim().take(MAX_TEXT)

    /** Clamps every field into its valid range. decode(encode(x)) == sanitize(x). */
    fun sanitize(cfg: Cfg): Cfg = cfg.copy(
        theme = cfg.theme.coerceIn(0, Themes.all.size - 1),
        bgMode = cfg.bgMode.coerceIn(0, 2),
        solid = cfg.solid or OPAQUE,
        dim = cfg.dim.coerceIn(0, 100),
        blur = cfg.blur.coerceIn(0, 100),
        accent = if (cfg.accent == 0) 0 else cfg.accent or OPAQUE,
        font = cfg.font.coerceIn(0, Fonts.families.size - 1),
        unit = cfg.unit.coerceIn(0, 2),
        label = cleanText(cfg.label),
        goal = cleanText(cfg.goal),
        effect = cfg.effect.coerceIn(0, 2),
        photoVersion = cfg.photoVersion.coerceAtLeast(0L)
    )

    fun decode(all: Map<String, Any?>): Cfg {
        val d = Cfg()
        return sanitize(
            Cfg(
                theme = all.int("theme", d.theme),
                bgMode = all.int("bgMode", d.bgMode),
                solid = all.int("solid", d.solid),
                dim = all.int("dim", d.dim),
                blur = all.int("blur", d.blur),
                accent = all.int("accent", d.accent),
                urgency = all.bool("urgency", d.urgency),
                font = all.int("font", d.font),
                ring = all.bool("ring", d.ring),
                card = all.bool("card", d.card),
                pill = all.bool("pill", d.pill),
                timeLeft = all.bool("timeLeft", d.timeLeft),
                seconds = all.bool("seconds", d.seconds),
                unit = all.int("unit", d.unit),
                label = all.str("label"),
                goal = all.str("goal"),
                effect = all.int("effect", d.effect),
                onboarded = all.bool("onboarded", d.onboarded),
                photoVersion = all.long("photoVersion", d.photoVersion)
            )
        )
    }

    fun encode(cfg: Cfg): Map<String, Any?> {
        val s = sanitize(cfg)
        return mapOf(
            "schema" to SCHEMA,
            "theme" to s.theme, "bgMode" to s.bgMode, "solid" to s.solid,
            "dim" to s.dim, "blur" to s.blur, "accent" to s.accent,
            "urgency" to s.urgency, "font" to s.font,
            "ring" to s.ring, "card" to s.card, "pill" to s.pill,
            "timeLeft" to s.timeLeft, "seconds" to s.seconds,
            "unit" to s.unit, "label" to s.label, "goal" to s.goal,
            "effect" to s.effect, "onboarded" to s.onboarded,
            "photoVersion" to s.photoVersion
        )
    }
}
