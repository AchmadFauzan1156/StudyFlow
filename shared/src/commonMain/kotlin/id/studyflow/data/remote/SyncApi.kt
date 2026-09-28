package id.studyflow.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** Error dari API StudyFlow dengan format {code, message, request_id}. */
class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/** Tidak ada sesi yang valid; pengguna perlu masuk lagi. */
class NotAuthenticatedException : Exception("Sesi berakhir. Silakan masuk lagi.")

/**
 * Penyedia access token. `forceRefresh = true` dipakai setelah server membalas 401.
 * Mengembalikan null jika tidak ada sesi yang dapat dipakai.
 */
fun interface AccessTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean): String?
}

class SyncApi(
    private val client: HttpClient,
    private val baseUrl: () -> String,
    private val tokens: AccessTokenProvider,
) {
    suspend fun push(mutations: List<MutationDto>): PushResponseDto =
        decode(PushResponseDto.serializer()) { token ->
            client.post("${baseUrl().trimEnd('/')}/v1/sync/push") {
                authorize(token)
                contentType(ContentType.Application.Json)
                setBody(PushRequestDto(mutations))
            }
        }

    suspend fun pull(cursor: String?, limit: Int): PullResponseDto =
        decode(PullResponseDto.serializer()) { token ->
            client.get("${baseUrl().trimEnd('/')}/v1/sync/pull") {
                authorize(token)
                if (cursor != null) parameter("cursor", cursor)
                parameter("limit", limit)
            }
        }

    private fun HttpRequestBuilder.authorize(token: String) = bearerAuth(token)

    private suspend fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        request: suspend (String) -> HttpResponse,
    ): T {
        var response = request(tokens.accessToken(false) ?: throw NotAuthenticatedException())
        if (response.status == HttpStatusCode.Unauthorized) {
            // Token bisa saja dicabut atau kedaluwarsa lebih cepat dari perkiraan: refresh sekali.
            response = request(tokens.accessToken(true) ?: throw NotAuthenticatedException())
            if (response.status == HttpStatusCode.Unauthorized) throw NotAuthenticatedException()
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val body = runCatching { ApiJson.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
            throw ApiException(response.status.value, body?.code ?: "http_${response.status.value}",
                body?.message ?: "Server membalas ${response.status.value}")
        }
        return ApiJson.decodeFromString(serializer, text)
    }
}
