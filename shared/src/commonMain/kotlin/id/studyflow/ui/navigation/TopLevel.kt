package id.studyflow.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import id.studyflow.ui.CourseDetailRoute
import id.studyflow.ui.CoursesRoute
import id.studyflow.ui.HomeRoute
import id.studyflow.ui.ProgressRoute
import id.studyflow.ui.SettingsRoute
import kotlin.reflect.KClass

internal data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)

internal val topLevels = listOf(
    TopLevel("Beranda", Icons.Outlined.Home, HomeRoute, HomeRoute::class),
    TopLevel("Mata Kuliah", Icons.AutoMirrored.Outlined.List, CoursesRoute, CoursesRoute::class),
    TopLevel("Progres", Icons.Outlined.CheckCircle, ProgressRoute, ProgressRoute::class),
    TopLevel("Pengaturan", Icons.Outlined.Settings, SettingsRoute, SettingsRoute::class),
)

/** Posisi "Mata Kuliah" di [topLevels]. */
internal const val COURSES_INDEX = 1

internal fun NavDestination?.isInHierarchy(item: TopLevel): Boolean = this?.hierarchy?.any { it.hasRoute(item.routeClass) } == true

internal fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Membuka detail mata kuliah dari sidebar, di atas layar daftar Mata Kuliah. */
internal fun NavHostController.openCourseFromSidebar(courseId: String) {
    navigateTopLevel(CoursesRoute)
    navigate(CourseDetailRoute(courseId)) {
        popUpTo(CoursesRoute)
        launchSingleTop = true
    }
}
