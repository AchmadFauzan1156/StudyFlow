package id.studyflow.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Membuka file database berdasarkan nama (adapter per platform). */
fun interface DatabaseFactory {
    fun open(name: String): StudyFlowDatabase
}

/**
 * Menentukan database yang sedang dipakai UI: mode lokal atau database milik akun yang masuk.
 * Tiap akun memakai file terpisah sehingga data akun sebelumnya tidak terlihat oleh akun lain.
 */
class DatabaseManager(private val factory: DatabaseFactory) {
    private val opened = mutableMapOf<String, StudyFlowDatabase>()
    private val _current = MutableStateFlow(open(StudyFlowDatabase.LOCAL_DB_NAME))
    private val _accountId = MutableStateFlow<String?>(null)

    /** Database aktif; UI mengamati flow ini agar ikut berganti saat login/logout. */
    val current: StateFlow<StudyFlowDatabase> = _current.asStateFlow()

    /** ID akun pemilik database aktif, atau null untuk mode lokal. */
    val accountId: StateFlow<String?> = _accountId.asStateFlow()

    val db: StudyFlowDatabase get() = _current.value

    /** Database mode lokal (juga menyimpan preferensi perangkat seperti tema). */
    val local: StudyFlowDatabase get() = open(StudyFlowDatabase.LOCAL_DB_NAME)

    fun useAccount(userId: String?) {
        val name = if (userId == null) StudyFlowDatabase.LOCAL_DB_NAME else StudyFlowDatabase.accountDbName(userId)
        _current.value = open(name)
        _accountId.value = userId
    }

    private fun open(name: String): StudyFlowDatabase = synchronized(opened) {
        opened.getOrPut(name) { factory.open(name) }
    }
}
