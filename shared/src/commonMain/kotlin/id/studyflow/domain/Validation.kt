package id.studyflow.domain

object Limits {
    const val COURSE_NAME_MAX = 80
    const val SEMESTER_MAX = 40
    const val NOTE_TITLE_MAX = 120
    const val NOTE_CONTENT_MAX = 100_000
    const val TASK_TITLE_MAX = 120
    const val TASK_DESCRIPTION_MAX = 2_000
}

class ValidationException(message: String) : IllegalArgumentException(message)

internal fun requireText(value: String, field: String, max: Int): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) throw ValidationException("$field wajib diisi")
    if (trimmed.length > max) throw ValidationException("$field maksimal $max karakter")
    return trimmed
}

internal fun optionalText(value: String?, field: String, max: Int): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.length > max) throw ValidationException("$field maksimal $max karakter")
    return trimmed.ifEmpty { null }
}
