package id.studyflow.di

import id.studyflow.data.AppClock
import id.studyflow.data.IdGenerator
import id.studyflow.data.SystemAppClock
import id.studyflow.data.UuidGenerator
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.LocalMutations
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.repository.SettingsRepository
import id.studyflow.data.repository.TaskRepository
import id.studyflow.ui.AppViewModel
import id.studyflow.ui.courses.CourseDetailViewModel
import id.studyflow.ui.courses.CoursesViewModel
import id.studyflow.ui.dashboard.HomeViewModel
import id.studyflow.ui.notes.NoteEditorViewModel
import id.studyflow.ui.progress.ProgressViewModel
import id.studyflow.ui.settings.SettingsViewModel
import id.studyflow.ui.tasks.TaskEditorViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val dataModule = module {
    single<AppClock> { SystemAppClock }
    single<IdGenerator> { UuidGenerator }
    single { LocalMutations(get(), get(), get()) }
    single { CourseRepository(get(), get()) }
    single { NoteRepository(get(), get()) }
    single { TaskRepository(get(), get()) }
    single { SettingsRepository(get()) }
}

val viewModelModule = module {
    viewModel { AppViewModel(get()) }
    viewModel { HomeViewModel(get(), get(), get()) }
    viewModel { CoursesViewModel(get()) }
    viewModel { params -> CourseDetailViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> NoteEditorViewModel(params[0], params[1], get()) }
    viewModel { params -> TaskEditorViewModel(params[0], params[1], get(), get()) }
    viewModel { ProgressViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}

fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(platformModule, dataModule, viewModelModule)
}
