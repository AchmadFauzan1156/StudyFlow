package id.studyflow.ui

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object CoursesRoute
@Serializable data object ProgressRoute
@Serializable data object SettingsRoute
@Serializable data class CourseDetailRoute(val courseId: String, val initialTab: Int = 0)
@Serializable data class NoteEditorRoute(val courseId: String, val noteId: String? = null)
@Serializable data class TaskEditorRoute(val courseId: String, val taskId: String? = null)
@Serializable data object AuthRoute
@Serializable data object ConflictsRoute
@Serializable data class StudyNoteRoute(val noteId: String)
@Serializable data class SummaryRoute(val summaryId: String)
@Serializable data class QuizRoute(val quizId: String, val attemptId: String? = null)
@Serializable data class DocumentRoute(val documentId: String)
@Serializable data class AskRoute(val courseId: String, val courseName: String)
@Serializable data class SourceRoute(val chunkId: String)
@Serializable data object FlashcardsRoute
