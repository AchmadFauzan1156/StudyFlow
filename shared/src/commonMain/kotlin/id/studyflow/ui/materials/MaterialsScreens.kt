package id.studyflow.ui.materials

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.remote.DocumentDto
import id.studyflow.data.remote.MessageDto
import id.studyflow.data.remote.SourceDto
import id.studyflow.platform.rememberPdfOpener
import id.studyflow.platform.rememberPdfPicker
import id.studyflow.ui.components.ConfirmDeleteDialog
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.InfoCard
import id.studyflow.ui.study.DemoLabel
import id.studyflow.ui.study.Label
import id.studyflow.ui.study.OfflineNote
import id.studyflow.ui.study.StudyScaffold
import id.studyflow.ui.study.jobErrorMessage
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
private fun NeedsAccount(title: String) = InfoCard(title, "Fitur ini membutuhkan akun dan koneksi internet. Masuk dari menu Pengaturan.")

@Composable
private fun NeedsSync(onSync: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard("Sinkronkan dulu", "Mata kuliah ini belum tersimpan di server. Sinkronkan agar materi bisa diunggah.")
        Button(onClick = onSync) { Text("Sinkronkan sekarang") }
    }
}

fun pageLabel(start: Int, end: Int): String = if (start == end) "hal. $start" else "hal. $start–$end"

// ---------- Tab Materi ----------

@Composable
fun MaterialsTab(courseId: String, onOpenDocument: (String) -> Unit, modifier: Modifier) {
    val session = koinInject<SessionManager>()
    val auth by session.state.collectAsStateWithLifecycle()
    if (auth !is AuthState.SignedIn) {
        Column(modifier.padding(16.dp)) { NeedsAccount("Materi PDF") }
        return
    }
    val viewModel = koinViewModel<MaterialsViewModel>(key = "materials-$courseId") { parametersOf(courseId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pick = rememberPdfPicker(viewModel::onPicked)
    var deleting by remember { mutableStateOf<DocumentDto?>(null) }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.courseSynced) {
            item { NeedsSync(viewModel::syncNow) }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Unggah PDF berbasis teks (maks. 10 MB, 100 halaman) untuk ditanyakan. PDF hasil scan belum didukung.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = pick, enabled = !state.uploading && !state.offline) {
                        Text(if (state.uploading) "Mengunggah…" else "Unggah PDF")
                    }
                    if (state.retryAvailable && !state.uploading) {
                        OutlinedButton(onClick = viewModel::retryUpload) { Text("Coba lagi") }
                    }
                }
                if (state.offline) OfflineNote()
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.loading) item { CircularProgressIndicator() }
        if (!state.loading && state.documents.isEmpty()) {
            item { EmptyState("Belum ada materi", "PDF yang kamu unggah akan muncul di sini.") }
        }
        items(state.documents, key = { it.id }) { doc ->
            DocumentCard(doc, onClick = { onOpenDocument(doc.id) }, onDelete = { deleting = doc })
        }
    }

    state.consentFor?.let { file ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConsent,
            title = { Text("Unggah \"${file.name}\"?") },
            text = {
                Text(
                    "PDF disimpan privat di server StudyFlow. Teksnya diproses menjadi embedding oleh " +
                        "${state.info?.providerLabel ?: "penyedia AI"} agar bisa ditanyakan.\n\n" +
                        (state.info?.dataNotice ?: ""),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmConsent) { Text("Setuju dan unggah") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConsent) { Text("Batal") } },
        )
    }
    deleting?.let { doc ->
        ConfirmDeleteDialog(
            title = "Hapus ${doc.title}?",
            message = "Berkas, teks, dan indeks pencarian dokumen ini dihapus dari server.",
            onConfirm = { viewModel.delete(doc.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun DocumentCard(doc: DocumentDto, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = doc.status == "ready", onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(doc.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                when (doc.status) {
                    "ready" -> Label("Siap", colors.primaryContainer, colors.onPrimaryContainer)
                    "queued", "processing" -> Label("Diproses…", colors.secondaryContainer, colors.onSecondaryContainer)
                    "unsupported" -> Label("Tidak didukung", colors.errorContainer, colors.onErrorContainer)
                    else -> Label("Gagal", colors.errorContainer, colors.onErrorContainer)
                }
            }
            Text("${doc.pageCount} halaman · ${doc.sizeBytes / 1024} KB", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            if (doc.status == "unsupported" || doc.status == "failed") {
                Text(documentErrorMessage(doc.errorCode), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            doc.warnings.filter { it.startsWith("Halaman tanpa teks") }.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) { Text("Hapus") }
        }
    }
}

// ---------- Detail dokumen ----------

@Composable
fun DocumentScreen(documentId: String, onBack: () -> Unit) {
    val viewModel = koinViewModel<DocumentViewModel>(key = "document-$documentId") { parametersOf(documentId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openPdf = rememberPdfOpener()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    StudyScaffold(state.document?.title ?: "Materi", onBack) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.offline) OfflineNote()
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.document?.let { doc ->
                        doc.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.open(openPdf) }, enabled = !state.busy && !state.offline) {
                                Text(if (state.busy) "Mengunduh…" else "Buka PDF")
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.offline) { Text("Hapus") }
                        }
                        Text("Pratinjau teks hasil ekstraksi", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            items(state.pages, key = { it.pageNumber }) { page ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Halaman ${page.pageNumber}", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(page.text.ifBlank { "(tidak ada teks terbaca)" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.hasMorePages) {
                item { OutlinedButton(onClick = viewModel::loadMorePages) { Text("Muat halaman berikutnya") } }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Hapus materi ini?",
            message = "Berkas, teks, dan indeks pencarian dokumen ini dihapus dari server.",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ---------- Tanya materi ----------

@Composable
fun AskScreen(courseId: String, courseName: String, onBack: () -> Unit, onOpenSource: (String) -> Unit) {
    val viewModel = koinViewModel<AskViewModel>(key = "ask-$courseId") { parametersOf(courseId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size)
    }

    StudyScaffold("Tanya materi · $courseName", onBack) { modifier ->
        Column(modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!state.courseSynced) InfoCard("Sinkronkan dulu", "Mata kuliah ini belum tersimpan di server.")
                        Text(
                            "Jawaban hanya diambil dari PDF yang sudah diunggah di mata kuliah ini, lengkap dengan sumbernya. " +
                                "Periksa kembali cuplikan sumber karena AI bisa keliru.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.info?.mode == "demo") DemoLabel()
                        if (state.offline) OfflineNote()
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message, onOpenSource)
                }
                if (state.waiting) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                            Text("Mencari jawaban di materi…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInput,
                    placeholder = { Text("Tulis pertanyaan tentang materi…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    enabled = state.threadId != null && !state.offline,
                )
                Button(
                    onClick = viewModel::send,
                    enabled = state.threadId != null && !state.sending && !state.offline && state.input.trim().length >= 3,
                ) { Text("Kirim") }
            }
        }
    }

    if (state.consentNeeded) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConsent,
            title = { Text("Kirim pertanyaan ke ${state.info?.providerLabel ?: "penyedia AI"}?") },
            text = {
                Text(
                    "Pertanyaan dan potongan materi yang relevan dikirim untuk dijawab.\n\n" + (state.info?.dataNotice ?: ""),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmConsent) { Text("Setuju dan kirim") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConsent) { Text("Batal") } },
        )
    }
}

@Composable
private fun MessageBubble(message: MessageDto, onOpenSource: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val mine = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = if (mine) colors.primaryContainer else colors.surfaceContainerLow,
            contentColor = if (mine) colors.onPrimaryContainer else colors.onSurface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 640.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!mine && message.isDemo) DemoLabel()
                Text(message.content, style = MaterialTheme.typography.bodyLarge)
                if (!mine && message.sources.isNotEmpty()) {
                    Text("Sumber", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    message.sources.forEach { source -> SourceChip(source, onOpenSource) }
                }
            }
        }
        if (mine && message.jobStatus == "failed") {
            Text(jobErrorMessage(message.jobError), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}

@Composable
private fun SourceChip(source: SourceDto, onOpenSource: (String) -> Unit) {
    AssistChip(
        onClick = { onOpenSource(source.chunkId) },
        label = { Text("${source.documentTitle} · ${pageLabel(source.pageStart, source.pageEnd)}", maxLines = 1,
            overflow = TextOverflow.Ellipsis) },
    )
}

// ---------- Cuplikan sumber ----------

@Composable
fun SourceScreen(chunkId: String, onBack: () -> Unit) {
    val viewModel = koinViewModel<SourceViewModel>(key = "source-$chunkId") { parametersOf(chunkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openPdf = rememberPdfOpener()
    StudyScaffold("Sumber", onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.offline) OfflineNote()
            val chunk = state.chunk
            if (chunk != null) {
                Text(chunk.documentTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pageLabel(chunk.pageStart, chunk.pageEnd)} (urutan halaman di berkas PDF, bisa berbeda dengan nomor cetak)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Text(chunk.text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { viewModel.openPdf(openPdf) }, enabled = !state.busy && !state.offline) {
                    Text(if (state.busy) "Mengunduh…" else "Buka PDF")
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
