package id.studyflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.repository.SettingsRepository
import id.studyflow.data.sync.LocalImport
import id.studyflow.domain.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(
    settings: SettingsRepository,
    private val session: SessionManager,
    private val localImport: LocalImport,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = settings.observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** Jumlah mata kuliah mode lokal yang ditawarkan untuk diimpor setelah masuk; null = tidak ada tawaran. */
    private val _importOffer = MutableStateFlow<Int?>(null)
    val importOffer: StateFlow<Int?> = _importOffer.asStateFlow()

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                _importOffer.value = if (state is AuthState.SignedIn && !state.sessionExpired &&
                    localImport.shouldOffer(state.userId)
                ) {
                    localImport.localCourseCount()
                } else {
                    null
                }
            }
        }
    }

    fun acceptImport() = resolveOffer(import = true)

    fun declineImport() = resolveOffer(import = false)

    private fun resolveOffer(import: Boolean) {
        val state = session.state.value as? AuthState.SignedIn ?: return
        _importOffer.value = null
        viewModelScope.launch {
            if (import) localImport.importInto(state.userId) else localImport.markOffered(state.userId)
        }
    }
}
