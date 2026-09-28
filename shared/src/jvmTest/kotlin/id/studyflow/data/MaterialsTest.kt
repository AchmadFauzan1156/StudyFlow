package id.studyflow.data

import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DOC_JSON = """{"id":"d1","course_id":"c1","title":"RSA","status":"queued","page_count":3,
"size_bytes":1200,"warnings":[],"version_id":"v1","job_id":"j1"}"""

class MaterialsTest {
    private val devices = mutableListOf<Device>()
    private val requests = mutableListOf<HttpRequestData>()
    private var online = true

    @AfterTest
    fun close() = devices.forEach { it.close() }

    private fun device(): Device = Device(
        MockEngine { request ->
            requests += request
            if (!online) throw IOException("offline")
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.method.value == "POST" && request.url.encodedPath == "/v1/documents" ->
                    respond(DOC_JSON, HttpStatusCode.Accepted, headers)
                request.url.encodedPath == "/v1/documents" -> respond("[$DOC_JSON]", HttpStatusCode.OK, headers)
                request.url.encodedPath.startsWith("/v1/chunks/") ->
                    respond("""{"code":"not_found","message":"Sumber tidak ditemukan"}""", HttpStatusCode.NotFound, headers)
                else -> respond("""{"code":"not_found","message":"x"}""", HttpStatusCode.NotFound, headers)
            }
        },
    ).also {
        it.databases.useAccount("alice")
        devices += it
    }

    private suspend fun HttpRequestData.bodyBytes(): ByteArray = when (val b = body) {
        is OutgoingContent.ByteArrayContent -> b.bytes()
        is OutgoingContent.WriteChannelContent -> {
            val channel = io.ktor.utils.io.ByteChannel()
            b.writeTo(channel)
            channel.flushAndClose()
            channel.readRemaining().readByteArray()
        }
        else -> error("body tidak dikenal: ${b::class}")
    }

    @Test
    fun uploadSendsMultipartWithIdempotencyKey() = runBlocking<Unit> {
        val d = device()
        val pdf = testPdf(listOf("RSA memakai dua bilangan prima."))
        val doc = d.ai.upload("c1", "RSA", "rsa.pdf", pdf, "upload-key-001")

        assertEquals("queued", doc.status)
        val request = requests.single()
        assertEquals("upload-key-001", request.headers["Idempotency-Key"])
        val body = request.bodyBytes().toString(Charsets.ISO_8859_1)
        assertTrue("form-data; name=\"course_id\"" in body && Regex("\r?\nc1\r?\n") in body)
        assertTrue("form-data; name=\"title\"" in body)
        assertTrue("form-data; name=\"file\"; filename=\"rsa.pdf\"" in body && "Content-Type: application/pdf" in body)
        assertTrue("%PDF-1.4" in body, "isi PDF ikut terkirim")
    }

    @Test
    fun oversizedFileIsRejectedBeforeSending() = runBlocking<Unit> {
        val d = device()
        val e = assertFailsWith<AiException> {
            d.ai.upload("c1", "Besar", "besar.pdf", ByteArray(AiRepository.MAX_UPLOAD_BYTES + 1), "upload-key-002")
        }
        assertEquals("file_too_large", e.code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun documentListIsReadableOffline() = runBlocking<Unit> {
        val d = device()
        assertFalse(d.ai.documents("c1").fromCache)
        online = false
        val cached = d.ai.documents("c1")
        assertTrue(cached.fromCache)
        assertEquals("RSA", cached.value.single().title)
    }

    @Test
    fun deletedSourceIsNotServedFromCache() = runBlocking<Unit> {
        val d = device()
        val e = assertFailsWith<AiException> { d.ai.chunk("k1") }
        assertEquals("not_found", e.code)
    }

    @Test
    fun courseMustBeSyncedBeforeUpload() = runBlocking<Unit> {
        val d = device()
        val courseId = d.courses.create("MK", null)
        assertFalse(d.ai.courseSynced(courseId))
    }
}
