package id.studyflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.studyflow.domain.ThemeMode

/** Bentuk: kecil untuk chip/badge, sedang untuk input/panel, besar untuk FAB/dialog (DESIGN_SYSTEM.md §4). */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun StudyFlowTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalCoursePalette provides coursePalette(dark)) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = AppShapes,
            typography = appTypography(),
            content = content,
        )
    }
}

/** Warna penanda sebuah mata kuliah: tetap untuk ID yang sama di semua perangkat (ID ikut tersinkron). */
class CourseColors(val accent: Color, val container: Color)

@Composable
@ReadOnlyComposable
fun courseColors(courseId: String): CourseColors {
    val palette = LocalCoursePalette.current
    val accent = palette.accents[(courseId.hashCode() and Int.MAX_VALUE) % palette.accents.size]
    return CourseColors(accent = accent, container = accent.copy(alpha = palette.containerAlpha))
}
