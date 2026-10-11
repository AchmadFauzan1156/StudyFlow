package id.studyflow.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.auth.AuthState
import id.studyflow.data.repository.EntityType
import id.studyflow.data.sync.ConflictItem
import id.studyflow.data.sync.ConflictSide
import id.studyflow.data.sync.SyncPhase
import id.studyflow.domain.formatDateTime
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.GroupLabel
import id.studyflow.ui.components.InfoCard
import id.studyflow.ui.components.heading
import id.studyflow.ui.theme.ContentWidth
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

private const val MIN_PASSWORD_LENGTH = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(onDone: () -> Unit) {
    val viewModel = koinViewModel<AccountViewModel>()
    val account by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val signUp = mode == 1
    val canSubmit = !form.busy && email.contains('@') && password.length >= (if (signUp) MIN_PASSWORD_LENGTH else 1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Akun StudyFlow", Modifier.heading()) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearForm()
                        onDone()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (account.auth is AuthState.Unconfigured) {
                    InfoCard(
                        title = "Server belum dikonfigurasi",
                        message = "Aplikasi ini dibangun tanpa alamat server, jadi hanya mode lokal yang tersedia. " +
                            "Lihat README bagian konfigurasi akun.",
                    )
                    return@Column
                }
                SecondaryTabRow(selectedTabIndex = mode) {
                    Tab(selected = mode == 0, onClick = {
                        mode = 0
                        viewModel.clearForm()
                    }, text = { Text("Masuk") })
                    Tab(selected = mode == 1, onClick = {
                        mode = 1
                        viewModel.clearForm()
                    }, text = { Text("Daftar") })
                }
                Text(
                    "Masuk untuk menyinkronkan mata kuliah, catatan, dan tugas antar perangkat. " +
                        "Mode lokal tetap bisa dipakai tanpa akun.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Kata sandi") },
                    supportingText = if (signUp) ({ Text("Minimal $MIN_PASSWORD_LENGTH karakter") }) else null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                form.info?.let { InfoCard(title = "Cek email", message = it) }
                Button(
                    onClick = {
                        if (signUp) viewModel.signUp(email, password, onDone) else viewModel.signIn(email, password, onDone)
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (form.busy) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(if (signUp) "Daftar" else "Masuk")
                }
                if (!account.persistsSession) {
                    Text(
                        "Perangkat ini belum mendukung penyimpanan sesi yang aman, jadi kamu perlu masuk lagi " +
                            "setiap kali aplikasi dibuka.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Bagian "Akun" di layar Pengaturan. */
@Composable
fun AccountSection(onOpenAuth: () -> Unit, onOpenConflicts: () -> Unit, formatTime: (Long) -> String) {
    val viewModel = koinViewModel<AccountViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localCourses by viewModel.localCourses.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupLabel("Akun")
        when (val auth = state.auth) {
            AuthState.Unconfigured -> {
                LabeledValue("Status", "Mode lokal")
                Text(
                    "Aplikasi ini dibangun tanpa alamat server, jadi akun dan sinkronisasi belum tersedia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AuthState.SignedOut -> {
                LabeledValue("Status", "Mode lokal (belum masuk)")
                Button(onClick = onOpenAuth) { Text("Masuk atau daftar") }
            }
            is AuthState.SignedIn -> {
                LabeledValue("Masuk sebagai", auth.email ?: auth.userId)
                if (auth.sessionExpired) {
                    InfoCard("Sesi berakhir", "Masuk lagi untuk melanjutkan sinkronisasi. Perubahan tetap tersimpan.")
                    Button(onClick = onOpenAuth) { Text("Masuk lagi") }
                }
                LabeledValue("Sinkronisasi", syncLabel(state, formatTime))
                LabeledValue(
                    "Perubahan menunggu dikirim",
                    if (state.pendingChanges == 0) "Tidak ada" else "${state.pendingChanges} perubahan",
                )
                if (state.conflicts > 0) {
                    OutlinedButton(onClick = onOpenConflicts) { Text("Tinjau ${state.conflicts} konflik") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::syncNow, enabled = state.sync.phase != SyncPhase.SYNCING) {
                        Text("Sinkronkan sekarang")
                    }
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text("Keluar") }
                }
                if (localCourses > 0) {
                    Text(
                        "Ada $localCourses mata kuliah dari mode lokal di perangkat ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::importLocal) { Text("Impor data mode lokal ke akun") }
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Keluar dari akun?") },
            text = {
                Text(
                    if (state.pendingChanges > 0) {
                        "${state.pendingChanges} perubahan belum terkirim. Perubahan itu tetap tersimpan di perangkat " +
                            "dan akan dikirim saat kamu masuk lagi dengan akun yang sama."
                    } else {
                        "Data akun disembunyikan dan aplikasi kembali ke mode lokal."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text("Keluar") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Batal") } },
        )
    }
}

private fun syncLabel(state: AccountUiState, formatTime: (Long) -> String): String = when (state.sync.phase) {
    SyncPhase.SYNCING -> "Sedang menyinkronkan…"
    SyncPhase.OFFLINE -> state.sync.lastError ?: "Offline; akan dicoba lagi otomatis"
    SyncPhase.NEEDS_LOGIN -> "Perlu masuk lagi"
    SyncPhase.IDLE -> state.sync.lastSyncedAt?.let { "Terakhir ${formatTime(it)}" } ?: "Belum pernah"
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ImportLocalDialog(courseCount: Int, onImport: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Impor data mode lokal?") },
        text = {
            Text(
                "Ada $courseCount mata kuliah yang dibuat tanpa akun di perangkat ini. Impor ke akun agar ikut " +
                    "tersinkron? Data mode lokal tidak dihapus, dan impor ulang tidak membuat data ganda.",
            )
        },
        confirmButton = { TextButton(onClick = onImport) { Text("Impor") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Nanti") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(zone: TimeZone, onBack: () -> Unit) {
    val viewModel = koinViewModel<ConflictsViewModel>()
    val items by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Konflik sinkronisasi", Modifier.heading()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState("Tidak ada konflik", "Semua perubahan sudah selaras dengan server.")
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = ContentWidth.list).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "Data ini diubah di perangkat lain sebelum perubahanmu terkirim. Pilih versi yang dipakai; " +
                                "versi lainnya dibuang.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(list, key = { "${it.entityType}:${it.entityId}" }) { item ->
                        ConflictCard(item, zone, onKeepLocal = { viewModel.keepLocal(item) }, onKeepServer = { viewModel.keepServer(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(item: ConflictItem, zone: TimeZone, onKeepLocal: () -> Unit, onKeepServer: () -> Unit) {
    val typeLabel = when (item.entityType) {
        EntityType.COURSE -> "Mata kuliah"
        EntityType.NOTE -> "Catatan"
        EntityType.TASK -> "Tugas"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(typeLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SideView("Versi di perangkat ini", item.local, zone)
            SideView("Versi di server", item.server, zone)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepLocal, enabled = item.local != null) { Text("Pakai versi saya") }
                OutlinedButton(onClick = onKeepServer) { Text("Pakai versi server") }
            }
        }
    }
}

@Composable
private fun SideView(label: String, side: ConflictSide?, zone: TimeZone) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            side == null -> Text("(tidak ada)", style = MaterialTheme.typography.bodyMedium)
            side.deleted -> Text("Dihapus", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else -> {
                Text(side.title, style = MaterialTheme.typography.titleSmall)
                side.dueAt?.let {
                    Text(
                        formatDateTime(it, zone) + if (side.done) " · selesai" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (side.body.isNotBlank()) {
                    Text(side.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
