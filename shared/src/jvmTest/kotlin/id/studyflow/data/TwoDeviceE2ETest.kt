package id.studyflow.data

import id.studyflow.data.remote.AccessTokenProvider
import id.studyflow.data.repository.EntityType
import id.studyflow.data.repository.TaskInput
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.flow.first
import id.studyflow.data.ai.NoteReadiness
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Uji end-to-end dua perangkat melawan backend FastAPI + PostgreSQL sungguhan.
 * Aktif jika STUDYFLOW_E2E_API_URL dan STUDYFLOW_E2E_JWT_SECRET diset (backend memakai HS256 secret yang
 * sama dan STUDYFLOW_SUPABASE_URL = STUDYFLOW_E2E_ISSUER_BASE). Tanpa variabel itu tes dilewati.
 */
class TwoDeviceE2ETest {
    private val apiUrl = System.getenv("STUDYFLOW_E2E_API_URL")
    private val secret = System.getenv("STUDYFLOW_E2E_JWT_SECRET")
    private val issuerBase = System.getenv("STUDYFLOW_E2E_ISSUER_BASE") ?: "http://auth.test.local"
    private val devices = mutableListOf<Device>()

    @AfterTest
    fun close() = devices.forEach { it.close() }

    private val enabled get() = !apiUrl.isNullOrBlank() && !secret.isNullOrBlank()

    private fun token(userId: String): String {
        val enc = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val now = System.currentTimeMillis() / 1000
        val header = enc.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload = enc.encode(
            """{"sub":"$userId","aud":"authenticated","iss":"$issuerBase/auth/v1","exp":${now + 3600},"iat":$now,"role":"authenticated"}"""
                .encodeToByteArray(),
        )
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256")) }
        val signature = enc.encode(mac.doFinal("$header.$payload".encodeToByteArray()))
        return "$header.$payload.$signature"
    }

    private fun device(userId: String): Device {
        val t = token(userId)
        return Device(OkHttp.create(), AccessTokenProvider { t }, apiBaseUrl = apiUrl).also {
            it.databases.useAccount(userId)
            devices += it
        }
    }

    @Test
    fun twoDevicesSyncEditsConflictsAndDeletes() = runBlocking<Unit> {
        if (!enabled) return@runBlocking
        val alice = UUID.randomUUID().toString()
        val a = device(alice)
        val b = device(alice)

        // 1. Perangkat A membuat data; B menerimanya.
        val courseId = a.courses.create("Kriptografi", "Ganjil")
        val noteId = a.notes.create(courseId, "RSA", "p dan q prima")
        a.tasks.create(courseId, TaskInput("Laporan", "", a.clock.now + 86_400_000, "Asia/Jakarta"))
        assertEquals(3, a.sync.syncOnce().pushed)
        b.sync.syncOnce()
        assertEquals(listOf("Kriptografi"), b.courses.observeCourses().first().map { it.name })
        assertEquals("RSA", b.notes.observeByCourse(courseId).first().single().title)
        assertEquals(1, b.tasks.observeAll().first().size)

        // 2. Edit bersamaan saat "offline": A menang duluan, B mendapat konflik tanpa kehilangan editnya.
        a.notes.update(noteId, "RSA (versi A)", "dari A")
        b.notes.update(noteId, "RSA (versi B)", "dari B")
        assertEquals(1, a.sync.syncOnce().pushed)
        assertEquals(1, b.sync.syncOnce().conflicts)
        assertEquals("RSA (versi B)", b.notes.get(noteId)!!.title, "edit B tidak ditimpa diam-diam")
        val conflict = b.conflicts.observeConflicts().first().single()
        assertEquals("RSA (versi A)", conflict.server.title)

        // B memilih versinya sendiri; A menerima hasilnya.
        b.conflicts.keepLocal(EntityType.NOTE, noteId)
        assertEquals(1, b.sync.syncOnce().pushed)
        a.sync.syncOnce()
        assertEquals("RSA (versi B)", a.notes.get(noteId)!!.title)

        // 3. Hapus mata kuliah di A ikut menghapus catatan & tugas di B.
        a.courses.delete(courseId)
        a.sync.syncOnce()
        b.sync.syncOnce()
        assertTrue(b.courses.observeCourses().first().isEmpty())
        assertTrue(b.tasks.observeAll().first().isEmpty())
        assertTrue(b.db.outboxDao().getAll().isEmpty())
        assertTrue(b.conflicts.observeConflicts().first().isEmpty())
    }

    @Test
    fun lostResponseRetryDoesNotDuplicate() = runBlocking<Unit> {
        if (!enabled) return@runBlocking
        val user = UUID.randomUUID().toString()
        val c = device(user)
        var dropNext = true
        c.http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (dropNext && request.url.build().encodedPath.endsWith("/push")) {
                dropNext = false
                // Server sudah memproses, tetapi respons "hilang" di jaringan.
                throw IOException("respons hilang")
            }
            call
        }
        c.courses.create("Retry", null)
        assertFailsWith<IOException> { c.sync.syncOnce() }
        assertEquals(1, c.sync.syncOnce().pushed)

        val observer = device(user)
        observer.sync.syncOnce()
        assertEquals(listOf("Retry"), observer.courses.observeCourses().first().map { it.name })
    }

    @Test
    fun otherAccountSeesNothing() = runBlocking<Unit> {
        if (!enabled) return@runBlocking
        val a = device(UUID.randomUUID().toString())
        a.courses.create("Rahasia", null)
        a.sync.syncOnce()

        val bob = device(UUID.randomUUID().toString())
        assertEquals(0, bob.sync.syncOnce().pulled)
        assertTrue(bob.courses.observeCourses().first().isEmpty())
    }

    /** Membutuhkan worker (`python -m app.worker`) yang berjalan dengan provider demo; aktif jika STUDYFLOW_E2E_AI=1. */
    @Test
    fun aiQuizFlowThroughWorker() = runBlocking<Unit> {
        if (!enabled || System.getenv("STUDYFLOW_E2E_AI") != "1") return@runBlocking
        val d = device(UUID.randomUUID().toString())
        val courseId = d.courses.create("Kriptografi", null)
        val noteId = d.notes.create(courseId, "RSA", "RSA memakai dua bilangan prima. Modulus n = p kali q. Kunci publik e dan n.")
        d.sync.syncOnce()
        val version = (d.ai.noteReadiness(noteId) as NoteReadiness.Ready).version
        assertEquals("demo", d.ai.info().value.mode)

        val key = UUID.randomUUID().toString()
        val queued = d.ai.requestQuiz(noteId, version, 3, key)
        assertEquals(queued.id, d.ai.requestQuiz(noteId, version, 3, key).id, "idempotency key yang sama")
        var job = queued
        withTimeout(30_000) {
            while (!job.isTerminal) {
                delay(500)
                job = d.ai.job(queued.id)
            }
        }
        assertEquals("succeeded", job.status)

        val quiz = d.ai.quiz(job.resultId!!).value
        assertEquals(3, quiz.questions.size)
        assertTrue(quiz.isDemo)
        val answers = quiz.questions.associate { it.id to it.options.first().id }
        val attemptKey = UUID.randomUUID().toString()
        val attempt = d.ai.submitAttempt(quiz.id, answers, attemptKey)
        assertEquals(attempt.id, d.ai.submitAttempt(quiz.id, answers, attemptKey).id, "pengiriman ganda tidak menggandakan")
        assertEquals(3, attempt.total)
        assertEquals(attempt.results.count { it.correct }, attempt.score)
        assertTrue(attempt.results.all { r -> r.sources.single().title == "RSA" })
        assertEquals(listOf(attempt.id), d.ai.attempts().value.map { it.id })
        assertEquals(1, d.ai.noteResults(noteId).value.results.count { it.type == "quiz" })
    }
}
