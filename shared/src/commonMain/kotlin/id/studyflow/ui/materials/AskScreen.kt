package id.studyflow.ui.materials

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.remote.MessageDto
import id.studyflow.data.remote.SourceDto
import id.studyflow.ui.components.InfoCard
import id.studyflow.ui.study.DemoLabel
import id.studyflow.ui.study.OfflineNote
import id.studyflow.ui.study.StudyScaffold
import id.studyflow.ui.study.jobErrorMessage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

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
        label = {
            Text(
                "${source.documentTitle} · ${pageLabel(source.pageStart, source.pageEnd)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

// ---------- Cuplikan sumber ----------
