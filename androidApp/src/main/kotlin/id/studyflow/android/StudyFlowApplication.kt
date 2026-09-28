package id.studyflow.android

import android.app.Application
import id.studyflow.di.androidPlatformModule
import id.studyflow.di.initKoin
import org.koin.android.ext.koin.androidContext

class StudyFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(androidPlatformModule(this)) {
            androidContext(this@StudyFlowApplication)
        }
    }
}
