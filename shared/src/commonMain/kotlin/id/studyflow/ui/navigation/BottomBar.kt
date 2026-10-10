package id.studyflow.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Navigasi bawah untuk layar sempit. */
@Composable
internal fun BottomBar(selectedIndex: Int, onSelect: (index: Int) -> Unit) {
    NavigationBar(Modifier.testTag("bottomBar")) {
        topLevels.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label, maxLines = 1) },
            )
        }
    }
}
