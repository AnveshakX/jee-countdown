package com.jeecountdown

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Urgency { NONE, AMBER, RED }

/** Everything a surface (wallpaper, widget, preview, desk mode) needs to display, computed in ONE place. */
class CountdownModel(
    val hasEvent: Boolean,
    val name: String,
    val phase: Phase,
    val calendarDays: Long,
    val bigNumber: Long,            // the headline number in the chosen unit; -1 when there is no event
    val label: String,
    val dateText: String,
    val timeLeftText: String,       // "14H 32M 08S LEFT TODAY"
    val timeLeftNoSeconds: String,  // "14H 32M LEFT TODAY"
    val progress: Float,
    val urgency: Urgency
)

object ModelBuilder {
    const val AMBER_WITHIN_DAYS = 30L
    const val RED_WITHIN_DAYS = 10L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_MINUTE = 60L
    private const val NO_NUMBER = -1L
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    private fun pad2(n: Long): String = if (n < 10L) "0$n" else n.toString()

    fun build(exams: List<Exam>, cfg: Cfg, now: ZonedDateTime): CountdownModel {
        val today = now.toLocalDate()
        val up = ExamSelection.next(exams, today.toEpochDay())
        val custom = cfg.label.trim().uppercase()

        if (up == null) {
            return CountdownModel(
                hasEvent = false, name = "", phase = Phase.PAST, calendarDays = 0L, bigNumber = NO_NUMBER,
                label = custom.ifEmpty { "ADD A COUNTDOWN" }, dateText = "", timeLeftText = "",
                timeLeftNoSeconds = "", progress = 1f, urgency = Urgency.NONE
            )
        }

        val date = LocalDate.ofEpochDay(up.exam.epochDay)
        val cd = CountdownEngine.compute(date, now)
        val big = when (cfg.unit) {
            1 -> CountdownEngine.weeks(cd.calendarDays)
            2 -> cd.hoursUntilStart
            else -> cd.calendarDays
        }
        val unitName = when (cfg.unit) {
            1 -> "WEEKS"
            2 -> "HOURS"
            else -> "DAYS"
        }
        val name = up.exam.name
        val label = when {
            custom.isNotEmpty() -> custom
            cd.phase == Phase.TODAY && cfg.unit == 0 -> "$name IS TODAY"
            else -> "$unitName TO $name"
        }

        val secs = cd.remainingToday.seconds.coerceAtLeast(0L)
        val h = secs / SECONDS_PER_HOUR
        val m = (secs % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val s = secs % SECONDS_PER_MINUTE

        val urgency = when {
            cd.calendarDays <= RED_WITHIN_DAYS -> Urgency.RED
            cd.calendarDays <= AMBER_WITHIN_DAYS -> Urgency.AMBER
            else -> Urgency.NONE
        }

        return CountdownModel(
            hasEvent = true,
            name = name,
            phase = cd.phase,
            calendarDays = cd.calendarDays,
            bigNumber = big,
            label = label,
            dateText = date.format(DATE_FMT).uppercase(),
            timeLeftText = "${h}H ${pad2(m)}M ${pad2(s)}S LEFT TODAY",
            timeLeftNoSeconds = "${h}H ${pad2(m)}M LEFT TODAY",
            progress = ExamSelection.progress(up.ringStartDay, up.exam.epochDay, today.toEpochDay()),
            urgency = urgency
        )
    }
}
