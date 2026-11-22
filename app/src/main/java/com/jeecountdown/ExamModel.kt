package com.jeecountdown

/** [createdDay] (epoch day) is when the countdown was added; it anchors the progress ring. */
data class Exam(val name: String, val epochDay: Long, val createdDay: Long)

class Upcoming(val exam: Exam, val ringStartDay: Long)

object ExamCodec {
    private const val V2 = "v2:"
    const val MAX_NAME = 24
    private const val MAX_EPOCH_DAY = 110_000L // ~ year 2271; rejects absurd stored values

    fun sanitizeName(raw: String): String =
        raw.replace(Regex("[|;\\r\\n]"), " ").trim().take(MAX_NAME).ifEmpty { "EVENT" }

    fun isLegacy(raw: String?): Boolean = raw != null && raw.isNotBlank() && !raw.startsWith(V2)

    fun encode(list: List<Exam>): String =
        V2 + list.joinToString(";") { "${sanitizeName(it.name)}|${it.epochDay}|${it.createdDay}" }

    class Decoded(val exams: List<Exam>, val skipped: Int)

    /** Reads the current v2 format and the legacy "name|epochDay" format (then [legacyStartDay] anchors the ring). */
    fun decode(raw: String?, legacyStartDay: Long): Decoded {
        if (raw == null || raw.isBlank()) return Decoded(emptyList(), 0)
        val v2 = raw.startsWith(V2)
        val body = if (v2) raw.removePrefix(V2) else raw
        if (body.isBlank()) return Decoded(emptyList(), 0)
        var skipped = 0
        val out = ArrayList<Exam>()
        for (part in body.split(";")) {
            val a = part.split("|")
            val sizeOk = if (v2) a.size == 3 else a.size == 2
            val epoch = a.getOrNull(1)?.toLongOrNull()
            val created = if (v2) a.getOrNull(2)?.toLongOrNull() else legacyStartDay
            if (!sizeOk || epoch == null || created == null || epoch !in 0L..MAX_EPOCH_DAY) {
                skipped++
                continue
            }
            out.add(Exam(sanitizeName(a[0]), epoch, created))
        }
        return Decoded(out, skipped)
    }
}

object ExamSelection {
    /** Nearest event that has not passed (an event dated today still counts). */
    fun next(exams: List<Exam>, todayEpochDay: Long): Upcoming? {
        val sorted = exams.sortedWith(compareBy({ it.epochDay }, { it.name }))
        val i = sorted.indexOfFirst { it.epochDay >= todayEpochDay }
        if (i < 0) return null
        val e = sorted[i]
        // Ring starts at the previous (strictly earlier) event, or when this one was created, whichever is later.
        val prev = sorted.getOrNull(i - 1)?.epochDay?.takeIf { it < e.epochDay }
        return Upcoming(e, maxOf(e.createdDay, prev ?: e.createdDay))
    }

    /** 0..1 fraction of the span [startDay, eventDay] that has elapsed. Full on/after the event day. */
    fun progress(startDay: Long, eventDay: Long, todayEpochDay: Long): Float {
        if (todayEpochDay >= eventDay) return 1f
        val total = (eventDay - startDay).coerceAtLeast(1L)
        val done = (todayEpochDay - startDay).coerceIn(0L, total)
        return done.toFloat() / total.toFloat()
    }
}

/** Persistence + in-memory cache for the countdown list. Pure Kotlin over [KeyValueStore] (JVM-testable). */
class ExamRepo(
    private val store: KeyValueStore,
    private val todayEpochDay: () -> Long,
    private val log: (String) -> Unit = {}
) {
    @Volatile private var cache: List<Exam>? = null
    @Volatile var version: Int = 0
        private set

    @Synchronized
    fun load(): List<Exam> {
        cache?.let { return it }
        val all = store.all()
        val raw = all[KEY_EXAMS] as? String
        val legacyStart = (all[KEY_LEGACY_START] as? Long) ?: todayEpochDay()
        val decoded = ExamCodec.decode(raw, legacyStart)
        if (decoded.skipped > 0) log("Skipped ${decoded.skipped} unreadable countdown entries")
        if (ExamCodec.isLegacy(raw) && decoded.exams.isNotEmpty()) {
            store.write(mapOf(KEY_EXAMS to ExamCodec.encode(decoded.exams))) // one-time migration to v2
            log("Migrated ${decoded.exams.size} countdowns to v2 format")
        }
        cache = decoded.exams
        return decoded.exams
    }

    @Synchronized
    fun save(list: List<Exam>) {
        val clean = list.map { it.copy(name = ExamCodec.sanitizeName(it.name)) }
        cache = clean
        version++
        store.write(mapOf(KEY_EXAMS to ExamCodec.encode(clean)))
    }

    fun add(name: String, epochDay: Long) = save(load() + Exam(name, epochDay, todayEpochDay()))

    fun updateDate(old: Exam, newEpochDay: Long) =
        save(load().map { if (it == old) it.copy(epochDay = newEpochDay) else it })

    fun remove(old: Exam) = save(load().filter { it != old })

    fun next(todayEpochDay: Long): Upcoming? = ExamSelection.next(load(), todayEpochDay)

    companion object {
        const val KEY_EXAMS = "exams"
        const val KEY_LEGACY_START = "s"
    }
}
