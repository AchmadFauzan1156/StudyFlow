package id.studyflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.studyflow.domain.DeadlineStatus
import id.studyflow.domain.StudyTask
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.deadlineStatus
import id.studyflow.domain.formatDateTime
import id.studyflow.ui.LocalAppActions
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    icon: ImageVector = Icons.Outlined.Info,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(icon, size = 56.dp)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

/** Ikon di dalam lingkaran berwarna lembut; dipakai untuk keadaan kosong dan penanda bagian. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size / 2))
    }
}

/** Inisial nama di kotak membulat; warnanya tetap untuk nama yang sama agar mudah dikenali. */
@Composable
fun InitialAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
    )
    val (container, content) = palette[name.sumOf { it.code } % palette.size]
    Box(modifier.size(size).background(container, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoCard(title: String, message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * Fitur yang butuh akun. Jika server tersedia ([canSignIn]), tampilkan tombol masuk langsung di tempat agar
 * pengguna tidak perlu mencari halaman Pengaturan.
 */
@Composable
fun NeedsAccountPanel(title: String, benefit: String, canSignIn: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    if (canSignIn) {
                        "$benefit Butuh akun dan koneksi internet."
                    } else {
                        "$benefit Versi aplikasi ini belum terhubung ke server, jadi fitur ini belum tersedia."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (canSignIn) {
                    val actions = LocalAppActions.current
                    Button(onClick = actions.openAccount) { Text("Masuk atau daftar") }
                }
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
fun DeadlineBadge(status: DeadlineStatus) {
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when (status) {
        DeadlineStatus.OVERDUE -> Triple("Terlambat", colors.errorContainer, colors.onErrorContainer)
        DeadlineStatus.DUE_TODAY -> Triple("Hari ini", colors.tertiaryContainer, colors.onTertiaryContainer)
        DeadlineStatus.DUE_SOON -> Triple("Segera", colors.primaryContainer, colors.onPrimaryContainer)
        DeadlineStatus.DONE -> Triple("Selesai", colors.surfaceContainerHighest, colors.onSurfaceVariant)
        DeadlineStatus.LATER -> return
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun TaskRow(
    task: StudyTask,
    nowMillis: Long,
    zone: TimeZone,
    showCourse: Boolean,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = deadlineStatus(task.dueAt, task.status, nowMillis, zone)
    val done = task.status == TaskStatus.DONE
    // Geser ke kanan untuk menandai selesai/belum; kartu selalu kembali ke posisi semula.
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.fillMaxWidth(),
        enableDismissFromEndToStart = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onToggleDone()
            scope.launch { swipeState.reset() }
        },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    if (done) "Tandai belum selesai" else "Tandai selesai",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(
                Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = done, onCheckedChange = { onToggleDone() })
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    )
                    val subtitle = buildString {
                        if (showCourse && task.courseName != null) append(task.courseName).append(" · ")
                        append(formatDateTime(task.dueAt, zone))
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DeadlineBadge(status)
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = accent, maxLines = 1)
        }
    }
}
