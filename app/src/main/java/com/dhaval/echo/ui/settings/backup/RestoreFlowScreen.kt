package com.dhaval.echo.ui.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.user.StorageReporter
import com.dhaval.echo.domain.backup.MediaConsistency
import com.dhaval.echo.domain.backup.RestorePlan
import com.dhaval.echo.ui.components.EchoButton
import com.dhaval.echo.ui.components.EchoTopBar

/**
 * Choose a backup, unlock it, see exactly what restoring it will do, confirm.
 *
 * Reachable from the Welcome screen as well as from settings, because after a
 * reinstall this is the *first* thing someone needs and they have no account
 * yet. Restoring before signing in is supported: the archive's rows are claimed
 * for whichever account signs in next.
 */
@Composable
fun RestoreFlowScreen(
    onNavigateBack: () -> Unit,
    initialUri: String? = null,
    viewModel: RestoreViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onFilePicked) }

    // Arriving from history, the archive is already chosen.
    LaunchedEffect(initialUri) {
        if (initialUri != null) viewModel.onFilePicked(android.net.Uri.parse(initialUri))
    }

    Scaffold(
        topBar = {
            EchoTopBar(
                title = "Restore",
                onBackClick = if (state.readyToRestart) null else onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when {
                state.readyToRestart -> RestartPrompt(onRestart = viewModel::restart)

                state.plan != null -> PlanCard(
                    plan = state.plan!!,
                    onConfirm = viewModel::confirm,
                    onCancel = { viewModel.cancel(); onNavigateBack() }
                )

                state.isWorking -> WorkingCard(state)

                state.archiveUri == null -> ChooseCard(
                    onChoose = {
                        // Any type: providers disagree about what an unknown
                        // extension maps to, and a filter that excludes the
                        // user's own backup is worse than no filter.
                        filePicker.launch(arrayOf("*/*"))
                    }
                )

                else -> ArchiveCard(state)
            }
        }
    }

    if (state.needsPassword) {
        UnlockDialog(
            wrongPassword = state.wrongPassword,
            onDismiss = { viewModel.cancel(); onNavigateBack() },
            onSubmit = viewModel::submitPassword,
            onTryStored = viewModel::tryStoredPassword
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
            title = { Text("Can't restore this backup") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError(); onNavigateBack() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun ChooseCard(onChoose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(14.dp))
            Text("Choose a backup file", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick the .echo file you saved. Echo will check it and show you " +
                    "exactly what's inside before changing anything.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            EchoButton(text = "Choose file", onClick = onChoose)
        }
    }
}

@Composable
private fun ArchiveCard(state: RestoreUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                state.archiveName ?: "Echo backup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Created ${state.createdAtMillis.asFriendlyTime()}" +
                    if (state.isEncrypted) " · Encrypted" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WorkingCard(state: RestoreUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                state.progress?.stage?.label ?: "Working",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing on this phone has changed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            val progress = state.progress
            if (progress != null && progress.bytesTotal > 0) {
                LinearProgressIndicator(
                    progress = { (progress.bytesDone.toFloat() / progress.bytesTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${progress.filesDone} of ${progress.filesTotal} · " +
                        StorageReporter.format(progress.bytesDone),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The confirmation.
 *
 * Everything shown here is established fact, not prediction: the archive has
 * been unpacked, migrated, relinked and checked. That is why the media
 * consistency numbers can be trusted at this point — they were measured, not
 * estimated.
 */
@Composable
private fun PlanCard(plan: RestorePlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val summary = plan.summary

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Ready to restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                summary.name ?: summary.header.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            DetailRow("Created", summary.header.createdAtMillis.asFriendlyTime())
            DetailRow("Made with", "Echo ${summary.appVersionName}")
            DetailRow("From", summary.deviceModel)
            DetailRow("Size", StorageReporter.format(summary.header.sizeBytes))
            DetailRow("Encrypted", if (summary.header.encrypted) "Yes" else "No")
            DetailRow("Includes recordings", if (summary.includesMedia) "Yes" else "No")

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            DetailRow("Memories", summary.counts.memories.toString())
            if (summary.counts.collections > 0) DetailRow("Worlds", summary.counts.collections.toString())
            if (summary.counts.people > 0) DetailRow("People", summary.counts.people.toString())
            if (summary.counts.tasks > 0) DetailRow("Tasks", summary.counts.tasks.toString())
            DetailRow("Memory graph", if (summary.counts.hasMemoryGraph) "Included" else "None")

            if (plan.consistency.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                plan.consistency.forEach { ConsistencyRow(it, summary.includesMedia) }
            }

            plan.migratedFromVersion?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    "This backup is from an older version of Echo and has been " +
                        "upgraded to match this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (plan.willRekeyUserId) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "This backup was made under a different account. Its memories " +
                        "will be moved to the one you're signed in with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (plan.undoIncludesMedia) {
                        "Your current memories and recordings will be set aside first, " +
                            "so you can undo this for the next 7 days."
                    } else {
                        "Your current memories will be set aside first, so you can " +
                            "undo this for the next 7 days."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            EchoButton(text = "Restore this backup", onClick = onConfirm, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun RestartPrompt(onRestart: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("Restored", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Echo needs to restart to finish. You'll see a summary of what came back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            EchoButton(text = "Restart Echo", onClick = onRestart)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * Missing files are only a warning when the archive claimed to have them. A
 * quick backup contains no media by design, and flagging that as damage would
 * teach people to ignore the warning that matters.
 */
@Composable
private fun ConsistencyRow(item: MediaConsistency, archiveHadMedia: Boolean) {
    val problem = archiveHadMedia && !item.isComplete
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            when {
                !archiveHadMedia -> "Not in this backup"
                item.isComplete -> "${item.present} · all present"
                else -> "${item.present} of ${item.referenced} · ${item.missing} missing"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun UnlockDialog(
    wrongPassword: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
    onTryStored: () -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This backup is encrypted") },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup password") },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text("That password didn't open this backup") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onTryStored) { Text("Use the password saved on this phone") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = { onSubmit(password.toCharArray()) }
            ) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
