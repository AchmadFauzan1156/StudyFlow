package id.studyflow.data.remote

import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class DocumentDto(
    val id: String,
    @SerialName("course_id") val courseId: String,
    val title: String,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("page_count") val pageCount: Int,
    @SerialName("size_bytes") val sizeBytes: Int,
    val warnings: List<String> = emptyList(),
    @SerialName("version_id") val versionId: String,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
) {
    val isProcessing: Boolean get() = status == "queued" || status == "processing"
}

@Serializable
data class PageDto(@SerialName("page_number") val pageNumber: Int, val text: String)

@Serializable
data class ChunkDto(
    @SerialName("chunk_id") val chunkId: String,
    @SerialName("document_id") val documentId: String,
    @SerialName("document_title") val documentTitle: String,
    @SerialName("page_start") val pageStart: Int,
    @SerialName("page_end") val pageEnd: Int,
    val text: String,
)

@Serializable
data class ThreadDto(
    val id: String,
    @SerialName("course_id") val courseId: String,
    @SerialName("document_id") val documentId: String? = null,
)

@Serializable
data class SourceDto(
    @SerialName("chunk_id") val chunkId: String,
    @SerialName("document_id") val documentId: String,
    @SerialName("document_title") val documentTitle: String,
    @SerialName("page_start") val pageStart: Int,
    @SerialName("page_end") val pageEnd: Int,
    val excerpt: String = "",
)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val content: String,
    val sources: List<SourceDto> = emptyList(),
    val answerable: Boolean? = null,
    @SerialName("is_demo") val isDemo: Boolean = false,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("job_status") val jobStatus: String? = null,
    @SerialName("job_error") val jobError: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
private data class ThreadRequest(@SerialName("course_id") val courseId: String)

@Serializable
private data class AskRequest(val content: String)

/** Dokumen PDF dan tanya materi (fase 4). */
class MaterialsApi(private val api: ApiCaller) {
    suspend fun documents(courseId: String): List<DocumentDto> = api.call(ListSerializer(DocumentDto.serializer())) { token ->
        get(api.url("/v1/documents")) {
            authorize(token)
            parameter("course_id", courseId)
        }
    }

    suspend fun document(id: String): DocumentDto = api.call(DocumentDto.serializer()) { token ->
        get(api.url("/v1/documents/$id")) { authorize(token) }
    }

    suspend fun upload(courseId: String, title: String, fileName: String, bytes: ByteArray, idempotencyKey: String): DocumentDto =
        api.call(DocumentDto.serializer()) { token ->
            submitFormWithBinaryData(
                url = api.url("/v1/documents"),
                formData = formData {
                    append("course_id", courseId)
                    append("title", title)
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                        },
                    )
                },
            ) {
                authorize(token)
                header("Idempotency-Key", idempotencyKey)
            }
        }

    suspend fun pages(documentId: String, start: Int, count: Int): List<PageDto> = api.call(ListSerializer(PageDto.serializer())) { token ->
        get(api.url("/v1/documents/$documentId/pages")) {
            authorize(token)
            parameter("start", start)
            parameter("count", count)
        }
    }

    suspend fun download(documentId: String): ByteArray =
        api.send { token -> get(api.url("/v1/documents/$documentId/download")) { authorize(token) } }.readRawBytes()

    suspend fun delete(documentId: String) {
        api.send { token -> delete(api.url("/v1/documents/$documentId")) { authorize(token) } }
    }

    suspend fun chunk(chunkId: String): ChunkDto = api.call(ChunkDto.serializer()) { token ->
        get(api.url("/v1/chunks/$chunkId")) { authorize(token) }
    }

    suspend fun courseThread(courseId: String): ThreadDto = api.call(ThreadDto.serializer()) { token ->
        post(api.url("/v1/chat/threads")) {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(ThreadRequest(courseId))
        }
    }

    suspend fun ask(threadId: String, question: String, idempotencyKey: String): MessageDto = api.call(MessageDto.serializer()) { token ->
        post(api.url("/v1/chat/threads/$threadId/messages")) {
            authorize(token)
            header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(AskRequest(question))
        }
    }

    suspend fun messages(threadId: String): List<MessageDto> = api.call(ListSerializer(MessageDto.serializer())) { token ->
        get(api.url("/v1/chat/threads/$threadId/messages")) { authorize(token) }
    }
}
