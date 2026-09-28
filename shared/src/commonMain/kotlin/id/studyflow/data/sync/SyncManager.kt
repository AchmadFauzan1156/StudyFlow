package id.studyflow.data.sync

import id.studyflow.data.AppClock
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.remote.ApiException
import id.studyflow.data.remote.AuthException
import id.studyflow.data.remote.NotAuthenticatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

enum class SyncPhase { IDLE, SYNCING, OFFLINE, NEEDS_LOGIN }

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.IDLE,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
    val lastReport: SyncReport? = null,
)

/**
 * Menjadwalkan [SyncEngine]: saat masuk, setelah ada perubahan lokal (debounce), berkala saat aplikasi
 * terbuka, dan saat diminta pengguna. Kegagalan sementara dicoba ulang dengan backoff eksponensial.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SyncManager(
    private val engine: SyncEngine,
    private val session: SessionManager,
    private val databases: DatabaseManager,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) {
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private var started = false
    private var failures = 0

    fun start() {
        if (started) return
        started = true
        scope.launch { runLoop() }
        scope.launch {
            session.state.collect { state ->
                if (state is AuthState.SignedIn && !state.sessionExpired) requestSync()
                if (state !is AuthState.SignedIn) _status.value = SyncStatus()
            }
        }
        scope.launch {
            databases.current
                .flatMapLatest { it.outboxDao().observePendingCount() }
                .distinctUntilChanged()
                .drop(1)
                .filter { it > 0 }
                .debounce(LOCAL_CHANGE_DEBOUNCE_MS)
                .collect { requestSync() }
        }
        scope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                requestSync()
            }
        }
    }

    fun requestSync() {
        triggers.trySend(Unit)
    }

    private suspend fun runLoop() {
        for (trigger in triggers) {
            val state = session.state.value
            if (state !is AuthState.SignedIn) continue
            if (state.sessionExpired) {
                _status.update { it.copy(phase = SyncPhase.NEEDS_LOGIN) }
                continue
            }
            _status.update { it.copy(phase = SyncPhase.SYNCING) }
            try {
                val report = engine.syncOnce()
                failures = 0
                _status.value = SyncStatus(SyncPhase.IDLE, clock.nowMillis(), null, report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotAuthenticatedException) {
                _status.update { it.copy(phase = SyncPhase.NEEDS_LOGIN, lastError = e.message) }
            } catch (e: Exception) {
                failures++
                val message = when (e) {
                    is ApiException -> e.message
                    is AuthException -> e.message
                    else -> "Tidak dapat terhubung ke server. Perubahan tetap tersimpan dan akan dikirim nanti."
                }
                _status.update { it.copy(phase = SyncPhase.OFFLINE, lastError = message) }
                val backoff = min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl min(failures - 1, 10))
                scope.launch {
                    delay(backoff)
                    requestSync()
                }
            }
        }
    }

    private companion object {
        const val LOCAL_CHANGE_DEBOUNCE_MS = 1_500L
        const val PERIODIC_INTERVAL_MS = 5 * 60_000L
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}
