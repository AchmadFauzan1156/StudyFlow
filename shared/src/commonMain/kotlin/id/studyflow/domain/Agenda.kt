package id.studyflow.domain

import kotlinx.datetime.TimeZone

/** Kelompok waktu tugas terbuka di agenda Beranda, berurutan dari yang paling mendesak. */
enum class AgendaGroup(val label: String) {
    OVERDUE("Terlambat"),
    TODAY("Hari ini"),
    NEXT_7_DAYS("7 hari ke depan"),
    LATER("Nanti"),
}

/** Batas kelompok "7 hari ke depan", dihitung dalam hari kalender pada zona tampilan. */
private const val NEXT_DAYS = 7

fun agendaGroup(dueAt: Long, nowMillis: Long, displayZone: TimeZone): AgendaGroup {
    if (dueAt < nowMillis) return AgendaGroup.OVERDUE
    val days = calendarDaysBetween(nowMillis, dueAt, displayZone)
    return when {
        days <= 0 -> AgendaGroup.TODAY
        days <= NEXT_DAYS -> AgendaGroup.NEXT_7_DAYS
        else -> AgendaGroup.LATER
    }
}

/**
 * Label deadline ringkas untuk daftar: "Hari ini · 16.30", "Kemarin · 09.00", "Besok · 07.15",
 * "Jum, 2 Okt · 23.59"; tahun hanya ditulis jika berbeda dari tahun sekarang.
 */
fun shortDueLabel(dueAt: Long, nowMillis: Long, displayZone: TimeZone): String {
    val due = toLocalDateTime(dueAt, displayZone)
    val time = formatTime(due.hour, due.minute)
    val day = when (calendarDaysBetween(nowMillis, dueAt, displayZone)) {
        0L -> "Hari ini"
        -1L -> "Kemarin"
        1L -> "Besok"
        else -> {
            val date = formatDate(due)
            if (due.year == toLocalDateTime(nowMillis, displayZone).year) date.substringBeforeLast(' ') else date
        }
    }
    return "$day · $time"
}

private fun calendarDaysBetween(fromMillis: Long, toMillis: Long, zone: TimeZone): Long =
    toLocalDateTime(toMillis, zone).date.toEpochDays() - toLocalDateTime(fromMillis, zone).date.toEpochDays()
