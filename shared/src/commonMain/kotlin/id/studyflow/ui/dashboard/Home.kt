package id.studyflow.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.studyflow.data.AppClock
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.TaskRepository
import id.studyflow.data.sync.ConflictRepository
import id.studyflow.domain.DeadlineStatus
import id.studyflow.domain.StudyTask
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.deadlineStatus
import id.studyflow.domain.formatDate
import id.studyflow.domain.toLocalDateTime
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.InfoCard
import id.studyflow.ui.components.SectionHeader
import id.studyflow.ui.components.StatTile
import id.studyflow.ui.components.TaskRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

data class HomeUiState(
    val loading: Boolean = true,
    val courseCount: Int = 0,
    val upcoming: List<StudyTask> = emptyList(),
    val openCount: Int = 0,
    val overdueCount: Int = 0,
    val nowMillis: Long = 0,
    val zone: TimeZone = TimeZone.UTC,
    val auth: AuthState = AuthState.SignedOut,
    val conflicts: Int = 0,
)

/** Jumlah deadline yang ditampilkan di beranda. */
private const val UPCOMING_LIMIT = 8

class HomeViewModel(
    courses: CourseRepository,
    private val tasks: TaskRepository,
    private val clock: AppClock,
    session: SessionManager,
    conflicts: ConflictRepository,
) : ViewModel() {
    val state: StateFlow<HomeUiState> =
        combine(
            courses.observeCount(),
            tasks.observeAll(),
            session.state,
            conflicts.observeCount(),
        ) { courseCount, allTasks, auth, conflictCount ->
            val now = clock.nowMillis()
            val zone = clock.currentZone()
            val open = allTasks.filter { it.status != TaskStatus.DONE }
            HomeUiState(
                loading = false,
                courseCount = courseCount,
                upcoming = open.take(UPCOMING_LIMIT),
                openCount = open.size,
                overdueCount = open.count { deadlineStatus(it.dueAt, it.status, now, zone) == DeadlineStatus.OVERDUE },
                nowMillis = now,
                zone = zone,
                auth = auth,
                conflicts = conflictCount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun toggleDone(task: StudyTask) {
        viewModelScope.launch {
            tasks.setStatus(task.id, if (task.status == TaskStatus.DONE) TaskStatus.TODO else TaskStatus.DONE)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenCourses: () -> Unit, onOpenTask: (StudyTask) -> Unit, onOpenConflicts: () -> Unit, onOpenAuth: () -> Unit) {
    val viewModel = koinViewModel<HomeViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Beranda") }) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val hasCourses = !state.loading && state.courseCount > 0
            if (maxWidth >= TWO_PANE_MIN_DP.dp) {
                // Layar lebar: daftar deadline di kiri, ringkasan dan akun di panel kanan.
                Row(
                    Modifier.widthIn(max = 1160.dp).fillMaxSize().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!state.loading) item { GreetingHeader(state) }
                        if (state.conflicts > 0) item { ConflictCard(state.conflicts, onOpenConflicts) }
                        mainItems(state, onOpenCourses, onOpenTask, viewModel::toggleDone)
                    }
                    Column(
                        Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccountCard(state.auth, onOpenAuth)
                        if (hasCourses) {
                            SectionHeader("Ringkasan")
                            StatTilesPanel(state)
                        }
                    }
                }
            } else {
                // Layar sempit: satu kolom, berurutan dari atas ke bawah.
                val compact = maxWidth < COMPACT_MAX_DP.dp
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!state.loading) item { GreetingHeader(state) }
                    item { AccountCard(state.auth, onOpenAuth) }
                    if (state.conflicts > 0) item { ConflictCard(state.conflicts, onOpenConflicts) }
                    if (hasCourses) item { StatTiles(state, compact) }
                    mainItems(state, onOpenCourses, onOpenTask, viewModel::toggleDone)
                }
            }
        }
    }
}

/** Lebar minimum area konten (dp) untuk menampilkan beranda dalam dua panel. */
private const val TWO_PANE_MIN_DP = 760

/** Di bawah lebar ini (dp) kotak ringkasan memakai label pendek. */
private const val COMPACT_MAX_DP = 480

private fun LazyListScope.mainItems(
    state: HomeUiState,
    onOpenCourses: () -> Unit,
    onOpenTask: (StudyTask) -> Unit,
    onToggleDone: (StudyTask) -> Unit,
) {
    if (state.loading) return
    if (state.courseCount == 0) {
        item {
            EmptyState(
                title = "Belum ada mata kuliah",
                message = "Mulai dengan menambahkan mata kuliah, lalu isi catatan dan deadline tugas.",
                action = { Button(onClick = onOpenCourses) { Text("Tambah mata kuliah") } },
                icon = Icons.Outlined.Create,
            )
        }
        return
    }
    item {
        SectionHeader(
            "Deadline terdekat",
            trailing = if (state.openCount > state.upcoming.size) "${state.upcoming.size} dari ${state.openCount}" else null,
        )
    }
    if (state.upcoming.isEmpty()) {
        item {
            EmptyState(
                title = "Tidak ada tugas terbuka",
                message = "Tambahkan deadline dari tab Tugas pada mata kuliah.",
                icon = Icons.Outlined.CheckCircle,
            )
        }
    }
    items(state.upcoming, key = { it.id }) { task ->
        TaskRow(
            task = task,
            nowMillis = state.nowMillis,
            zone = state.zone,
            showCourse = true,
            onToggleDone = { onToggleDone(task) },
            onClick = { onOpenTask(task) },
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun GreetingHeader(state: HomeUiState) {
    val now = toLocalDateTime(state.nowMillis, state.zone)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(greeting(now.hour), style = MaterialTheme.typography.headlineSmall)
        Text(formatDate(now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConflictCard(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            "$count perubahan bentrok dengan perangkat lain. Ketuk untuk memilih versi.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Tiga kotak sejajar; [compact] memakai label pendek agar muat di layar HP. */
@Composable
private fun StatTiles(state: HomeUiState, compact: Boolean) {
    val overdueAccent = if (state.overdueCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        StatTile(if (compact) "Kuliah" else "Mata kuliah", state.courseCount.toString(), Modifier.weight(1f))
        StatTile(if (compact) "Tugas" else "Tugas terbuka", state.openCount.toString(), Modifier.weight(1f))
        StatTile("Terlambat", state.overdueCount.toString(), Modifier.weight(1f), accent = overdueAccent)
    }
}

/** Panel samping: dua kotak sejajar dan satu kotak penuh di bawahnya. */
@Composable
private fun StatTilesPanel(state: HomeUiState) {
    val overdueAccent = if (state.overdueCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Mata kuliah", state.courseCount.toString(), Modifier.weight(1f))
        StatTile("Tugas terbuka", state.openCount.toString(), Modifier.weight(1f))
    }
    StatTile("Terlambat", state.overdueCount.toString(), Modifier.fillMaxWidth(), accent = overdueAccent)
}

private fun greeting(hour: Int): String = when (hour) {
    in 4..10 -> "Selamat pagi"
    in 11..14 -> "Selamat siang"
    in 15..17 -> "Selamat sore"
    else -> "Selamat malam"
}

@Composable
private fun AccountCard(auth: AuthState, onOpenAuth: () -> Unit) {
    when (auth) {
        is AuthState.SignedIn -> InfoCard(
            title = if (auth.sessionExpired) "Sesi berakhir" else "Tersinkron dengan akun",
            message = if (auth.sessionExpired) {
                "Masuk lagi di Pengaturan untuk melanjutkan sinkronisasi. Perubahan tetap tersimpan di perangkat."
            } else {
                "${auth.email ?: "Akun"} · perubahan tersimpan di perangkat dan dikirim otomatis saat online."
            },
        )
        AuthState.SignedOut -> InfoCard(
            title = "Mode lokal",
            message = "Data tersimpan di perangkat ini dan tetap bisa dipakai tanpa internet. " +
                "Ketuk untuk masuk dan menyinkronkan antar perangkat.",
            modifier = Modifier.clickable(onClick = onOpenAuth),
        )
        AuthState.Unconfigured -> InfoCard(
            title = "Mode lokal",
            message = "Data tersimpan di perangkat ini dan tetap bisa dipakai tanpa internet.",
        )
    }
}
