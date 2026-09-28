package id.studyflow.di

import android.content.Context
import androidx.room.Room
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.buildDatabase
import org.koin.dsl.module

fun androidPlatformModule(context: Context) = module {
    single<StudyFlowDatabase> {
        val appContext = context.applicationContext
        val dbFile = appContext.getDatabasePath(StudyFlowDatabase.LOCAL_DB_NAME)
        buildDatabase(Room.databaseBuilder<StudyFlowDatabase>(appContext, dbFile.absolutePath))
    }
}
