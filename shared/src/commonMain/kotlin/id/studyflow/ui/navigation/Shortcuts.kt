package id.studyflow.ui.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private val shortcutKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four)

/**
 * Pintasan keyboard: Ctrl+1..4 memilih menu utama ke-index di [topLevels].
 *
 * Dipasang di root App, sehingga hanya aktif untuk event yang melewati root. Dialog (mis. form mata kuliah) memakai
 * layer jendela sendiri, jadi pintasan tidak berpindah menu selama dialog terbuka. Apakah angka ikut terketik ke kolom
 * teks ditentukan event KEY_TYPED dari AWT, yang tidak dikirim oleh `performKeyInput` di Compose UI test; perilaku ini
 * hanya bisa diperiksa manual di aplikasi desktop.
 */
internal fun Modifier.topLevelShortcuts(onSelect: (index: Int) -> Unit): Modifier = onPreviewKeyEvent { event ->
    val index = shortcutKeys.indexOf(event.key)
    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && index in topLevels.indices) {
        onSelect(index)
        true
    } else {
        false
    }
}
