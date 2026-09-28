package id.studyflow.di

import android.content.Context
import androidx.room.Room
import id.studyflow.config.AppConfig
import id.studyflow.data.auth.KeystoreSessionStore
import id.studyflow.data.auth.SecureSessionStore
import id.studyflow.data.local.DatabaseFactory
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.buildDatabase
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.remote.studyFlowDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module

fun androidPlatformModule(context: Context) = module {
    val appContext = context.applicationContext
    single<DatabaseFactory> {
        DatabaseFactory { name ->
            val dbFile = appContext.getDatabasePath(name)
            buildDatabase(Room.databaseBuilder<StudyFlowDatabase>(appContext, dbFile.absolutePath))
        }
    }
    single<SecureSessionStore> { KeystoreSessionStore(appContext) }
    single { HttpClient(OkHttp) { studyFlowDefaults() } }
    single {
        ServerConfig(
            supabaseUrl = AppConfig.supabaseUrl,
            supabasePublishableKey = AppConfig.supabasePublishableKey,
            // Emulator Android menjangkau host lewat 10.0.2.2, jadi URL API bisa berbeda dari desktop.
            apiBaseUrl = AppConfig.androidApiBaseUrl.ifBlank { AppConfig.apiBaseUrl },
        )
    }
}
