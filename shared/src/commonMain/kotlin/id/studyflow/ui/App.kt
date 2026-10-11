package id.studyflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import id.studyflow.ui.account.ImportLocalDialog
import id.studyflow.ui.navigation.BottomBar
import id.studyflow.ui.navigation.COURSES_INDEX
import id.studyflow.ui.navigation.NavRail
import id.studyflow.ui.navigation.Sidebar
import id.studyflow.ui.navigation.StudyFlowNavHost
import id.studyflow.ui.navigation.isInHierarchy
import id.studyflow.ui.navigation.navigateTopLevel
import id.studyflow.ui.navigation.openCourseFromSidebar
import id.studyflow.ui.navigation.topLevelShortcuts
import id.studyflow.ui.navigation.topLevels
import id.studyflow.ui.theme.StudyFlowTheme
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Lebar minimum (dp) untuk berpindah dari bottom navigation ke navigation rail. */
private const val MEDIUM_LAYOUT_MIN_DP = 600

/** Lebar minimum (dp) untuk sidebar lengkap dengan daftar mata kuliah. */
private const val EXPANDED_LAYOUT_MIN_DP = 840

private const val SNACKBAR_MAX_WIDTH_DP = 480

/** Tinggi FAB (56 dp) ditambah jaraknya dari tepi bawah (16 dp) dan sedikit ruang. */
private const val FAB_CLEARANCE_DP = 80

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

        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val actions = remember(navController) {
            AppActions(
                openAccount = { navController.navigate(AuthRoute) },
                showMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            )
        }

        importOffer?.let { count ->
            ImportLocalDialog(count, onImport = appViewModel::acceptImport, onLater = appViewModel::declineImport)
        }

        // Fokus di root agar pintasan Ctrl+1..4 langsung aktif.
        val rootFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }
        CompositionLocalProvider(LocalAppActions provides actions) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .focusRequester(rootFocus)
                    .focusable()
                    .topLevelShortcuts(selectTopLevel),
            ) {
                if (maxWidth >= EXPANDED_LAYOUT_MIN_DP.dp) {
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
                        ContentWithSnackbar(navController, snackbar, Modifier.weight(1f))
                    }
                } else if (maxWidth >= MEDIUM_LAYOUT_MIN_DP.dp) {
                    Row(Modifier.fillMaxSize()) {
                        NavRail(selectedIndex, selectTopLevel)
                        VerticalDivider()
                        ContentWithSnackbar(navController, snackbar, Modifier.weight(1f))
                    }
                } else {
                    Scaffold(
                        bottomBar = { BottomBar(selectedIndex, selectTopLevel) },
                        // Di atas area FAB layar di dalamnya, agar tombol tambah tetap bisa ditekan.
                        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = FAB_CLEARANCE_DP.dp)) },
                    ) { padding ->
                        StudyFlowNavHost(navController, Modifier.padding(padding).consumeWindowInsets(padding))
                    }
                }
            }
        }
    }
}

/** Area konten layar lebar/menengah; Snackbar di kiri bawah dan dibatasi lebarnya agar tidak menutupi FAB di kanan bawah. */
@Composable
private fun ContentWithSnackbar(navController: NavHostController, snackbar: SnackbarHostState, modifier: Modifier) {
    Box(modifier) {
        StudyFlowNavHost(navController, Modifier.fillMaxSize())
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomStart).padding(24.dp).widthIn(max = SNACKBAR_MAX_WIDTH_DP.dp),
        )
    }
}
