package id.studyflow.data.repository

import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.SettingEntity
import id.studyflow.domain.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepository(private val databases: DatabaseManager) {

    /** Tema adalah preferensi perangkat, jadi selalu disimpan di database lokal. */
    fun observeThemeMode(): Flow<ThemeMode> = databases.local.settingsDao().observe(KEY_THEME).map { value ->
        ThemeMode.entries.firstOrNull { it.name == value } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) = databases.local.settingsDao().put(SettingEntity(KEY_THEME, mode.name))

    /** Jumlah perubahan di database aktif yang menunggu dikirim ke server. */
    fun observePendingChanges(): Flow<Int> = databases.current.flatMapLatest { it.outboxDao().observePendingCount() }

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}
