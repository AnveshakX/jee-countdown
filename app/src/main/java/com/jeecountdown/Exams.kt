package com.jeecountdown

import android.content.Context
import java.time.LocalDate

data class Exam(val name: String, val epochDay: Long)
data class Upcoming(val exam: Exam, val startDay: Long)

object Exams {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("p", Context.MODE_PRIVATE)

    fun load(c: Context): List<Exam> {
        val raw = prefs(c).getString("exams", null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { p ->
            val a = p.split("|")
            val d = a.getOrNull(1)?.toLongOrNull()
            if (a.size == 2 && d != null) Exam(a[0], d) else null
        }
    }

    fun save(c: Context, list: List<Exam>) {
        val s = list.joinToString(";") { "${it.name.replace(Regex("[|;]"), " ")}|${it.epochDay}" }
        prefs(c).edit().putString("exams", s).apply()
        CfgStore.changed()
    }

    fun add(c: Context, e: Exam) {
        val l = load(c)
        if (l.isEmpty()) prefs(c).edit().putLong("s", LocalDate.now().toEpochDay()).apply()
        save(c, l + e)
    }

    // The day the first countdown "started": when it was added
    private fun start(c: Context): Long {
        val p = prefs(c)
        if (!p.contains("s")) p.edit().putLong("s", LocalDate.now().toEpochDay()).apply()
        return p.getLong("s", LocalDate.now().toEpochDay())
    }

    // Nearest countdown that hasn't passed; ring starts from the previous one (or when the first was added)
    fun next(c: Context, today: LocalDate): Upcoming? {
        val t = today.toEpochDay()
        val sorted = load(c).sortedBy { it.epochDay }
        val i = sorted.indexOfFirst { it.epochDay >= t }
        if (i < 0) return null
        val prev = sorted.getOrNull(i - 1)
        return Upcoming(sorted[i], prev?.epochDay ?: start(c))
    }
}
