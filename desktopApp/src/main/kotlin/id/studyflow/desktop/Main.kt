package id.studyflow.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import id.studyflow.di.desktopPlatformModule
import id.studyflow.di.initKoin
import id.studyflow.ui.App
import id.studyflow.ui.theme.studyFlowAppIcon
import java.util.Locale

fun main() {
    // Bahasa produk Indonesia: komponen bawaan (date picker, dsb.) mengikuti locale JVM.
    Locale.setDefault(Locale.forLanguageTag("id-ID"))
    initKoin(desktopPlatformModule())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "StudyFlow",
            icon = studyFlowAppIcon(),
            state = rememberWindowState(size = DpSize(1120.dp, 760.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(420, 560)
            App()
        }
    }
}
