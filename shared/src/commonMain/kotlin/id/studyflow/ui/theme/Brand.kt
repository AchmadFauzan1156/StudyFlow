package id.studyflow.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import id.studyflow.resources.Res
import id.studyflow.resources.studyflow_app_icon
import id.studyflow.resources.studyflow_mark
import id.studyflow.resources.studyflow_mark_on_dark
import org.jetbrains.compose.resources.painterResource

// Aset dibuat oleh docs/brand/build_brand.py; panduan pemakaian di docs/brand/BRAND_GUIDELINES.md.

/** Ikon aplikasi lengkap (latar teal), mis. untuk ikon jendela desktop. */
@Composable
fun studyFlowAppIcon(): Painter = painterResource(Res.drawable.studyflow_app_icon)

/** Monogram SF; memakai varian putih di tema gelap agar tetap kontras. */
@Composable
fun StudyFlowMark(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Image(
        painter = painterResource(if (dark) Res.drawable.studyflow_mark_on_dark else Res.drawable.studyflow_mark),
        contentDescription = null,
        modifier = modifier,
    )
}
