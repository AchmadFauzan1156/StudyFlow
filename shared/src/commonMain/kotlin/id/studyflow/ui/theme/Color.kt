package id.studyflow.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Nilai dan rasio kontrasnya didokumentasikan di docs/ui-ux/DESIGN_SYSTEM.md §2.

internal val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF57534E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7F3F1),
    onSecondaryContainer = Color(0xFF102A28),
    tertiary = Color(0xFF92400E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFFBFBF9),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFBFBF9),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFE8E6E1),
    onSurfaceVariant = Color(0xFF57534E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3F0),
    surfaceContainer = Color(0xFFEEEDE9),
    surfaceContainerHigh = Color(0xFFE8E6E1),
    surfaceContainerHighest = Color(0xFFE2E0DA),
    outline = Color(0xFF78736C),
    outlineVariant = Color(0xFFE4E2DD),
    error = Color(0xFFB42318),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF55160C),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF04302C),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFC9C6C0),
    onSecondary = Color(0xFF2B2926),
    secondaryContainer = Color(0xFF1F302E),
    onSecondaryContainer = Color(0xFFD7EEEB),
    tertiary = Color(0xFFFCD34D),
    onTertiary = Color(0xFF3B2A06),
    tertiaryContainer = Color(0xFF4A3410),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE7E5E1),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE7E5E1),
    surfaceVariant = Color(0xFF323432),
    onSurfaceVariant = Color(0xFFC9C6C0),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF1A1C1B),
    surfaceContainer = Color(0xFF1F2120),
    surfaceContainerHigh = Color(0xFF2A2C2B),
    surfaceContainerHighest = Color(0xFF353736),
    outline = Color(0xFF928D86),
    outlineVariant = Color(0xFF323432),
    error = Color(0xFFFDA29B),
    onError = Color(0xFF55160C),
    errorContainer = Color(0xFF55160C),
    onErrorContainer = Color(0xFFFEE4E2),
)

/** Warna penanda mata kuliah: garis, titik, dan huruf avatar. Latar avatar memakai warna ini dengan alfa. */
@Immutable
class CoursePalette(val accents: List<Color>, val containerAlpha: Float)

private val LightCoursePalette = CoursePalette(
    accents = listOf(
        Color(0xFF0E7490), // cyan
        Color(0xFF2563EB), // biru
        Color(0xFF7C3AED), // ungu
        Color(0xFFE11D48), // mawar
        Color(0xFFB45309), // amber
        Color(0xFF047857), // zamrud
        Color(0xFF0369A1), // langit
        Color(0xFFA21CAF), // fuchsia
    ),
    containerAlpha = 0.14f,
)

private val DarkCoursePalette = CoursePalette(
    accents = listOf(
        Color(0xFF2DD4BF),
        Color(0xFF60A5FA),
        Color(0xFFA78BFA),
        Color(0xFFFB7185),
        Color(0xFFFBBF24),
        Color(0xFF34D399),
        Color(0xFF38BDF8),
        Color(0xFFE879F9),
    ),
    containerAlpha = 0.24f,
)

internal fun coursePalette(dark: Boolean): CoursePalette = if (dark) DarkCoursePalette else LightCoursePalette

internal val LocalCoursePalette = staticCompositionLocalOf { LightCoursePalette }
