package id.studyflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import id.studyflow.ui.courses.CourseDetailScreen
import id.studyflow.ui.courses.CoursesScreen
import id.studyflow.ui.dashboard.HomeScreen
import id.studyflow.ui.notes.NoteEditorScreen
import id.studyflow.ui.progress.ProgressScreen
import id.studyflow.ui.settings.SettingsScreen
import id.studyflow.ui.tasks.TaskEditorScreen
import id.studyflow.ui.theme.StudyFlowTheme
import org.koin.compose.viewmodel.koinViewModel
import kotlin.reflect.KClass

private data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)

private val topLevels = listOf(
    TopLevel("Beranda", Icons.Outlined.Home, HomeRoute, HomeRoute::class),
    TopLevel("Mata Kuliah", Icons.AutoMirrored.Outlined.List, CoursesRoute, CoursesRoute::class),
    TopLevel("Progres", Icons.Outlined.CheckCircle, ProgressRoute, ProgressRoute::class),
    TopLevel("Pengaturan", Icons.Outlined.Settings, SettingsRoute, SettingsRoute::class),
)

/** Lebar minimum (dp) untuk berpindah dari bottom navigation ke sidebar. */
private const val WIDE_LAYOUT_MIN_DP = 600

@Composable
fun App() {
    val appViewModel = koinViewModel<AppViewModel>()
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    StudyFlowTheme(themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val destination = backStackEntry?.destination
        // Layar detail tetap menyorot menu utama terakhir (mis. Mata Kuliah saat membuka detail).
        val currentIndex = topLevels.indexOfFirst { destination.isInHierarchy(it) }
        var lastTopLevel by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(currentIndex) { if (currentIndex >= 0) lastTopLevel = currentIndex }
        val selectedIndex = if (currentIndex >= 0) currentIndex else lastTopLevel

        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (maxWidth >= WIDE_LAYOUT_MIN_DP.dp) {
                Row(Modifier.fillMaxSize()) {
                    PermanentDrawerSheet(
                        modifier = Modifier.width(232.dp).fillMaxHeight(),
                        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            "StudyFlow",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        )
                        topLevels.forEachIndexed { index, item ->
                            NavigationDrawerItem(
                                label = { Text(item.label) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                selected = index == selectedIndex,
                                onClick = { navController.navigateTopLevel(item.route) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    VerticalDivider()
                    StudyFlowNavHost(navController, Modifier.weight(1f))
                }
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            topLevels.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = index == selectedIndex,
                                    onClick = { navController.navigateTopLevel(item.route) },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(item.label, maxLines = 1) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    StudyFlowNavHost(navController, Modifier.padding(padding).consumeWindowInsets(padding))
                }
            }
        }
    }
}

@Composable
private fun StudyFlowNavHost(navController: NavHostController, modifier: Modifier) {
    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenCourses = { navController.navigateTopLevel(CoursesRoute) },
                onOpenTask = { task -> navController.navigate(TaskEditorRoute(task.courseId, task.id)) },
            )
        }
        composable<CoursesRoute> {
            CoursesScreen(onOpenCourse = { navController.navigate(CourseDetailRoute(it)) })
        }
        composable<ProgressRoute> { ProgressScreen() }
        composable<SettingsRoute> { SettingsScreen() }
        composable<CourseDetailRoute> { entry ->
            val route = entry.toRoute<CourseDetailRoute>()
            CourseDetailScreen(
                courseId = route.courseId,
                initialTab = route.initialTab,
                onBack = { navController.popBackStack() },
                onOpenNote = { noteId -> navController.navigate(NoteEditorRoute(route.courseId, noteId)) },
                onOpenTask = { taskId -> navController.navigate(TaskEditorRoute(route.courseId, taskId)) },
            )
        }
        composable<NoteEditorRoute> { entry ->
            val route = entry.toRoute<NoteEditorRoute>()
            NoteEditorScreen(route.courseId, route.noteId, onDone = { navController.popBackStack() })
        }
        composable<TaskEditorRoute> { entry ->
            val route = entry.toRoute<TaskEditorRoute>()
            TaskEditorScreen(route.courseId, route.taskId, onDone = { navController.popBackStack() })
        }
    }
}

private fun NavDestination?.isInHierarchy(item: TopLevel): Boolean =
    this?.hierarchy?.any { it.hasRoute(item.routeClass) } == true

private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
