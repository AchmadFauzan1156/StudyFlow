package id.studyflow.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/** Pesan untuk kode status/kesalahan dokumen dari server. */
fun documentErrorMessage(code: String?): String = when (code) {
    "pdf_no_text" -> "PDF tidak memiliki teks yang bisa dibaca (kemungkinan hasil scan). OCR belum didukung."
    "pdf_encrypted" -> "PDF terkunci password."
    "pdf_invalid" -> "PDF rusak atau tidak dapat dibaca."
    "extract_timeout" -> "Ekstraksi teks terlalu lama. Coba PDF yang lebih kecil."
    "pdf_too_heavy" -> "PDF terlalu berat untuk diproses server. Pecah menjadi beberapa berkas yang lebih kecil."
    "provider_unavailable" -> "Layanan embedding sedang sibuk. Kuota tidak dihitung; unggah ulang nanti."
    "invalid_output", "provider_rejected" -> "Pemrosesan gagal di layanan AI. Unggah ulang nanti."
    else -> "Pemrosesan gagal."
}

/** Polling berjeda (2–10 detik) selama [active] mengembalikan true; berhenti saat ViewModel ditutup. */
internal fun ViewModel.pollWhile(active: () -> Boolean, refresh: suspend () -> Unit): Job = viewModelScope.launch {
    var interval = 2_000L
    while (active()) {
        delay(interval)
        refresh()
        interval = min(interval + 1_000L, 10_000L)
    }
}

// ---------- Daftar materi mata kuliah ----------
