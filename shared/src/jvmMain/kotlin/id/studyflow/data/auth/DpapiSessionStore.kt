package id.studyflow.data.auth

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Windows: sesi dienkripsi dengan DPAPI (CryptProtectData) sehingga hanya akun Windows yang sama
 * yang dapat membukanya. File terenkripsi disimpan di folder data aplikasi.
 */
class DpapiSessionStore(private val file: File) : SecureSessionStore {
    override val isPersistent = true

    override fun load(): StoredSession? {
        if (!file.exists()) return null
        return runCatching {
            val plain = Crypt32Util.cryptUnprotectData(file.readBytes())
            Json.decodeFromString(StoredSession.serializer(), plain.decodeToString())
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(session: StoredSession) {
        val encrypted = Crypt32Util.cryptProtectData(
            Json.encodeToString(StoredSession.serializer(), session).encodeToByteArray(),
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override fun clear() {
        file.delete()
    }
}

/** DPAPI hanya ada di Windows; OS lain memakai sesi memory-only (masuk ulang setelah aplikasi ditutup). */
fun desktopSessionStore(dataDir: File): SecureSessionStore =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        DpapiSessionStore(File(dataDir, "session.bin"))
    } else {
        MemorySessionStore()
    }
