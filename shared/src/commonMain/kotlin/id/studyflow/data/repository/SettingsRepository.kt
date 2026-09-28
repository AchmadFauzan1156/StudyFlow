package id.studyflow.data.repository

import id.studyflow.data.local.SettingEntity
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(db: StudyFlowDatabase) {
    private val dao = db.settingsDao()
    private val outboxDao = db.outboxDao()

    fun observeThemeMode(): Flow<ThemeMode> =
        dao.observe(KEY_THEME).map { value ->
            ThemeMode.entries.firstOrNull { it.name == value } ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) = dao.put(SettingEntity(KEY_THEME, mode.name))

    /** Jumlah perubahan lokal yang menunggu sinkronisasi (dikirim mulai fase 2). */
    fun observePendingChanges(): Flow<Int> = outboxDao.observePendingCount()

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}
