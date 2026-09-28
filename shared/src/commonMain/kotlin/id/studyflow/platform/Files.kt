package id.studyflow.platform

import androidx.compose.runtime.Composable

/** Berkas yang dipilih pengguna (isi dibaca utuh; ukuran dibatasi pemanggil). */
class PickedFile(val name: String, val bytes: ByteArray)

/**
 * Mengembalikan fungsi yang membuka pemilih berkas PDF. [onPicked] dipanggil dengan berkas terpilih,
 * atau tidak dipanggil jika pengguna membatalkan.
 */
@Composable
expect fun rememberPdfPicker(onPicked: (PickedFile) -> Unit): () -> Unit

/** Mengembalikan fungsi untuk membuka PDF di aplikasi penampil bawaan. false jika tidak ada penampil. */
@Composable
expect fun rememberPdfOpener(): (bytes: ByteArray, fileName: String) -> Boolean
