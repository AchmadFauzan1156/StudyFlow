package id.studyflow.di

import id.studyflow.data.AppClock
import id.studyflow.data.IdGenerator
import id.studyflow.data.SystemAppClock
import id.studyflow.data.UuidGenerator
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.remote.AiApi
import id.studyflow.data.remote.ApiCaller
import id.studyflow.data.remote.AuthApi
import id.studyflow.data.remote.MaterialsApi
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.remote.SyncApi
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.LocalMutations
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.repository.SettingsRepository
import id.studyflow.data.repository.TaskRepository
import id.studyflow.data.sync.ConflictRepository
import id.studyflow.data.sync.LocalImport
import id.studyflow.data.sync.SyncEngine
import id.studyflow.data.sync.SyncManager
import id.studyflow.ui.AppViewModel
import id.studyflow.ui.account.AccountViewModel
import id.studyflow.ui.account.ConflictsViewModel
import id.studyflow.ui.courses.CourseDetailViewModel
import id.studyflow.ui.courses.CoursesViewModel
import id.studyflow.ui.dashboard.HomeViewModel
import id.studyflow.ui.materials.AskViewModel
import id.studyflow.ui.materials.DocumentViewModel
import id.studyflow.ui.materials.MaterialsViewModel
import id.studyflow.ui.materials.SourceViewModel
import id.studyflow.ui.notes.NoteEditorViewModel
import id.studyflow.ui.progress.ProgressViewModel
import id.studyflow.ui.settings.SettingsViewModel
import id.studyflow.ui.study.FlashcardsViewModel
import id.studyflow.ui.study.QuizHistoryViewModel
import id.studyflow.ui.study.TopicsViewModel
import id.studyflow.ui.study.QuizViewModel
import id.studyflow.ui.study.StudyNoteViewModel
import id.studyflow.ui.study.SummaryViewModel
import id.studyflow.ui.tasks.TaskEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val dataModule = module {
    single<AppClock> { SystemAppClock }
    single<IdGenerator> { UuidGenerator }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { DatabaseManager(get()) }
    single { LocalMutations(get(), get(), get()) }
    single { CourseRepository(get(), get()) }
    single { NoteRepository(get(), get()) }
    single { TaskRepository(get(), get()) }
    single { SettingsRepository(get()) }

    single { AuthApi(get(), get()) }
    single { SessionManager(get(), get(), get(), get(), get()) }
    single { ApiCaller(get(), baseUrl = { get<ServerConfig>().apiBaseUrl }, tokens = get<SessionManager>()) }
    single { SyncApi(get()) }
    single { AiApi(get()) }
    single { MaterialsApi(get()) }
    single { AiRepository(get(), get(), get(), get()) }
    single { SyncEngine(get(), get(), get()) }
    single { SyncManager(get(), get(), get(), get(), get()) }
    single { ConflictRepository(get(), get()) }
    single { LocalImport(get(), get()) }
}

val viewModelModule = module {
    viewModel { AppViewModel(get(), get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get()) }
    viewModel { CoursesViewModel(get()) }
    viewModel { params -> CourseDetailViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> NoteEditorViewModel(params[0], params[1], get()) }
    viewModel { params -> TaskEditorViewModel(params[0], params[1], get(), get()) }
    viewModel { ProgressViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { AccountViewModel(get(), get(), get(), get(), get()) }
    viewModel { ConflictsViewModel(get()) }
    viewModel { params -> StudyNoteViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> SummaryViewModel(params.get(), get()) }
    viewModel { params -> QuizViewModel(params[0], params[1], get(), get()) }
    viewModel { QuizHistoryViewModel(get()) }
    viewModel { TopicsViewModel(get()) }
    viewModel { FlashcardsViewModel(get()) }
    viewModel { params -> MaterialsViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> DocumentViewModel(params.get(), get()) }
    viewModel { params -> AskViewModel(params.get(), get(), get()) }
    viewModel { params -> SourceViewModel(params.get(), get()) }
}

fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(platformModule, dataModule, viewModelModule)
}.also { it.koin.get<SyncManager>().start() }
