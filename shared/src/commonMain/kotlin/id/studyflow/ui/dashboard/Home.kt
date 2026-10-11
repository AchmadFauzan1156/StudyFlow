package id.studyflow.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import id.studyflow.domain.AgendaGroup
import id.studyflow.domain.DeadlineStatus
import id.studyflow.domain.StudyTask
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.agendaGroup
import id.studyflow.domain.deadlineStatus
import id.studyflow.domain.formatDate
import id.studyflow.domain.toLocalDateTime
import id.studyflow.ui.components.CountRow
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.GroupLabel
import id.studyflow.ui.components.TaskRow
import id.studyflow.ui.components.heading
import id.studyflow.ui.theme.ContentWidth
import id.studyflow.ui.theme.Spacing
import id.studyflow.ui.theme.tabularNumbers
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

@Composable
fun HomeScreen(onOpenCourses: () -> Unit, onOpenTask: (StudyTask) -> Unit, onOpenConflicts: () -> Unit, onOpenAuth: () -> Unit) {
    val viewModel = koinViewModel<HomeViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val hasCourses = !state.loading && state.courseCount > 0
            if (maxWidth >= TWO_PANE_MIN_DP.dp) {
                // Layar lebar: agenda di kiri, akun dan ringkasan di panel kanan.
                Row(
                    Modifier.widthIn(max = ContentWidth.wide).fillMaxSize().padding(horizontal = Spacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        if (!state.loading) item { HomeHeader(state, accountChip = null) }
                        if (state.conflicts > 0) item { ConflictCard(state.conflicts, onOpenConflicts) }
                        agendaItems(state, onOpenCourses, onOpenTask, viewModel::toggleDone)
                    }
                    Column(
                        Modifier.width(SIDE_PANEL_WIDTH_DP.dp).verticalScroll(rememberScrollState()).padding(vertical = Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                    ) {
                        AccountPanel(state.auth, onOpenAuth)
                        if (hasCourses) OverviewPanel(state, onOpenCourses)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.widthIn(max = ContentWidth.list).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (!state.loading) item { HomeHeader(state, accountChip = { AccountChip(state.auth, onOpenAuth) }) }
                    if (state.conflicts > 0) item { ConflictCard(state.conflicts, onOpenConflicts) }
                    agendaItems(state, onOpenCourses, onOpenTask, viewModel::toggleDone)
                }
            }
        }
    }
}

/** Lebar minimum area konten (dp) untuk menampilkan beranda dalam dua panel. */
private const val TWO_PANE_MIN_DP = 760

private const val SIDE_PANEL_WIDTH_DP = 300

/** Agenda: tugas terbuka dikelompokkan menurut waktu (Terlambat, Hari ini, 7 hari ke depan, Nanti). */
private fun LazyListScope.agendaItems(
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
                icon = Icons.AutoMirrored.Outlined.List,
            )
        }
        return
    }
    if (state.upcoming.isEmpty()) {
        item {
            EmptyState(
                title = "Tidak ada tugas terbuka",
                message = "Semua tugas sudah selesai. Tambahkan deadline baru dari tab Tugas di mata kuliah.",
                icon = Icons.Outlined.CheckCircle,
            )
        }
        return
    }
    val groups = state.upcoming.groupBy { agendaGroup(it.dueAt, state.nowMillis, state.zone) }
    AgendaGroup.entries.forEach { group ->
        val tasks = groups[group] ?: return@forEach
        item(key = "group-${group.name}") {
            GroupLabel(
                group.label,
                count = tasks.size,
                color = if (group == AgendaGroup.OVERDUE) MaterialTheme.colorScheme.error else Color.Unspecified,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        items(tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                nowMillis = state.nowMillis,
                zone = state.zone,
                showCourse = true,
                onToggleDone = { onToggleDone(task) },
                onClick = { onOpenTask(task) },
                modifier = Modifier.animateItem(),
                showStatus = false,
            )
        }
    }
    if (state.openCount > state.upcoming.size) {
        item {
            Text(
                "Menampilkan ${state.upcoming.size} dari ${state.openCount} tugas terbuka yang paling dekat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md, start = Spacing.sm),
            )
        }
    }
}

/** Satu judul halaman (sapaan), tanggal, dan ringkasan satu kalimat; penanda akun di kanan pada layar sempit. */
@Composable
private fun HomeHeader(state: HomeUiState, accountChip: (@Composable () -> Unit)?) {
    val now = toLocalDateTime(state.nowMillis, state.zone)
    Column(Modifier.padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDate(now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            accountChip?.invoke()
        }
        Text(greeting(now.hour), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.heading())
        if (state.courseCount > 0) SummaryLine(state)
    }
}

/** "4 tugas terbuka · 1 terlambat"; bagian terlambat berwarna error agar langsung terlihat. */
@Composable
private fun SummaryLine(state: HomeUiState) {
    val error = MaterialTheme.colorScheme.error
    val text = buildAnnotatedString {
        if (state.openCount == 0) {
            append("Semua tugas selesai")
        } else {
            append("${state.openCount} tugas terbuka")
            if (state.overdueCount > 0) {
                append(" · ")
                withStyle(SpanStyle(color = error, fontWeight = FontWeight.SemiBold)) { append("${state.overdueCount} terlambat") }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyLarge.tabularNumbers(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConflictCard(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            "$count perubahan bentrok dengan perangkat lain. Ketuk untuk memilih versi.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

/** Panel kanan: jumlah per jenis dalam baris ringkas, dengan tautan ke daftar mata kuliah. */
@Composable
private fun OverviewPanel(state: HomeUiState, onOpenCourses: () -> Unit) {
    Column {
        GroupLabel("Ringkasan")
        CountRow("Mata kuliah", state.courseCount)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        CountRow("Tugas terbuka", state.openCount)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        CountRow("Terlambat", state.overdueCount, alert = state.overdueCount > 0)
        TextButton(onClick = onOpenCourses, modifier = Modifier.padding(top = Spacing.sm)) { Text("Lihat mata kuliah") }
    }
}

private fun greeting(hour: Int): String = when (hour) {
    in 4..10 -> "Selamat pagi"
    in 11..14 -> "Selamat siang"
    in 15..17 -> "Selamat sore"
    else -> "Selamat malam"
}

private data class AccountSummary(val label: String, val message: String, val action: String?, val dot: @Composable () -> Color)

private fun accountSummary(auth: AuthState): AccountSummary = when (auth) {
    is AuthState.SignedIn -> if (auth.sessionExpired) {
        AccountSummary(
            "Sesi berakhir",
            "Masuk lagi untuk melanjutkan sinkronisasi. Perubahan tetap tersimpan di perangkat.",
            "Masuk lagi",
        ) { MaterialTheme.colorScheme.error }
    } else {
        AccountSummary(
            "Tersinkron",
            "${auth.email ?: "Akun"} · perubahan tersimpan di perangkat dan dikirim otomatis saat online.",
            null,
        ) { MaterialTheme.colorScheme.primary }
    }
    AuthState.SignedOut -> AccountSummary(
        "Mode lokal",
        "Data tersimpan di perangkat ini dan tetap bisa dipakai tanpa internet. Masuk untuk menyinkronkan antar perangkat.",
        "Masuk atau daftar",
    ) { MaterialTheme.colorScheme.outline }
    AuthState.Unconfigured -> AccountSummary(
        "Mode lokal",
        "Data tersimpan di perangkat ini dan tetap bisa dipakai tanpa internet.",
        null,
    ) { MaterialTheme.colorScheme.outline }
}

/** Penanda akun kecil di kepala Beranda (layar sempit); bisa diketuk jika ada aksi (masuk). */
@Composable
private fun AccountChip(auth: AuthState, onOpenAuth: () -> Unit) {
    val summary = accountSummary(auth)
    val clickable = summary.action != null
    Row(
        Modifier.clip(MaterialTheme.shapes.small)
            .then(if (clickable) Modifier.clickable(onClickLabel = summary.action, onClick = onOpenAuth) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .heightIn(min = 32.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(8.dp).background(summary.dot(), CircleShape))
        Text(summary.label, style = MaterialTheme.typography.labelLarge)
        if (clickable) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** Panel akun di kanan (layar lebar): status, penjelasan, dan tombol bila ada aksi. */
@Composable
private fun AccountPanel(auth: AuthState, onOpenAuth: () -> Unit) {
    val summary = accountSummary(auth)
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.size(8.dp).background(summary.dot(), CircleShape))
            Text(summary.label, style = MaterialTheme.typography.titleSmall)
        }
        Text(summary.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (summary.action != null) {
            OutlinedButton(onClick = onOpenAuth) { Text(summary.action) }
        }
    }
}
