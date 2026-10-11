package id.studyflow.ui.theme

import androidx.compose.ui.unit.dp

/** Skala spasi (DESIGN_SYSTEM.md §4). */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/** Lebar maksimum konten agar baris teks dan form tidak terlalu panjang di layar lebar. */
object ContentWidth {
    /** Editor dan form. */
    val form = 640.dp

    /** Teks panjang: catatan, rangkuman, jawaban AI. */
    val reading = 720.dp

    /** Daftar dan detail. */
    val list = 840.dp

    /** Kisi dan tata letak dua panel. */
    val wide = 1160.dp
}
