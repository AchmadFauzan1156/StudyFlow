package id.studyflow.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.auth.SignUpOutcome
import id.studyflow.data.remote.AuthException
import id.studyflow.data.repository.SettingsRepository
import id.studyflow.data.sync.ConflictItem
import id.studyflow.data.sync.ConflictRepository
import id.studyflow.data.sync.LocalImport
import id.studyflow.data.sync.SyncManager
import id.studyflow.data.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(
    val auth: AuthState = AuthState.SignedOut,
    val sync: SyncStatus = SyncStatus(),
    val pendingChanges: Int = 0,
    val conflicts: Int = 0,
    val persistsSession: Boolean = true,
)

data class AuthFormState(
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

class AccountViewModel(
    private val session: SessionManager,
    private val syncManager: SyncManager,
    settings: SettingsRepository,
    conflicts: ConflictRepository,
    private val localImport: LocalImport,
) : ViewModel() {
    val state: StateFlow<AccountUiState> = combine(
        session.state,
        syncManager.status,
        settings.observePendingChanges(),
        conflicts.observeCount(),
    ) { auth, sync, pending, conflictCount ->
        AccountUiState(auth, sync, pending, conflictCount, session.persistsSession)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState(auth = session.state.value))

    private val _form = MutableStateFlow(AuthFormState())
    val form: StateFlow<AuthFormState> = _form.asStateFlow()

    private val _localCourses = MutableStateFlow(0)
    val localCourses: StateFlow<Int> = _localCourses.asStateFlow()

    init {
        // Hitung ulang data mode lokal setiap status akun berubah (mis. setelah masuk atau keluar).
        viewModelScope.launch {
            session.state.collect { _localCourses.value = localImport.localCourseCount() }
        }
    }

    fun signIn(email: String, password: String, onDone: () -> Unit) = runAuth {
        session.signIn(email, password)
        onDone()
    }

    fun signUp(email: String, password: String, onDone: () -> Unit) = runAuth {
        when (session.signUp(email, password)) {
            SignUpOutcome.SignedIn -> onDone()
            SignUpOutcome.ConfirmEmail -> _form.update {
                it.copy(info = "Akun dibuat. Buka tautan konfirmasi di email kamu, lalu masuk.")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }

    fun syncNow() = syncManager.requestSync()

    fun importLocal() {
        val auth = session.state.value as? AuthState.SignedIn ?: return
        viewModelScope.launch {
            localImport.importInto(auth.userId)
            syncManager.requestSync()
        }
    }

    fun clearForm() {
        _form.value = AuthFormState()
    }

    private fun runAuth(block: suspend () -> Unit) {
        if (_form.value.busy) return
        _form.value = AuthFormState(busy = true)
        viewModelScope.launch {
            try {
                block()
                _form.update { it.copy(busy = false) }
            } catch (e: AuthException) {
                _form.value = AuthFormState(error = e.message)
            }
        }
    }
}

class ConflictsViewModel(private val conflicts: ConflictRepository) : ViewModel() {
    val items: StateFlow<List<ConflictItem>?> = conflicts.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun keepLocal(item: ConflictItem) {
        viewModelScope.launch { conflicts.keepLocal(item.entityType, item.entityId) }
    }

    fun keepServer(item: ConflictItem) {
        viewModelScope.launch { conflicts.keepServer(item.entityType, item.entityId) }
    }
}
