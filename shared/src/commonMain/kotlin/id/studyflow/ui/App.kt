package id.studyflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import id.studyflow.ui.account.ImportLocalDialog
import id.studyflow.ui.navigation.BottomBar
import id.studyflow.ui.navigation.COURSES_INDEX
import id.studyflow.ui.navigation.Sidebar
import id.studyflow.ui.navigation.StudyFlowNavHost
import id.studyflow.ui.navigation.isInHierarchy
import id.studyflow.ui.navigation.navigateTopLevel
import id.studyflow.ui.navigation.openCourseFromSidebar
import id.studyflow.ui.navigation.topLevelShortcuts
import id.studyflow.ui.navigation.topLevels
import id.studyflow.ui.theme.StudyFlowTheme
import org.koin.compose.viewmodel.koinViewModel

/** Lebar minimum (dp) untuk berpindah dari bottom navigation ke sidebar. */
private const val WIDE_LAYOUT_MIN_DP = 600

@Composable
fun App() {
    val appViewModel = koinViewModel<AppViewModel>()
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val importOffer by appViewModel.importOffer.collectAsStateWithLifecycle()
    StudyFlowTheme(themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val destination = backStackEntry?.destination
        // Layar detail tetap menyorot menu utama terakhir (mis. Mata Kuliah saat membuka detail).
        val currentIndex = topLevels.indexOfFirst { destination.isInHierarchy(it) }
        var lastTopLevel by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(currentIndex) { if (currentIndex >= 0) lastTopLevel = currentIndex }
        val selectedIndex = if (currentIndex >= 0) currentIndex else lastTopLevel
        val selectTopLevel: (Int) -> Unit = { index ->
            lastTopLevel = index
            navController.navigateTopLevel(topLevels[index].route)
        }

        importOffer?.let { count ->
            ImportLocalDialog(count, onImport = appViewModel::acceptImport, onLater = appViewModel::declineImport)
        }

        // Fokus di root agar pintasan Ctrl+1..4 langsung aktif.
        val rootFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(rootFocus)
                .focusable()
                .topLevelShortcuts(selectTopLevel),
        ) {
            if (maxWidth >= WIDE_LAYOUT_MIN_DP.dp) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(
                        selectedIndex = selectedIndex,
                        backStackEntry = backStackEntry,
                        onSelect = selectTopLevel,
                        onOpenCourse = { id ->
                            lastTopLevel = COURSES_INDEX
                            navController.openCourseFromSidebar(id)
                        },
                    )
                    VerticalDivider()
                    StudyFlowNavHost(navController, Modifier.weight(1f))
                }
            } else {
                Scaffold(bottomBar = { BottomBar(selectedIndex, selectTopLevel) }) { padding ->
                    StudyFlowNavHost(navController, Modifier.padding(padding).consumeWindowInsets(padding))
                }
            }
        }
    }
}
