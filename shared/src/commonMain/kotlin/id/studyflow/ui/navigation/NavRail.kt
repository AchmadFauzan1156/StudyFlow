package id.studyflow.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Navigasi untuk lebar menengah (600–839 dp): ikon dan label di sisi kiri, lebih hemat ruang daripada sidebar. */
@Composable
internal fun NavRail(selectedIndex: Int, onSelect: (index: Int) -> Unit) {
    NavigationRail(
        modifier = Modifier.testTag("navRail"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Spacer(Modifier.height(12.dp))
        topLevels.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label, maxLines = 1) },
            )
        }
    }
}
