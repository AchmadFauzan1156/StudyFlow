package id.studyflow.data.remote

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class AiInfoDto(
    val available: Boolean,
    val mode: String,
    @SerialName("provider_label") val providerLabel: String? = null,
    val model: String? = null,
    @SerialName("data_notice") val dataNotice: String? = null,
    @SerialName("daily_limit") val dailyLimit: Int,
    @SerialName("used_today") val usedToday: Int,
    @SerialName("max_input_chars") val maxInputChars: Int,
    @SerialName("quiz_min_questions") val quizMinQuestions: Int,
    @SerialName("quiz_max_questions") val quizMaxQuestions: Int,
)

@Serializable
data class JobDto(
    val id: String,
    val type: String,
    val status: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_version") val sourceVersion: Int,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("result_id") val resultId: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
) {
    val isTerminal: Boolean get() = status == "succeeded" || status == "failed"
}

@Serializable
data class SourceInfoDto(
    @SerialName("note_id") val noteId: String,
    val title: String? = null,
    val version: Int,
    @SerialName("current_version") val currentVersion: Int? = null,
    val outdated: Boolean,
    val deleted: Boolean,
)

@Serializable
data class SummaryDto(
    val id: String,
    val content: String,
    val source: SourceInfoDto,
    @SerialName("model_id") val modelId: String,
    @SerialName("prompt_version") val promptVersion: String,
    @SerialName("is_demo") val isDemo: Boolean,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
data class OptionDto(val id: String, val text: String)

@Serializable
data class QuestionDto(
    val id: String,
    val position: Int,
    @SerialName("topic_id") val topicId: String,
    val question: String,
    val options: List<OptionDto>,
)

@Serializable
data class QuizDto(
    val id: String,
    val title: String,
    val source: SourceInfoDto,
    @SerialName("model_id") val modelId: String,
    @SerialName("is_demo") val isDemo: Boolean,
    @SerialName("created_at") val createdAt: Long? = null,
    val questions: List<QuestionDto>,
)

@Serializable
data class SourceRefDto(@SerialName("source_id") val sourceId: String, val title: String? = null)

@Serializable
data class AnswerResultDto(
    @SerialName("question_id") val questionId: String,
    @SerialName("selected_option_id") val selectedOptionId: String? = null,
    @SerialName("correct_option_id") val correctOptionId: String,
    val correct: Boolean,
    val explanation: String,
    val sources: List<SourceRefDto> = emptyList(),
)

@Serializable
data class AttemptDto(
    val id: String,
    @SerialName("quiz_id") val quizId: String,
    val score: Int,
    val total: Int,
    @SerialName("submitted_at") val submittedAt: Long? = null,
    val results: List<AnswerResultDto>,
)

@Serializable
data class AttemptSummaryDto(
    val id: String,
    @SerialName("quiz_id") val quizId: String,
    @SerialName("quiz_title") val quizTitle: String,
    val score: Int,
    val total: Int,
    @SerialName("submitted_at") val submittedAt: Long? = null,
)

@Serializable
data class NoteResultDto(
    val type: String,
    val id: String,
    val title: String? = null,
    @SerialName("source_version") val sourceVersion: Int,
    @SerialName("is_demo") val isDemo: Boolean,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("best_score") val bestScore: Int? = null,
    val total: Int? = null,
)

@Serializable
data class NoteResultsDto(val results: List<NoteResultDto>, @SerialName("pending_jobs") val pendingJobs: List<JobDto>)

@Serializable
data class TopicDto(
    @SerialName("topic_id") val topicId: String,
    val label: String,
    @SerialName("note_id") val noteId: String,
    @SerialName("note_title") val noteTitle: String? = null,
    @SerialName("course_id") val courseId: String? = null,
    val answered: Int,
    val correct: Int,
    val accuracy: Double,
    @SerialName("needs_practice") val needsPractice: Boolean,
)

@Serializable
data class ProgressDto(
    val topics: List<TopicDto>,
    @SerialName("min_answers") val minAnswers: Int,
    @SerialName("accuracy_threshold") val accuracyThreshold: Double,
)

@Serializable
data class FlashcardDto(
    @SerialName("question_id") val questionId: String,
    val question: String,
    val answer: String,
    val explanation: String,
    @SerialName("topic_label") val topicLabel: String,
    @SerialName("note_title") val noteTitle: String? = null,
    val box: Int,
)

@Serializable
data class FlashcardsDto(val cards: List<FlashcardDto>, @SerialName("due_total") val dueTotal: Int)

@Serializable
private data class ReviewRequest(val remembered: Boolean)

@Serializable
private data class GenerateRequest(
    @SerialName("note_id") val noteId: String,
    @SerialName("note_version") val noteVersion: Int,
    @SerialName("question_count") val questionCount: Int? = null,
)

@Serializable
private data class AttemptRequest(val answers: Map<String, String?>)

@Serializable
private data class ReportRequest(val reason: String?)

/** Endpoint AI (fase 3). Semua operasi membutuhkan koneksi; hasil di-cache oleh repository. */
class AiApi(private val api: ApiCaller) {
    suspend fun info(): AiInfoDto = api.call(AiInfoDto.serializer()) { token ->
        get(api.url("/v1/ai/info")) { authorize(token) }
    }

    suspend fun requestSummary(noteId: String, version: Int, idempotencyKey: String): JobDto =
        generate("/v1/summaries", GenerateRequest(noteId, version), idempotencyKey)

    suspend fun requestQuiz(noteId: String, version: Int, questionCount: Int, idempotencyKey: String): JobDto =
        generate("/v1/quizzes", GenerateRequest(noteId, version, questionCount), idempotencyKey)

    private suspend fun generate(path: String, body: GenerateRequest, key: String): JobDto = api.call(JobDto.serializer()) { token ->
        post(api.url(path)) {
            authorize(token)
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun job(id: String): JobDto = api.call(JobDto.serializer()) { token ->
        get(api.url("/v1/jobs/$id")) { authorize(token) }
    }

    suspend fun summary(id: String): SummaryDto = api.call(SummaryDto.serializer()) { token ->
        get(api.url("/v1/summaries/$id")) { authorize(token) }
    }

    suspend fun quiz(id: String): QuizDto = api.call(QuizDto.serializer()) { token ->
        get(api.url("/v1/quizzes/$id")) { authorize(token) }
    }

    suspend fun submitAttempt(quizId: String, answers: Map<String, String?>, idempotencyKey: String): AttemptDto =
        api.call(AttemptDto.serializer()) { token ->
            post(api.url("/v1/quizzes/$quizId/attempts")) {
                authorize(token)
                header("Idempotency-Key", idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(AttemptRequest(answers))
            }
        }

    suspend fun attempt(id: String): AttemptDto = api.call(AttemptDto.serializer()) { token ->
        get(api.url("/v1/attempts/$id")) { authorize(token) }
    }

    suspend fun attempts(limit: Int = 50): List<AttemptSummaryDto> = api.call(ListSerializer(AttemptSummaryDto.serializer())) { token ->
        get(api.url("/v1/attempts")) {
            authorize(token)
            parameter("limit", limit)
        }
    }

    suspend fun noteResults(noteId: String): NoteResultsDto = api.call(NoteResultsDto.serializer()) { token ->
        get(api.url("/v1/notes/$noteId/ai-results")) { authorize(token) }
    }

    suspend fun topics(): ProgressDto = api.call(ProgressDto.serializer()) { token ->
        get(api.url("/v1/progress/topics")) { authorize(token) }
    }

    suspend fun dueFlashcards(): FlashcardsDto = api.call(FlashcardsDto.serializer()) { token ->
        get(api.url("/v1/flashcards/due")) { authorize(token) }
    }

    suspend fun review(questionId: String, remembered: Boolean) {
        api.send { token ->
            post(api.url("/v1/flashcards/$questionId/review")) {
                authorize(token)
                contentType(ContentType.Application.Json)
                setBody(ReviewRequest(remembered))
            }
        }
    }

    suspend fun reportQuestion(questionId: String, reason: String?) {
        api.send { token ->
            post(api.url("/v1/questions/$questionId/report")) {
                authorize(token)
                contentType(ContentType.Application.Json)
                setBody(ReportRequest(reason))
            }
        }
    }
}
