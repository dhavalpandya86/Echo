package com.dhaval.echo.ui.settings.backup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.backup.ArchiveNaming
import com.dhaval.echo.data.backup.BackupIndexEntry
import com.dhaval.echo.data.user.StorageReporter
import com.dhaval.echo.domain.backup.BackupReason
import com.dhaval.echo.ui.components.EchoTopBar

/**
 * Every archive in the destination folder, newest first.
 *
 * Rows are built from the index, which in turn is built from each file's
 * plaintext envelope header — so the list draws instantly and, crucially,
 * without asking for a password. Prompting for a password in order to *draw a
 * list* would be absurd; the counts simply stay hidden until an archive is
 * opened.
 */
@Composable
fun BackupHistoryScreen(
    onNavigateBack: () -> Unit,
    onRestore: (String) -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    var shareCandidate by remember { mutableStateOf<BackupIndexEntry?>(null) }
    var deleteCandidate by remember { mutableStateOf<BackupIndexEntry?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    fun share(entry: BackupIndexEntry) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share backup"))
    }

    Scaffold(
        topBar = { EchoTopBar(title = "All backups", onBackClick = onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (state.history.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No backups yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.history, key = { it.uri }) { entry ->
                ArchiveRow(
                    entry = entry,
                    currentEpoch = state.settings.passwordEpoch,
                    verifying = state.verifyingUri == entry.uri,
                    onRestore = { onRestore(entry.uri) },
                    onVerify = { viewModel.verify(entry.uri) },
                    onShare = {
                        // Sharing an unencrypted archive hands over the entire
                        // diary in the clear. That deserves a deliberate yes.
                        if (entry.encrypted) share(entry) else shareCandidate = entry
                    },
                    onDelete = { deleteCandidate = entry }
                )
            }
        }
    }

    shareCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { shareCandidate = null },
            title = { Text("This backup isn't encrypted") },
            text = {
                Text(
                    "Anyone who receives this file can read every memory, transcript " +
                        "and photo in it. Share it anyway?"
                )
            },
            confirmButton = {
                TextButton(onClick = { share(entry); shareCandidate = null }) { Text("Share anyway") }
            },
            dismissButton = { TextButton(onClick = { shareCandidate = null }) { Text("Cancel") } }
        )
    }

    deleteCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete this backup?") },
            text = { Text("${entry.fileName}\n\nThis can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.delete(entry.uri); deleteCandidate = null }
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ArchiveRow(
    entry: BackupIndexEntry,
    currentEpoch: Int,
    verifying: Boolean,
    onRestore: () -> Unit,
    onVerify: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    entry.name ?: entry.poolLabel(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (entry.encrypted) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Encrypted",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                buildList {
                    add(entry.createdAtMillis.asFriendlyTime())
                    add(StorageReporter.format(entry.sizeBytes))
                    entry.reasonLabel()?.let(::add)
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (entry.detailsKnown) {
                Text(
                    buildList {
                        add("${entry.memories} memories")
                        if (entry.recordings > 0) add("${entry.recordings} recordings")
                        if (!entry.includesMedia) add("no recordings")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Changing the backup password leaves older archives sealed with the
            // one they were made with. Saying so is the difference between an
            // old file being confusing and being presumed broken.
            if (entry.encrypted && entry.detailsKnown && entry.passwordEpoch < currentEpoch) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Needs your previous backup password",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onVerify, enabled = !verifying) {
                    Text(if (verifying) "Checking…" else "Verify")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

private fun BackupIndexEntry.poolLabel(): String = when (pool) {
    ArchiveNaming.Pool.FULL.tag -> "Full backup"
    ArchiveNaming.Pool.QUICK.tag -> "Quick backup"
    ArchiveNaming.Pool.CHECKPOINT.tag -> "Before a restore"
    else -> "Backup"
}

/** Why this archive exists — the thing that makes history readable months later. */
private fun BackupIndexEntry.reasonLabel(): String? {
    reasonDetail?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching { BackupReason.valueOf(reason).label }.getOrNull()
}
