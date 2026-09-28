package id.studyflow.data

import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Sumber waktu dan ID yang dapat diganti di tes. */
interface AppClock {
    fun nowMillis(): Long
    fun currentZone(): TimeZone
}

object SystemAppClock : AppClock {
    @OptIn(ExperimentalTime::class)
    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /** Zona perangkat; jika tidak dapat ditentukan, default Asia/Jakarta sesuai spesifikasi. */
    override fun currentZone(): TimeZone =
        runCatching { TimeZone.currentSystemDefault() }.getOrElse { TimeZone.of("Asia/Jakarta") }
}

fun interface IdGenerator {
    fun newId(): String
}

object UuidGenerator : IdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    override fun newId(): String = Uuid.random().toString()
}
