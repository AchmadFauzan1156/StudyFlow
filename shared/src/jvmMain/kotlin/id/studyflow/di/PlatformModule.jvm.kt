package id.studyflow.di

import androidx.room.Room
import id.studyflow.config.AppConfig
import id.studyflow.data.auth.SecureSessionStore
import id.studyflow.data.auth.desktopSessionStore
import id.studyflow.data.local.DatabaseFactory
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.buildDatabase
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.remote.studyFlowDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module
import java.io.File

/** Folder data desktop: %APPDATA%\StudyFlow di Windows, ~/.studyflow di OS lain. */
fun desktopDataDir(): File {
    val appData = System.getenv("APPDATA")
    val dir = if (!appData.isNullOrBlank()) File(appData, "StudyFlow") else File(System.getProperty("user.home"), ".studyflow")
    dir.mkdirs()
    return dir
}

fun desktopPlatformModule(dataDir: File = desktopDataDir()) = module {
    single<DatabaseFactory> {
        DatabaseFactory { name ->
            buildDatabase(Room.databaseBuilder<StudyFlowDatabase>(name = File(dataDir, name).absolutePath))
        }
    }
    single<SecureSessionStore> { desktopSessionStore(dataDir) }
    single { HttpClient(OkHttp) { studyFlowDefaults() } }
    single {
        ServerConfig(
            supabaseUrl = AppConfig.supabaseUrl,
            supabasePublishableKey = AppConfig.supabasePublishableKey,
            apiBaseUrl = AppConfig.apiBaseUrl,
        )
    }
}
