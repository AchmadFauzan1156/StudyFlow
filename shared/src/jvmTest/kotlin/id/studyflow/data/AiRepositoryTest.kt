package id.studyflow.data

import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.NoteReadiness
import id.studyflow.data.remote.MutationResultDto
import id.studyflow.data.remote.PushResponseDto
import id.studyflow.data.remote.ApiJson
import id.studyflow.data.remote.PushRequestDto
import id.studyflow.data.sync.SyncManager
import id.studyflow.ui.study.PendingRequest
import id.studyflow.ui.study.StudyNoteViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SUMMARY_JSON = """{"id":"s1","content":"Rangkuman RSA","source":{"note_id":"n","title":"RSA","version":1,
"current_version":1,"outdated":false,"deleted":false},"model_id":"openai/gpt-oss-120b","prompt_version":"p","is_demo":false,
"created_at":1790000000000}"""

private const val INFO_JSON = """{"available":true,"mode":"live","provider_label":"Groq","model":"openai/gpt-oss-120b",
"data_notice":"Dikirim ke Groq.","daily_limit":10,"used_today":2,"max_input_chars":12000,"quiz_min_questions":3,
"quiz_max_questions":10}"""

@OptIn(ExperimentalCoroutinesApi::class)
class AiRepositoryTest {
    private val devices = mutableListOf<Device>()
    private var online = true
    private val requests = mutableListOf<HttpRequestData>()

    @AfterTest
    fun close() {
        devices.forEach { it.close() }
    }

    private fun device(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): Device =
        Device(
            MockEngine { request ->
                requests += request
                if (!online) throw IOException("offline")
                val (status, body) = handler(request)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ).also {
            it.databases.useAccount("alice")
            devices += it
        }

    private fun defaultHandler(request: HttpRequestData): Pair<HttpStatusCode, String> {
        val path = request.url.encodedPath
        return when {
            path.endsWith("/sync/push") -> {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val mutations = ApiJson.decodeFromString(PushRequestDto.serializer(), body).mutations
                HttpStatusCode.OK to ApiJson.encodeToString(
                    PushResponseDto.serializer(),
                    PushResponseDto(mutations.map { MutationResultDto(it.mutationId, it.entityType, it.entityId, "applied", it.baseVersion + 1) }),
                )
            }
            path.endsWith("/sync/pull") -> HttpStatusCode.OK to """{"changes":[],"next_cursor":"c","has_more":false}"""
            path.endsWith("/ai/info") -> HttpStatusCode.OK to INFO_JSON
            path.endsWith("/summaries/s1") -> HttpStatusCode.OK to SUMMARY_JSON
            path.endsWith("/summaries/gone") -> HttpStatusCode.NotFound to """{"code":"not_found","message":"Rangkuman tidak ditemukan"}"""
            path.endsWith("/summaries") -> HttpStatusCode.TooManyRequests to
                """{"code":"quota_exceeded","message":"Kuota AI harianmu (10 permintaan) sudah habis. Coba lagi besok."}"""
            path.contains("/ai-results") -> HttpStatusCode.OK to """{"results":[],"pending_jobs":[]}"""
            else -> HttpStatusCode.NotFound to """{"code":"not_found","message":"x"}"""
        }
    }

    @Test
    fun noteMustBeSyncedBeforeAi() = runBlocking<Unit> {
        val d = device(::defaultHandler)
        val courseId = d.courses.create("Kriptografi", null)
        val noteId = d.notes.create(courseId, "RSA", "isi")
        assertEquals(NoteReadiness.NeedsSync, d.ai.noteReadiness(noteId))
        d.sync.syncOnce()
        assertEquals(NoteReadiness.Ready(1), d.ai.noteReadiness(noteId))
        assertEquals(NoteReadiness.NotFound, d.ai.noteReadiness("tidak-ada"))
    }

    @Test
    fun resultsAreReadableOfflineFromCache() = runBlocking<Unit> {
        val d = device(::defaultHandler)
        val online1 = d.ai.summary("s1")
        assertFalse(online1.fromCache)
        online = false
        val offline = d.ai.summary("s1")
        assertTrue(offline.fromCache)
        assertEquals("Rangkuman RSA", offline.value.content)

        val e = assertFailsWith<AiException> { d.ai.summary("belum-pernah-dibuka") }
        assertEquals("offline", e.code)
    }

    @Test
    fun cacheIsNotUsedWhenServerSaysNotFound() = runBlocking<Unit> {
        val d = device(::defaultHandler)
        d.db.cacheDao().put(id.studyflow.data.local.RemoteCacheEntity("summary:gone", SUMMARY_JSON, 0))
        val e = assertFailsWith<AiException> { d.ai.summary("gone") }
        assertEquals("not_found", e.code)
    }

    @Test
    fun cacheAndConsentAreScopedPerAccount() = runBlocking<Unit> {
        val d = device(::defaultHandler)
        d.ai.summary("s1")
        d.ai.giveConsent("Groq")
        assertTrue(d.ai.hasConsent("Groq"))

        d.databases.useAccount("bob")
        online = false
        assertFalse(d.ai.hasConsent("Groq"))
        assertFailsWith<AiException> { d.ai.summary("s1") }
    }

    @Test
    fun quotaErrorKeepsServerMessage() = runBlocking<Unit> {
        val d = device(::defaultHandler)
        val e = assertFailsWith<AiException> { d.ai.requestSummary("n", 1, "key-00000001") }
        assertEquals("quota_exceeded", e.code)
        assertTrue(e.message!!.contains("Kuota AI harianmu"))
        assertEquals("key-00000001", requests.last().headers["Idempotency-Key"])
    }

    @Test
    fun retryAfterNetworkFailureReusesIdempotencyKey() = runBlocking<Unit> {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val d = device { request ->
                if (request.url.encodedPath.endsWith("/summaries")) {
                    HttpStatusCode.Accepted to
                        """{"id":"j1","type":"summary","status":"queued","source_id":"n","source_version":1}"""
                } else {
                    defaultHandler(request)
                }
            }
            val courseId = d.courses.create("MK", null)
            val noteId = d.notes.create(courseId, "RSA", "isi")
            d.sync.syncOnce()
            d.ai.giveConsent("Groq")
            val session = d.session()
            val manager = SyncManager(d.sync, session, d.databases, d.clock, scope)
            val vm = StudyNoteViewModel(noteId, d.ai, d.notes, manager, SequentialIds("key"))
            waitUntil { !vm.state.value.loading }
            assertIs<NoteReadiness.Ready>(vm.state.value.readiness)

            online = false
            vm.request(PendingRequest.Summary)
            waitUntil { vm.state.value.message != null && !vm.state.value.busy }
            online = true
            vm.request(PendingRequest.Summary)
            waitUntil { vm.state.value.jobs.isNotEmpty() }

            val keys = requests.filter { it.url.encodedPath.endsWith("/summaries") }.map { it.headers["Idempotency-Key"] }
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1], "retry setelah gagal jaringan memakai key yang sama")
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) kotlinx.coroutines.delay(20)
    }
}

