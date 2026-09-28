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
import id.studyflow.data.remote.JobDto
import id.studyflow.data.remote.NotAuthenticatedException
import id.studyflow.data.remote.NoteResultsDto
import id.studyflow.data.remote.QuizDto
import id.studyflow.data.remote.SummaryDto
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
    private val clock: AppClock,
) {
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

    suspend fun reportQuestion(questionId: String, reason: String?) = guard { api.reportQuestion(questionId, reason) }

    /** Persetujuan pengiriman materi ke provider, disimpan per akun dan per provider. */
    suspend fun hasConsent(providerLabel: String): Boolean =
        databases.db.settingsDao().get(consentKey(providerLabel)) != null

    suspend fun giveConsent(providerLabel: String) =
        databases.db.settingsDao().put(SettingEntity(consentKey(providerLabel), clock.nowMillis().toString()))

    private fun consentKey(label: String) = "ai_consent:$label"

    private suspend fun <T> cached(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Loaded<T> =
        try {
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
