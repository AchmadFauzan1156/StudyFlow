package id.studyflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
import id.studyflow.data.AppClock
import id.studyflow.domain.Course
import id.studyflow.ui.account.AuthScreen
import id.studyflow.ui.account.ConflictsScreen
import id.studyflow.ui.account.ImportLocalDialog
import id.studyflow.ui.courses.CourseDetailScreen
import id.studyflow.ui.courses.CoursesScreen
import id.studyflow.ui.courses.CoursesViewModel
import id.studyflow.ui.dashboard.HomeScreen
import id.studyflow.ui.materials.AskScreen
import id.studyflow.ui.materials.DocumentScreen
import id.studyflow.ui.materials.SourceScreen
import id.studyflow.ui.notes.NoteEditorScreen
import id.studyflow.ui.progress.ProgressScreen
import id.studyflow.ui.settings.SettingsScreen
import id.studyflow.ui.study.FlashcardsScreen
import id.studyflow.ui.study.QuizScreen
import id.studyflow.ui.study.StudyNoteScreen
import id.studyflow.ui.study.SummaryScreen
import id.studyflow.ui.tasks.TaskEditorScreen
import id.studyflow.ui.theme.StudyFlowTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.reflect.KClass

private data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)

private val topLevels = listOf(
    TopLevel("Beranda", Icons.Outlined.Home, HomeRoute, HomeRoute::class),
    TopLevel("Mata Kuliah", Icons.AutoMirrored.Outlined.List, CoursesRoute, CoursesRoute::class),
    TopLevel("Progres", Icons.Outlined.CheckCircle, ProgressRoute, ProgressRoute::class),
    TopLevel("Pengaturan", Icons.Outlined.Settings, SettingsRoute, SettingsRoute::class),
)

/** Jeda sebelum daftar mata kuliah menutup, agar tidak berkedip saat kursor lewat sekilas di luar. */
private const val HOVER_CLOSE_DELAY_MS = 250L

/** Posisi "Mata Kuliah" di [topLevels]. */
private const val COURSES_INDEX = 1

private val shortcutKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four)

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

        importOffer?.let { count ->
            ImportLocalDialog(count, onImport = appViewModel::acceptImport, onLater = appViewModel::declineImport)
        }

        // Pintasan keyboard: Ctrl+1..4 berpindah antar menu utama.
        val rootFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val index = shortcutKeys.indexOf(event.key)
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && index in topLevels.indices) {
                        lastTopLevel = index
                        navController.navigateTopLevel(topLevels[index].route)
                        true
                    } else {
                        false
                    }
                },
        ) {
            if (maxWidth >= WIDE_LAYOUT_MIN_DP.dp) {
                Row(Modifier.fillMaxSize()) {
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
                        var coursesExpanded by remember { mutableStateOf(false) }
                        LaunchedEffect(backStackEntry) {
                            val entry = backStackEntry ?: return@LaunchedEffect
                            when {
                                entry.destination.hasRoute(CourseDetailRoute::class) ->
                                    openCourseId = entry.toRoute<CourseDetailRoute>().courseId
                                topLevels.any { entry.destination.hasRoute(it.routeClass) } -> openCourseId = null
                            }
                        }
                        // Daftar terbuka selama kursor berada di menu atau daftarnya, dan menutup saat kursor pergi.
                        val coursesHover = remember { MutableInteractionSource() }
                        val coursesHovered by coursesHover.collectIsHoveredAsState()
                        LaunchedEffect(coursesHovered) {
                            if (!coursesHovered) delay(HOVER_CLOSE_DELAY_MS)
                            coursesExpanded = coursesHovered
                        }
                        topLevels.forEachIndexed { index, item ->
                            val isCourses = index == COURSES_INDEX
                            Column(if (isCourses) Modifier.hoverable(coursesHover) else Modifier) {
                                NavigationDrawerItem(
                                    label = { Text(item.label, maxLines = 1, softWrap = false) },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    badge = {
                                        if (isCourses) {
                                            Icon(
                                                if (coursesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                                contentDescription = if (coursesExpanded) "Tutup daftar" else "Buka daftar",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    // Saat daftar terbuka, sorotan pindah ke mata kuliah yang sedang dibuka.
                                    selected = index == selectedIndex && !(isCourses && coursesExpanded && openCourseId != null),
                                    onClick = {
                                        lastTopLevel = index
                                        navController.navigateTopLevel(item.route)
                                    },
                                    // Jarak vertikal agar sorotan hover antar menu tidak saling menempel.
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                                )
                                if (isCourses) {
                                    AnimatedVisibility(coursesExpanded) {
                                        CourseSubList(
                                            courses = courses.orEmpty(),
                                            selectedId = openCourseId,
                                            onOpen = { id ->
                                                lastTopLevel = COURSES_INDEX
                                                navController.navigateTopLevel(CoursesRoute)
                                                navController.navigate(CourseDetailRoute(id)) {
                                                    popUpTo(CoursesRoute)
                                                    launchSingleTop = true
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    VerticalDivider()
                    StudyFlowNavHost(navController, Modifier.weight(1f))
                }
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar(Modifier.testTag("bottomBar")) {
                            topLevels.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = index == selectedIndex,
                                    onClick = {
                                        lastTopLevel = index
                                        navController.navigateTopLevel(item.route)
                                    },
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
                    .clickable { onOpen(course.id) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
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
                onOpenConflicts = { navController.navigate(ConflictsRoute) },
                onOpenAuth = { navController.navigate(AuthRoute) },
            )
        }
        composable<CoursesRoute> {
            CoursesScreen(onOpenCourse = { navController.navigate(CourseDetailRoute(it)) })
        }
        composable<ProgressRoute> {
            ProgressScreen(
                onOpenAttempt = { quizId, attemptId -> navController.navigate(QuizRoute(quizId, attemptId)) },
                onOpenNote = { navController.navigate(StudyNoteRoute(it)) },
                onOpenFlashcards = { navController.navigate(FlashcardsRoute) },
            )
        }
        composable<FlashcardsRoute> { FlashcardsScreen(onBack = { navController.popBackStack() }) }
        composable<SettingsRoute> {
            SettingsScreen(
                onOpenAuth = { navController.navigate(AuthRoute) },
                onOpenConflicts = { navController.navigate(ConflictsRoute) },
            )
        }
        composable<AuthRoute> { AuthScreen(onDone = { navController.popBackStack() }) }
        composable<ConflictsRoute> {
            ConflictsScreen(zone = koinInject<AppClock>().currentZone(), onBack = { navController.popBackStack() })
        }
        composable<CourseDetailRoute> { entry ->
            val route = entry.toRoute<CourseDetailRoute>()
            CourseDetailScreen(
                courseId = route.courseId,
                initialTab = route.initialTab,
                onBack = { navController.popBackStack() },
                onOpenNote = { noteId -> navController.navigate(NoteEditorRoute(route.courseId, noteId)) },
                onOpenTask = { taskId -> navController.navigate(TaskEditorRoute(route.courseId, taskId)) },
                onOpenStudy = { noteId -> navController.navigate(StudyNoteRoute(noteId)) },
                onOpenDocument = { navController.navigate(DocumentRoute(it)) },
                onOpenAsk = { name -> navController.navigate(AskRoute(route.courseId, name)) },
            )
        }
        composable<DocumentRoute> { entry ->
            DocumentScreen(entry.toRoute<DocumentRoute>().documentId, onBack = { navController.popBackStack() })
        }
        composable<AskRoute> { entry ->
            val route = entry.toRoute<AskRoute>()
            AskScreen(
                route.courseId,
                route.courseName,
                onBack = { navController.popBackStack() },
                onOpenSource = { navController.navigate(SourceRoute(it)) },
            )
        }
        composable<SourceRoute> { entry ->
            SourceScreen(entry.toRoute<SourceRoute>().chunkId, onBack = { navController.popBackStack() })
        }
        composable<StudyNoteRoute> { entry ->
            val route = entry.toRoute<StudyNoteRoute>()
            StudyNoteScreen(
                noteId = route.noteId,
                zone = koinInject<AppClock>().currentZone(),
                onBack = { navController.popBackStack() },
                onOpenSummary = { navController.navigate(SummaryRoute(it)) },
                onOpenQuiz = { navController.navigate(QuizRoute(it)) },
            )
        }
        composable<SummaryRoute> { entry ->
            SummaryScreen(entry.toRoute<SummaryRoute>().summaryId, koinInject<AppClock>().currentZone()) {
                navController.popBackStack()
            }
        }
        composable<QuizRoute> { entry ->
            val route = entry.toRoute<QuizRoute>()
            QuizScreen(route.quizId, route.attemptId, onBack = { navController.popBackStack() })
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

private fun NavDestination?.isInHierarchy(item: TopLevel): Boolean = this?.hierarchy?.any { it.hasRoute(item.routeClass) } == true

private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
