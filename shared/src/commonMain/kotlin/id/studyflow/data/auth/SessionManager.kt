package id.studyflow.data.auth

import id.studyflow.data.AppClock
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.remote.AccessTokenProvider
import id.studyflow.data.remote.AuthApi
import id.studyflow.data.remote.AuthException
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.remote.SignUpResult
import id.studyflow.data.remote.TokenResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/** Data sesi yang disimpan terenkripsi. Access token tidak disimpan; didapat ulang lewat refresh. */
@Serializable
data class StoredSession(val userId: String, val email: String?, val refreshToken: String)

/**
 * Penyimpanan sesi aman per platform (Android Keystore, Windows DPAPI). Implementasi yang tidak punya
 * penyimpanan aman wajib memory-only; tidak boleh diam-diam menyimpan token sebagai plaintext.
 */
interface SecureSessionStore {
    /** false jika sesi hanya disimpan di memori (pengguna harus masuk lagi setelah aplikasi ditutup). */
    val isPersistent: Boolean
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

class MemorySessionStore : SecureSessionStore {
    private var session: StoredSession? = null
    override val isPersistent = false
    override fun load() = session
    override fun save(session: StoredSession) {
        this.session = session
    }
    override fun clear() {
        session = null
    }
}

sealed interface AuthState {
    /** URL/publishable key server belum diisi; hanya mode lokal yang tersedia. */
    data object Unconfigured : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?, val sessionExpired: Boolean = false) : AuthState
}

sealed interface SignUpOutcome {
    data object SignedIn : SignUpOutcome
    data object ConfirmEmail : SignUpOutcome
}

class SessionManager(
    private val config: ServerConfig,
    private val authApi: AuthApi,
    private val store: SecureSessionStore,
    private val databases: DatabaseManager,
    private val clock: AppClock,
) : AccessTokenProvider {
    private val mutex = Mutex()
    private var accessToken: String? = null
    private var accessTokenExpiresAt: Long = 0

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val persistsSession: Boolean get() = store.isPersistent

    init {
        if (!config.isConfigured) {
            _state.value = AuthState.Unconfigured
        } else {
            // Pulihkan sesi tanpa jaringan: data akun tetap bisa dipakai offline.
            store.load()?.let { saved ->
                databases.useAccount(saved.userId)
                _state.value = AuthState.SignedIn(saved.userId, saved.email)
            }
        }
    }

    suspend fun signIn(email: String, password: String) {
        requireConfigured()
        establish(authApi.signIn(email, password), fallbackEmail = email.trim())
    }

    suspend fun signUp(email: String, password: String): SignUpOutcome {
        requireConfigured()
        return when (val result = authApi.signUp(email, password)) {
            is SignUpResult.SignedIn -> {
                establish(result.tokens, fallbackEmail = email.trim())
                SignUpOutcome.SignedIn
            }
            SignUpResult.ConfirmationRequired -> SignUpOutcome.ConfirmEmail
        }
    }

    /**
     * Keluar: mencabut sesi di server bila memungkinkan, menghapus token lokal, dan kembali ke database
     * mode lokal. Database akun tetap di perangkat (termasuk perubahan yang belum terkirim), tetapi
     * tidak terlihat oleh akun lain karena setiap akun memakai file terpisah.
     */
    suspend fun signOut() {
        mutex.withLock {
            accessToken?.let { token -> runCatching { authApi.signOut(token) } }
            accessToken = null
            accessTokenExpiresAt = 0
            store.clear()
            databases.useAccount(null)
            _state.value = if (config.isConfigured) AuthState.SignedOut else AuthState.Unconfigured
        }
    }

    override suspend fun accessToken(forceRefresh: Boolean): String? = mutex.withLock {
        val signedIn = _state.value as? AuthState.SignedIn ?: return@withLock null
        val cached = accessToken
        if (!forceRefresh && cached != null && clock.nowMillis() < accessTokenExpiresAt - REFRESH_MARGIN_MS) {
            return@withLock cached
        }
        val saved = store.load() ?: return@withLock null.also { markExpired(signedIn) }
        try {
            val tokens = authApi.refresh(saved.refreshToken)
            remember(tokens, saved.copy(refreshToken = tokens.refreshToken))
            tokens.accessToken
        } catch (e: AuthException) {
            if (e.isInvalidSession) {
                markExpired(signedIn)
                null
            } else {
                throw e
            }
        }
    }

    private suspend fun establish(tokens: TokenResponse, fallbackEmail: String) = mutex.withLock {
        val userId = tokens.user?.id ?: jwtSubject(tokens.accessToken)
            ?: throw AuthException("Respons autentikasi tidak valid.")
        val session = StoredSession(userId, tokens.user?.email ?: fallbackEmail, tokens.refreshToken)
        remember(tokens, session)
        databases.useAccount(userId)
        _state.value = AuthState.SignedIn(userId, session.email)
    }

    private fun remember(tokens: TokenResponse, session: StoredSession) {
        accessToken = tokens.accessToken
        accessTokenExpiresAt = clock.nowMillis() + tokens.expiresIn * 1000
        // Supabase merotasi refresh token; simpan yang terbaru setiap kali refresh.
        store.save(session)
    }

    private fun markExpired(state: AuthState.SignedIn) {
        accessToken = null
        _state.value = state.copy(sessionExpired = true)
    }

    private fun requireConfigured() {
        if (!config.isConfigured) throw AuthException("Server belum dikonfigurasi di aplikasi ini.")
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 60_000L
    }
}

/** Mengambil klaim `sub` dari JWT tanpa verifikasi (verifikasi dilakukan server). */
internal fun jwtSubject(token: String): String? = runCatching {
    val payload = token.split('.')[1]
    val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
    Json.parseToJsonElement(decoded).jsonObject["sub"]?.jsonPrimitive?.content
}.getOrNull()
