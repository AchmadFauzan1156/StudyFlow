package id.studyflow.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadlinesTest {
    private val jakarta = TimeZone.of("Asia/Jakarta")
    private val now = toEpochMillis(LocalDateTime(2026, 9, 28, 10, 0), jakarta)

    private fun at(day: Int, hour: Int, minute: Int = 0, zone: TimeZone = jakarta) =
        toEpochMillis(LocalDateTime(2026, 9, day, hour, minute), zone)

    @Test
    fun doneTaskIsAlwaysDone() {
        assertEquals(DeadlineStatus.DONE, deadlineStatus(at(1, 0), TaskStatus.DONE, now, jakarta))
    }

    @Test
    fun pastDeadlineIsOverdue() {
        assertEquals(DeadlineStatus.OVERDUE, deadlineStatus(at(28, 9, 59), TaskStatus.TODO, now, jakarta))
    }

    @Test
    fun laterTodayIsDueToday() {
        assertEquals(DeadlineStatus.DUE_TODAY, deadlineStatus(at(28, 23, 59), TaskStatus.TODO, now, jakarta))
    }

    @Test
    fun withinThreeDaysIsDueSoon() {
        assertEquals(DeadlineStatus.DUE_SOON, deadlineStatus(at(29, 0, 1), TaskStatus.TODO, now, jakarta))
        val oct1 = toEpochMillis(LocalDateTime(2026, 10, 1, 23, 59), jakarta)
        assertEquals(DeadlineStatus.DUE_SOON, deadlineStatus(oct1, TaskStatus.TODO, now, jakarta))
    }

    @Test
    fun beyondThreeDaysIsLater() {
        val fourDaysLater = toEpochMillis(LocalDateTime(2026, 10, 2, 8, 0), jakarta)
        assertEquals(DeadlineStatus.LATER, deadlineStatus(fourDaysLater, TaskStatus.TODO, now, jakarta))
    }

    @Test
    fun todayIsEvaluatedInDisplayZone() {
        // 29 Sep 01.00 WIB = 28 Sep 18.00 UTC: masih "hari ini" bagi pengguna di UTC.
        val due = at(29, 1)
        val utc = TimeZone.UTC
        assertEquals(DeadlineStatus.DUE_TODAY, deadlineStatus(due, TaskStatus.TODO, now, utc))
        assertEquals(DeadlineStatus.DUE_SOON, deadlineStatus(due, TaskStatus.TODO, now, jakarta))
    }

    @Test
    fun instantIsStableAcrossZones() {
        val due = at(30, 23, 59)
        // 23.59 WIB = 16.59 UTC pada instant yang sama.
        assertEquals(LocalDateTime(2026, 9, 30, 16, 59), toLocalDateTime(due, TimeZone.UTC))
    }

    @Test
    fun formatsIndonesianDateTime() {
        assertEquals("Rab, 30 Sep 2026 · 23.59", formatDateTime(at(30, 23, 59), jakarta))
        assertEquals("07.05", formatTime(7, 5))
    }
}
