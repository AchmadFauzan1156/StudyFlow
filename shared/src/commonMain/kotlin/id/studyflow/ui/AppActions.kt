package id.studyflow.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Aksi tingkat aplikasi untuk layar yang berada jauh di dalam navigasi, agar callback tidak perlu diteruskan
 * lewat banyak lapisan. Disediakan oleh [App].
 */
class AppActions(
    /** Membuka halaman masuk/daftar. */
    val openAccount: () -> Unit,
    /** Menampilkan pesan singkat (Snackbar) yang tetap terlihat walau layar ditutup. */
    val showMessage: (String) -> Unit,
)

val LocalAppActions = staticCompositionLocalOf { AppActions(openAccount = {}, showMessage = {}) }
