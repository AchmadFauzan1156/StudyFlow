package id.studyflow.data.ai

import id.studyflow.data.AppClock
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.RemoteCacheEntity
import id.studyflow.data.local.SettingEntity
import id.studyflow.data.remote.AiApi
import id.studyflow.data.remote.AiInfoDto
import id.studyflow.data.remote.ApiException
import id.studyflow.data.remote.ApiJson
import id.studyflow.data.remote.AttemptDto
import id.studyflow.data.remote.AttemptSummaryDto
import id.studyflow.data.remote.ChunkDto
import id.studyflow.data.remote.DocumentDto
import id.studyflow.data.remote.FlashcardsDto
import id.studyflow.data.remote.JobDto
import id.studyflow.data.remote.MaterialsApi
import id.studyflow.data.remote.MessageDto
import id.studyflow.data.remote.NotAuthenticatedException
import id.studyflow.data.remote.NoteResultsDto
import id.studyflow.data.remote.PageDto
import id.studyflow.data.remote.ProgressDto
import id.studyflow.data.remote.QuizDto
import id.studyflow.data.remote.SummaryDto
import id.studyflow.data.remote.ThreadDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Kesiapan catatan untuk diproses AI: server hanya menerima versi yang sudah tersinkron. */
sealed interface NoteReadiness {
    data class Ready(val version: Int) : NoteReadiness
    data object NeedsSync : NoteReadiness
    data object NotFound : NoteReadiness
}

/** Hasil baca: data plus penanda apakah berasal dari cache (offline). */
data class Loaded<T>(val value: T, val fromCache: Boolean)

/** Pesan kesalahan yang aman ditampilkan ke pengguna. */
class AiException(message: String, val code: String? = null) : Exception(message)

class AiRepository(
    private val databases: DatabaseManager,
    private val api: AiApi,
    private val materials: MaterialsApi,
    private val clock: AppClock,
) {
    /** Server hanya menerima dokumen untuk mata kuliah yang sudah tersinkron. */
    suspend fun courseSynced(courseId: String): Boolean {
        val course = databases.db.courseDao().getById(courseId) ?: return false
        return course.deletedAt == null && !course.pendingSync && course.version > 0
    }

    // ---------- Dokumen ----------

    suspend fun documents(courseId: String): Loaded<List<DocumentDto>> =
        cached("documents:$courseId", ListSerializer(DocumentDto.serializer())) { materials.documents(courseId) }

    suspend fun document(id: String): Loaded<DocumentDto> = cached("document:$id", DocumentDto.serializer()) { materials.document(id) }

    suspend fun pages(documentId: String, start: Int, count: Int): Loaded<List<PageDto>> =
        cached("pages:$documentId:$start:$count", ListSerializer(PageDto.serializer())) {
            materials.pages(documentId, start, count)
        }

    suspend fun upload(courseId: String, title: String, fileName: String, bytes: ByteArray, idempotencyKey: String): DocumentDto {
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw AiException("Berkas melebihi ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB", "file_too_large")
        }
        return guard { materials.upload(courseId, title, fileName, bytes, idempotencyKey) }
    }

    suspend fun download(documentId: String): ByteArray = guard { materials.download(documentId) }

    suspend fun deleteDocument(documentId: String) = guard { materials.delete(documentId) }

    suspend fun chunk(chunkId: String): Loaded<ChunkDto> = cached("chunk:$chunkId", ChunkDto.serializer()) { materials.chunk(chunkId) }

    // ---------- Tanya materi ----------

    suspend fun courseThread(courseId: String): Loaded<ThreadDto> =
        cached("thread:$courseId", ThreadDto.serializer()) { materials.courseThread(courseId) }

    suspend fun ask(threadId: String, question: String, idempotencyKey: String): MessageDto =
        guard { materials.ask(threadId, question, idempotencyKey) }

    suspend fun messages(threadId: String): Loaded<List<MessageDto>> =
        cached("messages:$threadId", ListSerializer(MessageDto.serializer())) { materials.messages(threadId) }

    suspend fun noteReadiness(noteId: String): NoteReadiness {
        val note = databases.db.noteDao().getById(noteId) ?: return NoteReadiness.NotFound
        if (note.deletedAt != null) return NoteReadiness.NotFound
        return if (note.pendingSync || note.version == 0) NoteReadiness.NeedsSync else NoteReadiness.Ready(note.version)
    }

    suspend fun info(): Loaded<AiInfoDto> = cached("ai:info", AiInfoDto.serializer()) { api.info() }

    suspend fun requestSummary(noteId: String, version: Int, idempotencyKey: String): JobDto =
        guard { api.requestSummary(noteId, version, idempotencyKey) }

    suspend fun requestQuiz(noteId: String, version: Int, count: Int, idempotencyKey: String): JobDto =
        guard { api.requestQuiz(noteId, version, count, idempotencyKey) }

    suspend fun job(id: String): JobDto = guard { api.job(id) }

    suspend fun summary(id: String): Loaded<SummaryDto> = cached("summary:$id", SummaryDto.serializer()) { api.summary(id) }

    suspend fun quiz(id: String): Loaded<QuizDto> = cached("quiz:$id", QuizDto.serializer()) { api.quiz(id) }

    suspend fun submitAttempt(quizId: String, answers: Map<String, String?>, idempotencyKey: String): AttemptDto {
        val attempt = guard { api.submitAttempt(quizId, answers, idempotencyKey) }
        store("attempt:${attempt.id}", AttemptDto.serializer(), attempt)
        return attempt
    }

    suspend fun attempt(id: String): Loaded<AttemptDto> = cached("attempt:$id", AttemptDto.serializer()) { api.attempt(id) }

    suspend fun attempts(): Loaded<List<AttemptSummaryDto>> =
        cached("attempts", ListSerializer(AttemptSummaryDto.serializer())) { api.attempts() }

    suspend fun noteResults(noteId: String): Loaded<NoteResultsDto> =
        cached("note-results:$noteId", NoteResultsDto.serializer()) { api.noteResults(noteId) }

    // ---------- Progres & flashcard ----------

    suspend fun topics(): Loaded<ProgressDto> = cached("progress:topics", ProgressDto.serializer()) { api.topics() }

    /** Kartu latihan hanya diambil online (jadwalnya ditentukan server). */
    suspend fun dueFlashcards(): FlashcardsDto = guard { api.dueFlashcards() }

    suspend fun review(questionId: String, remembered: Boolean) = guard { api.review(questionId, remembered) }

    suspend fun reportQuestion(questionId: String, reason: String?) = guard { api.reportQuestion(questionId, reason) }

    /** Persetujuan pengiriman materi ke provider, disimpan per akun dan per provider. */
    suspend fun hasConsent(providerLabel: String): Boolean = databases.db.settingsDao().get(consentKey(providerLabel)) != null

    suspend fun giveConsent(providerLabel: String) =
        databases.db.settingsDao().put(SettingEntity(consentKey(providerLabel), clock.nowMillis().toString()))

    private fun consentKey(label: String) = "ai_consent:$label"

    companion object {
        /** Sama dengan batas default server (STUDYFLOW_DOCUMENT_MAX_BYTES); server tetap yang menentukan. */
        const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
    }

    private suspend fun <T> cached(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Loaded<T> = try {
        val value = guard { fetch() }
        store(key, serializer, value)
        Loaded(value, fromCache = false)
    } catch (e: AiException) {
        val cachedEntry = databases.db.cacheDao().get(key) ?: throw e
        // Server menolak akses (mis. data dihapus): jangan tampilkan salinan lama.
        if (e.code == "not_found") throw e
        Loaded(ApiJson.decodeFromString(serializer, cachedEntry.json), fromCache = true)
    }

    private suspend fun <T> store(key: String, serializer: KSerializer<T>, value: T) {
        databases.db.cacheDao().put(RemoteCacheEntity(key, ApiJson.encodeToString(serializer, value), clock.nowMillis()))
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw AiException(e.message ?: "Permintaan gagal", e.code)
    } catch (e: NotAuthenticatedException) {
        throw AiException("Sesi berakhir. Masuk lagi di Pengaturan.", "unauthorized")
    } catch (e: Exception) {
        throw AiException("Butuh koneksi internet untuk fitur ini.", "offline")
    }
}
