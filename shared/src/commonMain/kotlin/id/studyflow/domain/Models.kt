package id.studyflow.domain

data class Course(
    val id: String,
    val name: String,
    val semester: String?,
    val noteCount: Int = 0,
    val openTaskCount: Int = 0,
    val pendingSync: Boolean = true,
)

data class Note(
    val id: String,
    val courseId: String,
    val title: String,
    val content: String,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
)

enum class TaskStatus { TODO, DONE }

data class StudyTask(
    val id: String,
    val courseId: String,
    val courseName: String?,
    val title: String,
    val description: String,
    val dueAt: Long,
    val timeZoneId: String,
    val status: TaskStatus,
    val pendingSync: Boolean = true,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
