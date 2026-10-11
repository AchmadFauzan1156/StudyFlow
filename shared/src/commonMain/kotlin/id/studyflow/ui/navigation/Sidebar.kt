package id.studyflow.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import id.studyflow.domain.Course
import id.studyflow.ui.CourseDetailRoute
import id.studyflow.ui.courses.CoursesViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sidebar layar lebar: menu utama dan daftar mata kuliah. Daftar dibuka/ditutup lewat tombol panah (bisa dengan
 * mouse, sentuhan, atau keyboard) dan terbuka sendiri saat sebuah mata kuliah sedang dibuka.
 */
@Composable
internal fun Sidebar(
    selectedIndex: Int,
    backStackEntry: NavBackStackEntry?,
    onSelect: (index: Int) -> Unit,
    onOpenCourse: (courseId: String) -> Unit,
) {
    PermanentDrawerSheet(
        modifier = Modifier.width(272.dp).fillMaxHeight().testTag("sidebar"),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text("S", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
            Text("StudyFlow", style = MaterialTheme.typography.titleLarge)
        }
        val courses by koinViewModel<CoursesViewModel>().courses.collectAsStateWithLifecycle()
        // Mata kuliah yang sedang dibuka, agar tetap tersorot saat masuk ke catatan atau tugasnya.
        var openCourseId by rememberSaveable { mutableStateOf<String?>(null) }
        var coursesExpanded by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(backStackEntry) {
            val entry = backStackEntry ?: return@LaunchedEffect
            when {
                entry.destination.hasRoute(CourseDetailRoute::class) -> {
                    openCourseId = entry.toRoute<CourseDetailRoute>().courseId
                    coursesExpanded = true
                }
                topLevels.any { entry.destination.hasRoute(it.routeClass) } -> openCourseId = null
            }
        }
        topLevels.forEachIndexed { index, item ->
            val isCourses = index == COURSES_INDEX
            Column {
                NavigationDrawerItem(
                    label = { Text(item.label, maxLines = 1, softWrap = false) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    badge = {
                        if (isCourses) {
                            IconButton(onClick = { coursesExpanded = !coursesExpanded }) {
                                Icon(
                                    if (coursesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (coursesExpanded) {
                                        "Sembunyikan daftar mata kuliah"
                                    } else {
                                        "Tampilkan daftar mata kuliah"
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    // Saat daftar terbuka, sorotan pindah ke mata kuliah yang sedang dibuka.
                    selected = index == selectedIndex && !(isCourses && coursesExpanded && openCourseId != null),
                    onClick = { onSelect(index) },
                    // Jarak vertikal agar sorotan hover antar menu tidak saling menempel.
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                )
                if (isCourses) {
                    AnimatedVisibility(coursesExpanded) {
                        CourseSubList(courses = courses.orEmpty(), selectedId = openCourseId, onOpen = onOpenCourse)
                    }
                }
            }
        }
    }
}

/** Daftar mata kuliah di bawah menu "Mata Kuliah" pada sidebar; isinya terbuka di panel kanan. */
@Composable
private fun CourseSubList(courses: List<Course>, selectedId: String?, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            // Indentasi dipilih agar teks nama sejajar dengan label menu di atasnya.
            .padding(start = 48.dp, end = 12.dp, top = 3.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (courses.isEmpty()) {
            Text(
                "Belum ada mata kuliah",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        courses.forEach { course ->
            val selected = course.id == selectedId
            Text(
                course.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(role = Role.Button) { onOpen(course.id) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
