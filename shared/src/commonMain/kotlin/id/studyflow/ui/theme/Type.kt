package id.studyflow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.studyflow.resources.Res
import id.studyflow.resources.plus_jakarta_sans_bold
import id.studyflow.resources.plus_jakarta_sans_medium
import id.studyflow.resources.plus_jakarta_sans_regular
import id.studyflow.resources.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font

/** Plus Jakarta Sans (Tokotype, OFL 1.1), empat ketebalan yang dibundel di composeResources/font. */
@Composable
private fun appFontFamily(): FontFamily = FontFamily(
    Font(Res.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(Res.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(Res.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

/** Skala tipografi DESIGN_SYSTEM.md §3; token yang tidak disebut hanya diganti fontnya. */
@Composable
internal fun appTypography(): Typography {
    val family = appFontFamily()
    val base = Typography()
    fun TextStyle.withFamily() = copy(fontFamily = family)
    fun TextStyle.sized(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = copy(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking.sp,
    )
    return Typography(
        displayLarge = base.displayLarge.withFamily(),
        displayMedium = base.displayMedium.withFamily(),
        displaySmall = base.displaySmall.withFamily(),
        headlineLarge = base.headlineLarge.withFamily(),
        headlineMedium = base.headlineMedium.sized(28, 36, FontWeight.Bold),
        headlineSmall = base.headlineSmall.sized(24, 32, FontWeight.Bold),
        titleLarge = base.titleLarge.sized(20, 28, FontWeight.SemiBold),
        titleMedium = base.titleMedium.sized(16, 24, FontWeight.SemiBold),
        titleSmall = base.titleSmall.sized(14, 20, FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.sized(16, 24, FontWeight.Normal),
        bodyMedium = base.bodyMedium.sized(14, 20, FontWeight.Normal),
        bodySmall = base.bodySmall.sized(12, 16, FontWeight.Normal),
        labelLarge = base.labelLarge.sized(14, 20, FontWeight.SemiBold),
        labelMedium = base.labelMedium.sized(12, 16, FontWeight.SemiBold, tracking = 0.6f),
        labelSmall = base.labelSmall.sized(11, 16, FontWeight.Medium),
    )
}

/** Angka tabular agar jam, tanggal, dan statistik sejajar antar baris. */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
