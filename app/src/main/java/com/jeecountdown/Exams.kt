package com.jeecountdown

import android.content.Context
import android.util.Log
import java.time.LocalDate

/** Android facade over [ExamRepo]; keeps the call sites simple and notifies listeners on change. */
object Exams {
    private const val PREFS_NAME = "p"
    @Volatile private var repo: ExamRepo? = null

    private fun repo(ctx: Context): ExamRepo =
        repo ?: synchronized(this) {
            repo ?: ExamRepo(
                PrefsStore(ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
                { LocalDate.now().toEpochDay() },
                { Log.w("Exams", it) }
            ).also { repo = it }
        }

    fun load(ctx: Context): List<Exam> = repo(ctx).load()
    fun version(ctx: Context): Int = repo(ctx).version

    fun add(ctx: Context, name: String, epochDay: Long) {
        repo(ctx).add(name, epochDay)
        CfgStore.changed()
    }

    fun updateDate(ctx: Context, e: Exam, newEpochDay: Long) {
        repo(ctx).updateDate(e, newEpochDay)
        CfgStore.changed()
    }

    fun remove(ctx: Context, e: Exam) {
        repo(ctx).remove(e)
        CfgStore.changed()
    }
}
