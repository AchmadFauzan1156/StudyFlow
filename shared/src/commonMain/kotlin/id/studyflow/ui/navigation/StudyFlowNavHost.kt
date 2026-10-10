package id.studyflow.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import id.studyflow.data.AppClock
import id.studyflow.ui.AskRoute
import id.studyflow.ui.AuthRoute
import id.studyflow.ui.ConflictsRoute
import id.studyflow.ui.CourseDetailRoute
import id.studyflow.ui.CoursesRoute
import id.studyflow.ui.DocumentRoute
import id.studyflow.ui.FlashcardsRoute
import id.studyflow.ui.HomeRoute
import id.studyflow.ui.NoteEditorRoute
import id.studyflow.ui.ProgressRoute
import id.studyflow.ui.QuizRoute
import id.studyflow.ui.SettingsRoute
import id.studyflow.ui.SourceRoute
import id.studyflow.ui.StudyNoteRoute
import id.studyflow.ui.SummaryRoute
import id.studyflow.ui.TaskEditorRoute
import id.studyflow.ui.account.AuthScreen
import id.studyflow.ui.account.ConflictsScreen
import id.studyflow.ui.courses.CourseDetailScreen
import id.studyflow.ui.courses.CoursesScreen
import id.studyflow.ui.dashboard.HomeScreen
import id.studyflow.ui.materials.AskScreen
import id.studyflow.ui.materials.DocumentScreen
import id.studyflow.ui.materials.SourceScreen
import id.studyflow.ui.notes.NoteEditorScreen
import id.studyflow.ui.progress.ProgressScreen
import id.studyflow.ui.settings.SettingsScreen
import id.studyflow.ui.study.FlashcardsScreen
import id.studyflow.ui.study.QuizScreen
import id.studyflow.ui.study.StudyNoteScreen
import id.studyflow.ui.study.SummaryScreen
import id.studyflow.ui.tasks.TaskEditorScreen
import org.koin.compose.koinInject

@Composable
internal fun StudyFlowNavHost(navController: NavHostController, modifier: Modifier) {
    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenCourses = { navController.navigateTopLevel(CoursesRoute) },
                onOpenTask = { task -> navController.navigate(TaskEditorRoute(task.courseId, task.id)) },
                onOpenConflicts = { navController.navigate(ConflictsRoute) },
                onOpenAuth = { navController.navigate(AuthRoute) },
            )
        }
        composable<CoursesRoute> {
            CoursesScreen(onOpenCourse = { navController.navigate(CourseDetailRoute(it)) })
        }
        composable<ProgressRoute> {
            ProgressScreen(
                onOpenAttempt = { quizId, attemptId -> navController.navigate(QuizRoute(quizId, attemptId)) },
                onOpenNote = { navController.navigate(StudyNoteRoute(it)) },
                onOpenFlashcards = { navController.navigate(FlashcardsRoute) },
            )
        }
        composable<FlashcardsRoute> { FlashcardsScreen(onBack = { navController.popBackStack() }) }
        composable<SettingsRoute> {
            SettingsScreen(
                onOpenAuth = { navController.navigate(AuthRoute) },
                onOpenConflicts = { navController.navigate(ConflictsRoute) },
            )
        }
        composable<AuthRoute> { AuthScreen(onDone = { navController.popBackStack() }) }
        composable<ConflictsRoute> {
            ConflictsScreen(zone = koinInject<AppClock>().currentZone(), onBack = { navController.popBackStack() })
        }
        composable<CourseDetailRoute> { entry ->
            val route = entry.toRoute<CourseDetailRoute>()
            CourseDetailScreen(
                courseId = route.courseId,
                initialTab = route.initialTab,
                onBack = { navController.popBackStack() },
                onOpenNote = { noteId -> navController.navigate(NoteEditorRoute(route.courseId, noteId)) },
                onOpenTask = { taskId -> navController.navigate(TaskEditorRoute(route.courseId, taskId)) },
                onOpenStudy = { noteId -> navController.navigate(StudyNoteRoute(noteId)) },
                onOpenDocument = { navController.navigate(DocumentRoute(it)) },
                onOpenAsk = { name -> navController.navigate(AskRoute(route.courseId, name)) },
            )
        }
        composable<DocumentRoute> { entry ->
            DocumentScreen(entry.toRoute<DocumentRoute>().documentId, onBack = { navController.popBackStack() })
        }
        composable<AskRoute> { entry ->
            val route = entry.toRoute<AskRoute>()
            AskScreen(
                route.courseId,
                route.courseName,
                onBack = { navController.popBackStack() },
                onOpenSource = { navController.navigate(SourceRoute(it)) },
            )
        }
        composable<SourceRoute> { entry ->
            SourceScreen(entry.toRoute<SourceRoute>().chunkId, onBack = { navController.popBackStack() })
        }
        composable<StudyNoteRoute> { entry ->
            val route = entry.toRoute<StudyNoteRoute>()
            StudyNoteScreen(
                noteId = route.noteId,
                zone = koinInject<AppClock>().currentZone(),
                onBack = { navController.popBackStack() },
                onOpenSummary = { navController.navigate(SummaryRoute(it)) },
                onOpenQuiz = { navController.navigate(QuizRoute(it)) },
            )
        }
        composable<SummaryRoute> { entry ->
            SummaryScreen(entry.toRoute<SummaryRoute>().summaryId, koinInject<AppClock>().currentZone()) {
                navController.popBackStack()
            }
        }
        composable<QuizRoute> { entry ->
            val route = entry.toRoute<QuizRoute>()
            QuizScreen(route.quizId, route.attemptId, onBack = { navController.popBackStack() })
        }
        composable<NoteEditorRoute> { entry ->
            val route = entry.toRoute<NoteEditorRoute>()
            NoteEditorScreen(route.courseId, route.noteId, onDone = { navController.popBackStack() })
        }
        composable<TaskEditorRoute> { entry ->
            val route = entry.toRoute<TaskEditorRoute>()
            TaskEditorScreen(route.courseId, route.taskId, onDone = { navController.popBackStack() })
        }
    }
}
