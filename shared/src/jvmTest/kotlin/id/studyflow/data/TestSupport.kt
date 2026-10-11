package id.studyflow.data

import androidx.room.Room
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.auth.MemorySessionStore
import id.studyflow.data.auth.SecureSessionStore
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.local.DatabaseFactory
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.buildDatabase
import id.studyflow.data.remote.AccessTokenProvider
import id.studyflow.data.remote.AiApi
import id.studyflow.data.remote.ApiCaller
import id.studyflow.data.remote.AuthApi
import id.studyflow.data.remote.MaterialsApi
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.remote.SyncApi
import id.studyflow.data.remote.studyFlowDefaults
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.LocalMutations
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.repository.TaskRepository
import id.studyflow.data.sync.ConflictRepository
import id.studyflow.data.sync.LocalImport
import id.studyflow.data.sync.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.datetime.TimeZone

class FakeClock(var now: Long = 1_790_000_000_000L) : AppClock {
    override fun nowMillis(): Long = now
    override fun currentZone(): TimeZone = TimeZone.of("Asia/Jakarta")
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var next = 0
    override fun newId(): String = "$prefix-${next++}"
}

/** Database in-memory per nama file, meniru satu perangkat. */
class InMemoryDatabases : DatabaseFactory {
    val opened = mutableListOf<StudyFlowDatabase>()
    override fun open(name: String): StudyFlowDatabase =
        buildDatabase(Room.inMemoryDatabaseBuilder<StudyFlowDatabase>()).also { opened += it }

    fun closeAll() = opened.forEach { it.close() }
}

val testServerConfig = ServerConfig(
    supabaseUrl = "https://auth.test",
    supabasePublishableKey = "sb_publishable_test",
    apiBaseUrl = "https://api.test",
)

/** Satu "perangkat": database, repository, dan mesin sinkronisasi. */
class Device(
    engine: HttpClientEngine,
    tokens: AccessTokenProvider = AccessTokenProvider { "token" },
    val clock: FakeClock = FakeClock(),
    ids: IdGenerator = UuidGenerator,
    apiBaseUrl: String = testServerConfig.apiBaseUrl,
) {
    val factory = InMemoryDatabases()
    val databases = DatabaseManager(factory)
    val mutations = LocalMutations(databases, clock, ids)
    val courses = CourseRepository(databases, mutations)
    val notes = NoteRepository(databases, mutations)
    val tasks = TaskRepository(databases, mutations)
    val conflicts = ConflictRepository(databases, mutations)
    val import = LocalImport(databases, mutations)
    val http = HttpClient(engine) { studyFlowDefaults() }
    val apiCaller = ApiCaller(http, { apiBaseUrl }, tokens)
    val sync = SyncEngine(databases, SyncApi(apiCaller), clock)
    val ai = AiRepository(databases, AiApi(apiCaller), MaterialsApi(apiCaller), clock)

    fun session(store: SecureSessionStore = MemorySessionStore(), config: ServerConfig = testServerConfig) =
        SessionManager(config, AuthApi(http, config), store, databases, clock)

    val db get() = databases.db

    fun close() {
        http.close()
        factory.closeAll()
    }
}
