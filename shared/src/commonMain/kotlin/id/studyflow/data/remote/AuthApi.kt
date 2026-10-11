package id.studyflow.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject

/** Kegagalan autentikasi dengan pesan yang siap ditampilkan ke pengguna. */
class AuthException(message: String, val isInvalidSession: Boolean = false) : Exception(message)

sealed interface SignUpResult {
    data class SignedIn(val tokens: TokenResponse) : SignUpResult

    /** Proyek Supabase mewajibkan konfirmasi email sebelum bisa masuk. */
    data object ConfirmationRequired : SignUpResult
}

/** Klien REST Supabase Auth (`/auth/v1`). Hanya membawa publishable key, tidak ada rahasia. */
class AuthApi(private val client: HttpClient, private val config: ServerConfig) {
    private val base get() = "${config.supabaseUrl.trimEnd('/')}/auth/v1"

    suspend fun signIn(email: String, password: String): TokenResponse =
        parseTokens(post("$base/token?grant_type=password", PasswordCredentials(email.trim(), password)))

    suspend fun signUp(email: String, password: String): SignUpResult {
        val response = post("$base/signup", PasswordCredentials(email.trim(), password))
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw authError(response.status.value, text)
        val json = ApiJson.decodeFromString(JsonObject.serializer(), text)
        return if ("access_token" in json) {
            SignUpResult.SignedIn(ApiJson.decodeFromJsonElement(TokenResponse.serializer(), json))
        } else {
            SignUpResult.ConfirmationRequired
        }
    }

    suspend fun refresh(refreshToken: String): TokenResponse =
        parseTokens(post("$base/token?grant_type=refresh_token", RefreshRequest(refreshToken)))

    /** Mencabut refresh token di server. Kegagalan jaringan diabaikan oleh pemanggil. */
    suspend fun signOut(accessToken: String) {
        client.post("$base/logout") {
            header("apikey", config.supabasePublishableKey)
            bearerAuth(accessToken)
        }
    }

    private suspend inline fun <reified T : Any> post(url: String, body: T): HttpResponse = try {
        client.post(url) {
            header("apikey", config.supabasePublishableKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    } catch (e: IOException) {
        throw AuthException("Tidak dapat terhubung ke server. Periksa koneksi internet.")
    }

    private suspend fun parseTokens(response: HttpResponse): TokenResponse {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw authError(response.status.value, text)
        return ApiJson.decodeFromString(TokenResponse.serializer(), text)
    }

    private fun authError(status: Int, body: String): AuthException {
        val parsed = runCatching { ApiJson.decodeFromString(AuthErrorBody.serializer(), body) }.getOrNull()
        val code = parsed?.errorCode ?: parsed?.error
        val message = when {
            status == 429 -> "Terlalu banyak percobaan. Coba lagi beberapa saat lagi."
            code == "email_not_confirmed" -> "Email belum dikonfirmasi. Buka tautan konfirmasi di email kamu."
            code == "invalid_credentials" ||
                code == "invalid_grant" &&
                status == 400 &&
                parsed?.errorDescription?.contains("credentials", ignoreCase = true) == true ->
                "Email atau kata sandi salah."
            code == "user_already_exists" -> "Email sudah terdaftar. Silakan masuk."
            code == "weak_password" -> "Kata sandi terlalu lemah."
            code == "refresh_token_not_found" || code == "refresh_token_already_used" || code == "invalid_grant" ->
                "Sesi berakhir. Silakan masuk lagi."
            status >= 500 -> "Server autentikasi sedang bermasalah. Coba lagi nanti."
            else -> parsed?.msg ?: parsed?.errorDescription ?: parsed?.message ?: "Autentikasi gagal ($status)."
        }
        val invalidSession = status in 400..401 &&
            code in setOf(
                "refresh_token_not_found",
                "refresh_token_already_used",
                "invalid_grant",
                "session_not_found",
            )
        return AuthException(message, isInvalidSession = invalidSession)
    }
}
