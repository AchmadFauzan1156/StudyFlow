package id.studyflow.data

import id.studyflow.data.local.SettingEntity
import id.studyflow.data.remote.AccessTokenProvider
import id.studyflow.data.remote.ApiJson
import id.studyflow.data.remote.ChangeDto
import id.studyflow.data.remote.MutationDto
import id.studyflow.data.remote.MutationResultDto
import id.studyflow.data.remote.PullResponseDto
import id.studyflow.data.remote.PushRequestDto
import id.studyflow.data.remote.PushResponseDto
import id.studyflow.data.remote.ServerEntity
import id.studyflow.data.repository.EntityType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, jsonHeaders)

private fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

private fun HttpRequestData.pushedMutations(): List<MutationDto> =
    ApiJson.decodeFromString(PushRequestDto.serializer(), bodyText()).mutations

private fun pushResponse(vararg results: MutationResultDto) =
    ApiJson.encodeToString(PushResponseDto.serializer(), PushResponseDto(results.toList()))

private fun pullResponse(changes: List<ChangeDto> = emptyList(), cursor: String = "c1") =
    ApiJson.encodeToString(PullResponseDto.serializer(), PullResponseDto(changes, cursor, hasMore = false))

private fun applied(m: MutationDto) = MutationResultDto(
    m.mutationId, m.entityType, m.entityId, "applied", version = m.baseVersion + 1,
)

class SyncEngineTest {
    private val devices = mutableListOf<Device>()

    @AfterTest
    fun close() = devices.forEach { it.close() }

    private fun device(tokens: AccessTokenProvider = AccessTokenProvider { "token" }, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Device =
        Device(MockEngine(handler), tokens).also {
            it.databases.useAccount("user-1")
            devices += it
        }

    @Test
    fun retryAfterNetworkFailureResendsSameMutation() = runBlocking<Unit> {
        val sentIds = mutableListOf<String>()
        var failNextPush = true
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                val mutations = request.pushedMutations()
                sentIds += mutations.map { it.mutationId }
                if (failNextPush) {
                    failNextPush = false
                    throw IOException("koneksi terputus")
                }
                json(pushResponse(*mutations.map(::applied).toTypedArray()))
            } else {
                json(pullResponse())
            }
        }
        val courseId = d.courses.create("Kriptografi", null)

        assertFailsWith<IOException> { d.sync.syncOnce() }
        assertEquals(1, d.db.outboxDao().getAll().size, "outbox tetap ada setelah gagal")

        val report = d.sync.syncOnce()
        assertEquals(1, report.pushed)
        assertEquals(2, sentIds.size)
        assertEquals(sentIds[0], sentIds[1], "retry wajib memakai mutation_id yang sama")
        assertTrue(d.db.outboxDao().getAll().isEmpty())
        val course = d.db.courseDao().getById(courseId)!!
        assertEquals(1, course.version)
        assertEquals(false, course.pendingSync)
    }

    @Test
    fun appliedMutationRebasesFollowingOnes() = runBlocking<Unit> {
        val pushed = mutableListOf<MutationDto>()
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                val mutations = request.pushedMutations()
                pushed += mutations
                json(pushResponse(*mutations.map(::applied).toTypedArray()))
            } else {
                json(pullResponse())
            }
        }
        val courseId = d.courses.create("Basis Data", null)
        d.courses.update(courseId, "Basis Data Lanjut", null)

        d.sync.syncOnce()

        assertEquals(listOf("create" to 0, "update" to 1), pushed.map { it.operation to it.baseVersion })
        assertEquals(2, d.db.courseDao().getById(courseId)!!.version)
    }

    @Test
    fun conflictKeepsLocalContentUntilUserChooses() = runBlocking<Unit> {
        var serverName = "Nama dari server"
        var serverVersion = 2
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                val results = request.pushedMutations().map { m ->
                    MutationResultDto(
                        m.mutationId, m.entityType, m.entityId, "conflict", serverVersion,
                        ServerEntity(id = m.entityId, version = serverVersion, name = serverName),
                    )
                }
                json(pushResponse(*results.toTypedArray()))
            } else {
                json(pullResponse())
            }
        }
        val courseId = d.courses.create("Nama lokal", null)
        val report = d.sync.syncOnce()

        assertEquals(1, report.conflicts)
        assertEquals("Nama lokal", d.db.courseDao().getById(courseId)!!.name)
        assertTrue(d.db.outboxDao().getAll().isEmpty())
        val item = d.conflicts.observeConflicts().first().single()
        assertEquals("Nama lokal", item.local!!.title)
        assertEquals("Nama dari server", item.server.title)

        // Pilih versi saya: dikirim ulang sebagai UPDATE di atas versi server.
        d.conflicts.keepLocal(EntityType.COURSE, courseId)
        val outbox = d.db.outboxDao().getAll().single()
        assertEquals("UPDATE" to 2, outbox.operation to outbox.baseVersion)
        assertTrue(d.conflicts.observeConflicts().first().isEmpty())
    }

    @Test
    fun keepServerOverwritesLocalAndDropsPendingEdits() = runBlocking<Unit> {
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                val results = request.pushedMutations().map { m ->
                    MutationResultDto(m.mutationId, m.entityType, m.entityId, "conflict", 5,
                        ServerEntity(id = m.entityId, version = 5, name = "Server"))
                }
                json(pushResponse(*results.toTypedArray()))
            } else {
                json(pullResponse())
            }
        }
        val courseId = d.courses.create("Lokal", null)
        d.sync.syncOnce()
        // Edit tambahan saat konflik belum diselesaikan ditahan, tidak dikirim.
        d.courses.update(courseId, "Lokal 2", null)
        d.sync.syncOnce()
        assertEquals(1, d.db.outboxDao().getAll().size)

        d.conflicts.keepServer(EntityType.COURSE, courseId)

        val course = d.db.courseDao().getById(courseId)!!
        assertEquals("Server" to 5, course.name to course.version)
        assertTrue(d.db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun pullAppliesParentsBeforeChildrenAndSavesCursor() = runBlocking<Unit> {
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                json(pushResponse())
            } else {
                // Catatan datang lebih dulu daripada induknya (course diubah belakangan di server).
                json(
                    pullResponse(
                        listOf(
                            ChangeDto("note", ServerEntity(id = "n1", version = 1, courseId = "c1", title = "Catatan", content = "isi")),
                            ChangeDto("course", ServerEntity(id = "c1", version = 2, name = "Dari server")),
                        ),
                        cursor = "cursor-2",
                    ),
                )
            }
        }
        val report = d.sync.syncOnce()

        assertEquals(2, report.pulled)
        assertEquals("Dari server", d.db.courseDao().getById("c1")!!.name)
        assertEquals("Catatan", d.db.noteDao().getById("n1")!!.title)
        assertEquals(false, d.db.noteDao().getById("n1")!!.pendingSync)
        assertEquals("cursor-2", d.db.settingsDao().get("sync_cursor"))
    }

    @Test
    fun pullSkipsEntityWithPendingOutbox() = runBlocking<Unit> {
        lateinit var courseId: String
        var pushCalls = 0
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                pushCalls++
                // Respons kosong: mutasi belum diproses, tetap di outbox.
                json(pushResponse())
            } else {
                json(pullResponse(listOf(ChangeDto("course", ServerEntity(id = courseId, version = 7, name = "Server")))))
            }
        }
        courseId = d.courses.create("Lokal", null)
        d.sync.syncOnce()
        assertEquals(1, pushCalls)
        assertEquals("Lokal", d.db.courseDao().getById(courseId)!!.name)
    }

    @Test
    fun invalidCursorTriggersFullResync() = runBlocking<Unit> {
        val cursors = mutableListOf<String?>()
        val d = device { request ->
            val cursor = request.url.parameters["cursor"]
            cursors += cursor
            if (cursor == "stale") {
                json("""{"code":"cursor_invalid","message":"x","request_id":"r"}""", HttpStatusCode.BadRequest)
            } else {
                json(pullResponse(listOf(ChangeDto("course", ServerEntity(id = "c9", version = 1, name = "Ulang"))), "fresh"))
            }
        }
        d.db.settingsDao().put(SettingEntity("sync_cursor", "stale"))

        val report = d.sync.syncOnce()

        assertEquals(listOf("stale", null), cursors)
        assertEquals(1, report.pulled)
        assertEquals("fresh", d.db.settingsDao().get("sync_cursor"))
    }

    @Test
    fun unauthorizedResponseRefreshesTokenOnce() = runBlocking<Unit> {
        val forced = mutableListOf<Boolean>()
        var calls = 0
        val tokens = AccessTokenProvider { force -> forced += force; if (force) "baru" else "lama" }
        val d = device(tokens) { request ->
            calls++
            if (request.headers[HttpHeaders.Authorization] == "Bearer lama") {
                json("""{"code":"unauthorized","message":"x"}""", HttpStatusCode.Unauthorized)
            } else {
                json(pullResponse())
            }
        }
        d.sync.syncOnce()
        assertEquals(listOf(false, true), forced)
        assertEquals(2, calls)
    }

    @Test
    fun rejectedMutationIsDroppedNotRetriedForever() = runBlocking<Unit> {
        var pushes = 0
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                pushes++
                val results = request.pushedMutations().map {
                    MutationResultDto(it.mutationId, it.entityType, it.entityId, "rejected", errorCode = "parent_not_found")
                }
                json(pushResponse(*results.toTypedArray()))
            } else {
                json(pullResponse())
            }
        }
        d.courses.create("X", null)
        val report = d.sync.syncOnce()
        d.sync.syncOnce()
        assertEquals(1, report.rejected)
        assertEquals(1, pushes)
        assertTrue(d.db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun localModeNeverTalksToServer() = runBlocking<Unit> {
        var calls = 0
        val d = device { calls++; json(pullResponse()) }
        d.databases.useAccount(null)
        d.courses.create("Lokal saja", null)
        d.sync.syncOnce()
        assertEquals(0, calls)
    }

    @Test
    fun mutationPayloadUsesApiFieldNames() = runBlocking<Unit> {
        val pushed = mutableListOf<MutationDto>()
        val d = device { request ->
            if (request.url.encodedPath.endsWith("/push")) {
                val m = request.pushedMutations()
                pushed += m
                json(pushResponse(*m.map(::applied).toTypedArray()))
            } else {
                json(pullResponse())
            }
        }
        val courseId = d.courses.create("MK", null)
        d.notes.create(courseId, "Judul", "Isi")
        d.sync.syncOnce()
        val note = pushed.first { it.entityType == "note" }
        assertEquals(courseId, note.payload["course_id"]!!.jsonPrimitive.content)
        assertEquals("create", note.operation)
        assertNull(note.payload["courseId"])
    }
}
