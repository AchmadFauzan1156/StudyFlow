package id.studyflow.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Konfigurasi publik server (bukan rahasia). String kosong berarti belum dikonfigurasi. */
data class ServerConfig(val supabaseUrl: String, val supabasePublishableKey: String, val apiBaseUrl: String) {
    val isConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && supabasePublishableKey.isNotBlank() && apiBaseUrl.isNotBlank()
}

// ---------- Supabase Auth ----------

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: AuthUser? = null,
)

@Serializable
data class AuthUser(val id: String, val email: String? = null)

@Serializable
internal data class PasswordCredentials(val email: String, val password: String)

@Serializable
internal data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class AuthErrorBody(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val msg: String? = null,
    val message: String? = null,
)

// ---------- StudyFlow API ----------

@Serializable
data class ApiErrorBody(val code: String, val message: String, @SerialName("request_id") val requestId: String? = null)

@Serializable
data class MutationDto(
    @SerialName("mutation_id") val mutationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val operation: String,
    @SerialName("base_version") val baseVersion: Int,
    val payload: JsonObject,
)

@Serializable
data class PushRequestDto(val mutations: List<MutationDto>)

@Serializable
data class MutationResultDto(
    @SerialName("mutation_id") val mutationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val status: String,
    val version: Int? = null,
    @SerialName("server_entity") val serverEntity: ServerEntity? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class PushResponseDto(val results: List<MutationResultDto>)

/** Snapshot entity dari server. Field yang tidak relevan untuk tipe tertentu bernilai null. */
@Serializable
data class ServerEntity(
    val id: String,
    val version: Int,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val name: String? = null,
    val semester: String? = null,
    @SerialName("course_id") val courseId: String? = null,
    val title: String? = null,
    val content: String? = null,
    val description: String? = null,
    @SerialName("due_at") val dueAt: Long? = null,
    @SerialName("time_zone_id") val timeZoneId: String? = null,
    val status: String? = null,
)

@Serializable
data class ChangeDto(@SerialName("entity_type") val entityType: String, val entity: ServerEntity)

@Serializable
data class PullResponseDto(
    val changes: List<ChangeDto>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("has_more") val hasMore: Boolean,
)
