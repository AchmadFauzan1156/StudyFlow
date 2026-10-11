package id.studyflow.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class AgendaTest {
    private val jakarta = TimeZone.of("Asia/Jakarta")

    // Minggu, 28 Sep 2026 pukul 10.00.
    private val now = toEpochMillis(LocalDateTime(2026, 9, 28, 10, 0), jakarta)

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0, year: Int = 2026) =
        toEpochMillis(LocalDateTime(year, month, day, hour, minute), jakarta)

    @Test
    fun groupsOpenTasksByTime() {
        assertEquals(AgendaGroup.OVERDUE, agendaGroup(at(9, 28, 9, 59), now, jakarta))
        assertEquals(AgendaGroup.TODAY, agendaGroup(at(9, 28, 23, 59), now, jakarta))
        assertEquals(AgendaGroup.NEXT_7_DAYS, agendaGroup(at(9, 29, 0, 1), now, jakarta))
        assertEquals(AgendaGroup.NEXT_7_DAYS, agendaGroup(at(10, 5, 23, 59), now, jakarta))
        assertEquals(AgendaGroup.LATER, agendaGroup(at(10, 6, 0, 0), now, jakarta))
    }

    @Test
    fun shortDueLabelUsesRelativeDayWhenClose() {
        assertEquals("Hari ini · 16.30", shortDueLabel(at(9, 28, 16, 30), now, jakarta))
        assertEquals("Kemarin · 09.00", shortDueLabel(at(9, 27, 9, 0), now, jakarta))
        assertEquals("Besok · 07.15", shortDueLabel(at(9, 29, 7, 15), now, jakarta))
        assertEquals("Jum, 2 Okt · 23.59", shortDueLabel(at(10, 2, 23, 59), now, jakarta))
        assertEquals("Sen, 4 Jan 2027 · 08.00", shortDueLabel(at(1, 4, 8, 0, year = 2027), now, jakarta))
    }
}
