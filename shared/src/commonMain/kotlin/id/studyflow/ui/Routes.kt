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
