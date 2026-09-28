package id.studyflow.di

import androidx.room.Room
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.buildDatabase
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
    single<StudyFlowDatabase> {
        val dbFile = File(dataDir, StudyFlowDatabase.LOCAL_DB_NAME)
        buildDatabase(Room.databaseBuilder<StudyFlowDatabase>(name = dbFile.absolutePath))
    }
}
