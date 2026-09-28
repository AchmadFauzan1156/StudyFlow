package id.studyflow.data

import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.DpapiSessionStore
import id.studyflow.data.auth.MemorySessionStore
import id.studyflow.data.auth.SignUpOutcome
import id.studyflow.data.auth.StoredSession
import id.studyflow.data.remote.AuthException
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.sync.importedId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JWT tidak bertanda tangan dengan klaim sub; klien hanya membaca sub, verifikasi dilakukan server. */
private fun fakeJwt(sub: String): String {
    val enc = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    return "${enc.encode("""{"alg":"none"}""".encodeToByteArray())}.${enc.encode("""{"sub":"$sub"}""".encodeToByteArray())}.sig"
}

private fun tokens(user: String, refresh: String, withUser: Boolean = true) = buildString {
    append("""{"access_token":"${fakeJwt(user)}","token_type":"bearer","expires_in":3600,"refresh_token":"$refresh"""")
    if (withUser) append(""","user":{"id":"$user","email":"$user@contoh.id"}""")
    append("}")
}

class SessionTest {
    private val devices = mutableListOf<Device>()
    val refreshRequests = mutableListOf<String>()
    var logoutCalls = 0

    @AfterTest
    fun close() = devices.forEach { it.close() }

    private fun device(): Device = Device(
        MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            assertEquals("sb_publishable_test", request.headers["apikey"])
            when {
                request.url.encodedPath.endsWith("/logout") -> { logoutCalls++; respond("", HttpStatusCode.NoContent) }
                request.url.encodedPath.endsWith("/signup") ->
                    respond("""{"id":"u-new","email":"baru@contoh.id","confirmation_sent_at":"2026-09-28T00:00:00Z"}""", HttpStatusCode.OK, headers)
                request.url.parameters["grant_type"] == "password" -> when {
                    "salah" in body -> respond("""{"error":"invalid_grant","error_description":"Invalid login credentials","error_code":"invalid_credentials"}""", HttpStatusCode.BadRequest, headers)
                    "\"alice@" in body -> respond(tokens("alice", "r1"), HttpStatusCode.OK, headers)
                    else -> respond(tokens("bob", "b1", withUser = false), HttpStatusCode.OK, headers)
                }
                request.url.parameters["grant_type"] == "refresh_token" -> {
                    refreshRequests += body
                    if ("revoked" in body) {
                        respond("""{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""", HttpStatusCode.BadRequest, headers)
                    } else {
                        respond(tokens("alice", "r2"), HttpStatusCode.OK, headers)
                    }
                }
                else -> error("tak terduga: ${request.url}")
            }
        },
    ).also { devices += it }

    @Test
    fun signInSwitchesToAccountDatabaseAndStoresRefreshToken() = runBlocking<Unit> {
        val d = device()
        val store = MemorySessionStore()
        val session = d.session(store)
        session.signIn("alice@contoh.id", "rahasia123")

        assertEquals(AuthState.SignedIn("alice", "alice@contoh.id"), session.state.value)
        assertEquals("alice", d.databases.accountId.value)
        assertEquals(StoredSession("alice", "alice@contoh.id", "r1"), store.load())
    }

    @Test
    fun userIdFallsBackToJwtSubject() = runBlocking<Unit> {
        val d = device()
        val session = d.session()
        session.signIn("bob@contoh.id", "rahasia123")
        assertEquals("bob", (session.state.value as AuthState.SignedIn).userId)
    }

    @Test
    fun accountsDoNotSeeEachOthersData() = runBlocking<Unit> {
        val d = device()
        val session = d.session()
        d.courses.create("Catatan lokal", null)

        session.signIn("alice@contoh.id", "x")
        assertTrue(d.courses.observeCourses().first().isEmpty(), "akun baru tidak melihat data mode lokal")
        d.courses.create("Milik Alice", null)

        session.signOut()
        assertEquals(listOf("Catatan lokal"), d.courses.observeCourses().first().map { it.name })

        session.signIn("bob@contoh.id", "x")
        assertTrue(d.courses.observeCourses().first().isEmpty(), "Bob tidak melihat data Alice")

        session.signOut()
        session.signIn("alice@contoh.id", "x")
        assertEquals(listOf("Milik Alice"), d.courses.observeCourses().first().map { it.name })
        assertEquals(2, logoutCalls)
    }

    @Test
    fun expiredAccessTokenIsRefreshedAndRotated() = runBlocking<Unit> {
        val d = device()
        val store = MemorySessionStore()
        val session = d.session(store)
        session.signIn("alice@contoh.id", "x")
        assertEquals(tokensAccess("alice"), session.accessToken(false))
        assertTrue(refreshRequests.isEmpty(), "token masih berlaku, tidak perlu refresh")

        d.clock.now += 3_600_000
        session.accessToken(false)
        assertEquals(1, refreshRequests.size)
        assertTrue("r1" in refreshRequests.single())
        assertEquals("r2", store.load()!!.refreshToken, "refresh token hasil rotasi disimpan")
    }

    @Test
    fun revokedRefreshTokenMarksSessionExpired() = runBlocking<Unit> {
        val d = device()
        val store = MemorySessionStore().apply { save(StoredSession("alice", "a@contoh.id", "revoked")) }
        val session = d.session(store)
        assertNull(session.accessToken(false))
        val state = assertIs<AuthState.SignedIn>(session.state.value)
        assertTrue(state.sessionExpired)
        assertEquals("alice", d.databases.accountId.value, "data akun tetap bisa dipakai offline")
    }

    @Test
    fun sessionIsRestoredWithoutNetworkOnStartup() {
        val d = device()
        val store = MemorySessionStore().apply { save(StoredSession("alice", "a@contoh.id", "r1")) }
        val session = d.session(store)
        assertEquals(AuthState.SignedIn("alice", "a@contoh.id"), session.state.value)
        assertEquals("alice", d.databases.accountId.value)
    }

    @Test
    fun wrongPasswordShowsFriendlyMessage() = runBlocking<Unit> {
        val session = device().session()
        val e = assertFailsWith<AuthException> { session.signIn("alice@contoh.id", "salah") }
        assertEquals("Email atau kata sandi salah.", e.message)
        assertEquals(AuthState.SignedOut, session.state.value)
    }

    @Test
    fun signUpMayRequireEmailConfirmation() = runBlocking<Unit> {
        val session = device().session()
        assertEquals(SignUpOutcome.ConfirmEmail, session.signUp("baru@contoh.id", "rahasia123"))
        assertEquals(AuthState.SignedOut, session.state.value)
    }

    @Test
    fun unconfiguredServerKeepsLocalModeOnly() = runBlocking<Unit> {
        val session = device().session(config = ServerConfig("", "", ""))
        assertEquals(AuthState.Unconfigured, session.state.value)
        assertFailsWith<AuthException> { session.signIn("alice@contoh.id", "x") }
    }

    @Test
    fun localImportIsIdempotentAndScopedPerAccount() = runBlocking<Unit> {
        val d = device()
        val session = d.session()
        val localCourse = d.courses.create("Kriptografi", null)
        d.notes.create(localCourse, "RSA", "isi")

        session.signIn("alice@contoh.id", "x")
        assertTrue(d.import.shouldOffer("alice"))
        assertEquals(1, d.import.importInto("alice"))
        assertEquals(0, d.import.importInto("alice"), "impor ulang tidak menggandakan")
        assertFalse(d.import.shouldOffer("alice"))

        val imported = d.courses.observeCourses().first().single()
        assertEquals(importedId("alice", localCourse), imported.id)
        assertEquals(1, imported.noteCount)
        assertEquals(2, d.db.outboxDao().getAll().size)
        assertNotEquals(importedId("alice", localCourse), importedId("bob", localCourse))
        assertEquals(importedId("alice", localCourse), importedId("alice", localCourse))

        session.signOut()
        assertEquals(localCourse, d.courses.observeCourses().first().single().id, "data mode lokal tetap ada")
    }

    @Test
    fun dpapiStoreEncryptsSessionOnWindows() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val dir = Files.createTempDirectory("studyflow-dpapi").toFile()
        try {
            val file = File(dir, "session.bin")
            val store = DpapiSessionStore(file)
            val session = StoredSession("alice", "a@contoh.id", "refresh-token-rahasia")
            store.save(session)
            assertFalse("refresh-token-rahasia" in String(file.readBytes(), Charsets.ISO_8859_1))
            assertEquals(session, DpapiSessionStore(file).load())
            store.clear()
            assertNull(store.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}

private fun tokensAccess(user: String) = fakeJwt(user)
