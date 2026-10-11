package id.studyflow.ui.materials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import id.studyflow.ui.components.InfoCard

@Composable
internal fun NeedsSync(onSync: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard("Sinkronkan dulu", "Mata kuliah ini belum tersimpan di server. Sinkronkan agar materi bisa diunggah.")
        Button(onClick = onSync) { Text("Sinkronkan sekarang") }
    }
}

fun pageLabel(start: Int, end: Int): String = if (start == end) "hal. $start" else "hal. $start–$end"

// ---------- Tab Materi ----------
