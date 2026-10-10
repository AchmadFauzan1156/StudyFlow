package id.studyflow.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class DeadlineStatus { DONE, OVERDUE, DUE_TODAY, DUE_SOON, LATER }

/** Jendela "segera" untuk deadline yang belum hari ini. */
const val DUE_SOON_DAYS = 3

/**
 * Mengklasifikasikan deadline relatif terhadap [nowMillis]. "Hari ini" dihitung pada [displayZone]
 * (zona yang sedang dipakai pengguna), bukan zona saat deadline dibuat.
 */
@OptIn(ExperimentalTime::class)
fun deadlineStatus(dueAt: Long, status: TaskStatus, nowMillis: Long, displayZone: TimeZone): DeadlineStatus {
    if (status == TaskStatus.DONE) return DeadlineStatus.DONE
    if (dueAt < nowMillis) return DeadlineStatus.OVERDUE
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(displayZone).date
    val dueDate = Instant.fromEpochMilliseconds(dueAt).toLocalDateTime(displayZone).date
    val days = dueDate.toEpochDays() - today.toEpochDays()
    return when {
        days <= 0L -> DeadlineStatus.DUE_TODAY
        days <= DUE_SOON_DAYS -> DeadlineStatus.DUE_SOON
        else -> DeadlineStatus.LATER
    }
}

@OptIn(ExperimentalTime::class)
fun toEpochMillis(dateTime: LocalDateTime, zone: TimeZone): Long = dateTime.toInstant(zone).toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
fun toLocalDateTime(epochMillis: Long, zone: TimeZone): LocalDateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)

private val dayNames = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

/** Contoh: "Sen, 29 Sep 2026 · 23.59". */
fun formatDateTime(epochMillis: Long, zone: TimeZone): String {
    val dt = toLocalDateTime(epochMillis, zone)
    return "${formatDate(dt)} · ${formatTime(dt.hour, dt.minute)}"
}

fun formatDate(dt: LocalDateTime): String = "${dayNames[dt.dayOfWeek.ordinal]}, ${dt.day} ${monthNames[dt.month.ordinal]} ${dt.year}"

fun formatTime(hour: Int, minute: Int): String = "${hour.toString().padStart(2, '0')}.${minute.toString().padStart(2, '0')}"
