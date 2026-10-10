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
import kotlinx.serialization.KSerializer

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

/** Pemanggil API StudyFlow: menyisipkan token, refresh sekali saat 401, dan memetakan format error. */
class ApiCaller(val client: HttpClient, private val baseUrl: () -> String, private val tokens: AccessTokenProvider) {
    fun url(path: String): String = "${baseUrl().trimEnd('/')}$path"

    suspend fun <T> call(serializer: KSerializer<T>, request: suspend HttpClient.(token: String) -> HttpResponse): T {
        val text = send(request).bodyAsText()
        return ApiJson.decodeFromString(serializer, text)
    }

    suspend fun send(request: suspend HttpClient.(token: String) -> HttpResponse): HttpResponse {
        var response = client.request(tokens.accessToken(false) ?: throw NotAuthenticatedException())
        if (response.status == HttpStatusCode.Unauthorized) {
            // Token bisa saja dicabut atau kedaluwarsa lebih cepat dari perkiraan: refresh sekali.
            response = client.request(tokens.accessToken(true) ?: throw NotAuthenticatedException())
            if (response.status == HttpStatusCode.Unauthorized) throw NotAuthenticatedException()
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val body = runCatching { ApiJson.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
            throw ApiException(
                response.status.value,
                body?.code ?: "http_${response.status.value}",
                body?.message ?: "Server membalas ${response.status.value}",
            )
        }
        return response
    }
}

fun HttpRequestBuilder.authorize(token: String) = bearerAuth(token)

class SyncApi(private val api: ApiCaller) {
    suspend fun push(mutations: List<MutationDto>): PushResponseDto = api.call(PushResponseDto.serializer()) { token ->
        post(api.url("/v1/sync/push")) {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(PushRequestDto(mutations))
        }
    }

    suspend fun pull(cursor: String?, limit: Int): PullResponseDto = api.call(PullResponseDto.serializer()) { token ->
        get(api.url("/v1/sync/pull")) {
            authorize(token)
            if (cursor != null) parameter("cursor", cursor)
            parameter("limit", limit)
        }
    }
}
