package com.jeecountdown

import android.content.Context
import java.time.LocalDate

data class Exam(val name: String, val epochDay: Long)
data class Upcoming(val exam: Exam, val startDay: Long)

object Exams {
    private fun prefs(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)

    // Dates from NTA / IIT Delhi announcements (Nov 2026). Edit them in the app if they change.
    fun defaults() = listOf(
        Exam("JEE MAIN", LocalDate.of(2027, 1, 20).toEpochDay()),
        Exam("JEE MAIN S2", LocalDate.of(2027, 4, 2).toEpochDay()),
        Exam("JEE ADVANCED", LocalDate.of(2027, 5, 23).toEpochDay())
    )

    fun load(c: Context): List<Exam> {
        val raw = prefs(c).getString("exams", null) ?: return defaults()
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
    }

    // The day the countdown "started": first time the wallpaper/app ran
    private fun start(c: Context): Long {
        val p = prefs(c)
        if (!p.contains("s")) p.edit().putLong("s", LocalDate.now().toEpochDay()).apply()
        return p.getLong("s", LocalDate.now().toEpochDay())
    }

    // Nearest exam that hasn't passed; ring starts from the previous exam (or first run)
    fun next(c: Context, today: LocalDate): Upcoming? {
        val t = today.toEpochDay()
        val sorted = load(c).sortedBy { it.epochDay }
        val i = sorted.indexOfFirst { it.epochDay >= t }
        if (i < 0) return null
        val prev = sorted.getOrNull(i - 1)
        return Upcoming(sorted[i], prev?.epochDay ?: start(c))
    }
}
