package com.jeecountdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamModelTest {
    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay()

    // ---- codec ----
    @Test fun codecRoundTrip() {
        val list = listOf(Exam("JEE MAIN", 20_000, 19_900), Exam("MY BIRTHDAY", 20_100, 19_950))
        val d = ExamCodec.decode(ExamCodec.encode(list), 0L)
        assertEquals(list, d.exams)
        assertEquals(0, d.skipped)
    }

    @Test fun namesAreSanitised() {
        assertEquals("A B C", ExamCodec.sanitizeName("A|B;C"))
        assertEquals("EVENT", ExamCodec.sanitizeName("  |;  "))
        assertEquals(ExamCodec.MAX_NAME, ExamCodec.sanitizeName("X".repeat(100)).length)
    }

    @Test fun legacyFormatKeepsDataAndAnchorsRingToLegacyStart() {
        val d = ExamCodec.decode("JEE MAIN|20000;NEET UG|20100", 19_000L)
        assertEquals(2, d.exams.size)
        assertEquals(Exam("JEE MAIN", 20_000, 19_000), d.exams[0])
        assertEquals(19_000L, d.exams[1].createdDay)
    }

    @Test fun corruptedEntriesAreSkippedAndCounted() {
        val d = ExamCodec.decode("v2:A|x|1;B|20000|5;C|20000;D|-5|1;E|999999999|1", 0L)
        assertEquals(listOf(Exam("B", 20_000, 5)), d.exams)
        assertEquals(4, d.skipped)
    }

    @Test fun emptyInputsDecodeToEmptyList() {
        for (raw in listOf(null, "", "   ", "v2:")) {
            val d = ExamCodec.decode(raw, 0L)
            assertTrue(d.exams.isEmpty())
            assertEquals(0, d.skipped)
        }
    }

    // ---- selection ----
    @Test fun eventDatedTodayStillCounts() {
        val up = ExamSelection.next(listOf(Exam("A", 100, 10)), 100)
        assertNotNull(up)
        assertEquals("A", up!!.exam.name)
    }

    @Test fun nothingUpcomingReturnsNull() {
        assertNull(ExamSelection.next(listOf(Exam("A", 100, 10)), 101))
        assertNull(ExamSelection.next(emptyList(), 0))
    }

    @Test fun ringStartsAtPreviousEventOrCreation() {
        val a = Exam("A", 100, 10)
        val b = Exam("B", 200, 20)
        assertEquals(10L, ExamSelection.next(listOf(a, b), 50)!!.ringStartDay)   // first: its own creation
        assertEquals(100L, ExamSelection.next(listOf(a, b), 150)!!.ringStartDay) // second: previous event's date
        // created AFTER the previous event passed => its own creation day wins
        assertEquals(150L, ExamSelection.next(listOf(a, Exam("C", 200, 150)), 160)!!.ringStartDay)
    }

    @Test fun twoEventsOnTheSameDayDoNotAnchorEachOther() {
        val x = Exam("X", 200, 20)
        val y = Exam("Y", 200, 30)
        assertEquals(20L, ExamSelection.next(listOf(y, x), 100)!!.ringStartDay)
    }

    @Test fun progressMath() {
        assertEquals(0.5f, ExamSelection.progress(100, 200, 150), 1e-6f)
        assertEquals(0f, ExamSelection.progress(100, 200, 90), 0f)
        assertEquals(1f, ExamSelection.progress(100, 200, 200), 0f)
        assertEquals(1f, ExamSelection.progress(100, 200, 250), 0f)
        assertEquals(0f, ExamSelection.progress(200, 200, 150), 0f) // degenerate span never divides by zero
    }

    // ---- repository: persistence, restart, upgrade ----
    @Test fun eventsSurviveRestart() {
        val store = MemoryStore()
        val repo1 = ExamRepo(store, { 19_000L })
        repo1.add("JEE MAIN", 20_000)
        repo1.add("NEET UG", 20_100)
        val repo2 = ExamRepo(store, { 19_500L }) // "process restart": new object, same storage
        assertEquals(listOf(Exam("JEE MAIN", 20_000, 19_000), Exam("NEET UG", 20_100, 19_000)), repo2.load())
    }

    @Test fun editAndDeletePersist() {
        val store = MemoryStore()
        val repo = ExamRepo(store, { 19_000L })
        repo.add("A", 20_000)
        val a = repo.load().single()
        repo.updateDate(a, 20_050)
        assertEquals(20_050L, ExamRepo(store, { 0L }).load().single().epochDay)
        assertEquals(19_000L, ExamRepo(store, { 0L }).load().single().createdDay) // creation anchor is preserved
        repo.remove(repo.load().single())
        assertTrue(ExamRepo(store, { 0L }).load().isEmpty())
    }

    @Test fun versionChangesOnEveryWrite() {
        val repo = ExamRepo(MemoryStore(), { 1L })
        val v0 = repo.version
        repo.add("A", 20_000)
        assertTrue(repo.version > v0)
    }

    @Test fun upgradeFromLegacyKeepsUserDataAndMigratesToV2() {
        val store = MemoryStore(mapOf(ExamRepo.KEY_EXAMS to "JEE MAIN|20000", ExamRepo.KEY_LEGACY_START to 19_000L))
        val logs = ArrayList<String>()
        val loaded = ExamRepo(store, { 99_999L }, { logs.add(it) }).load()
        assertEquals(listOf(Exam("JEE MAIN", 20_000, 19_000)), loaded)
        assertTrue((store.all()[ExamRepo.KEY_EXAMS] as String).startsWith("v2:"))
        assertTrue(logs.any { it.contains("Migrated") })
    }

    @Test fun corruptedStorageNeverCrashesAndIsLogged() {
        val store = MemoryStore(mapOf(ExamRepo.KEY_EXAMS to "v2:garbage;B|20000|5"))
        val logs = ArrayList<String>()
        val loaded = ExamRepo(store, { 1L }, { logs.add(it) }).load()
        assertEquals(listOf(Exam("B", 20_000, 5)), loaded)
        assertTrue(logs.any { it.contains("Skipped 1") })
        // wrong value type under the key is treated as "no data"
        assertTrue(ExamRepo(MemoryStore(mapOf(ExamRepo.KEY_EXAMS to 42)), { 1L }).load().isEmpty())
    }

    @Test fun repoNextUsesCreatedDayFromTheInjectedClock() {
        val repo = ExamRepo(MemoryStore(), { day(2026, 11, 19) })
        repo.add("JEE MAIN", day(2027, 1, 22))
        val up = repo.next(day(2026, 12, 21))!!
        assertEquals(day(2026, 11, 19), up.ringStartDay)
    }
}
