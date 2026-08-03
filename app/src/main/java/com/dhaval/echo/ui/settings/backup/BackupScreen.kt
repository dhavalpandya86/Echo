package com.dhaval.echo.ui.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.user.StorageReporter
import com.dhaval.echo.domain.backup.BackupConditions
import com.dhaval.echo.domain.backup.BackupFrequency
import com.dhaval.echo.domain.backup.BackupHealth
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupSettings
import com.dhaval.echo.domain.backup.BackupTrigger
import com.dhaval.echo.ui.components.EchoButton
import com.dhaval.echo.ui.components.EchoTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup & Restore.
 *
 * The health card comes first and everything else after it, because the
 * question people actually arrive with is "am I protected?" — not "how do I
 * configure this". A settings list that opens on a frequency picker answers a
 * question nobody asked and leaves the real one to inference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onRestoreFromFile: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    var showFrequency by remember { mutableStateOf(false) }
    var showConditions by remember { mutableStateOf(false) }
    var showRetention by remember { mutableStateOf(false) }
    var showTriggers by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }

    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onDestinationPicked) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = { EchoTopBar(title = "Backup", onBackClick = onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            state.health?.let { HealthCard(it, onFixDestination = { destinationPicker.launch(null) }) }

            state.progress?.let { RunningCard(it, onCancel = viewModel::cancelBackup) }

            Group("Back up now") {
                Row(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EchoButton(
                        text = if (state.settings.includeMedia) "Back up everything" else "Back up",
                        onClick = {
                            viewModel.backupNow(
                                if (state.settings.includeMedia) BackupProfile.FULL else BackupProfile.QUICK
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Item(
                    icon = Icons.Default.Bookmark,
                    title = "Save a named backup",
                    subtitle = "Kept until you delete it — never removed automatically",
                    onClick = { showNameDialog = true }
                )
                Item(
                    icon = Icons.Default.History,
                    title = "All backups",
                    value = state.history.size.takeIf { it > 0 }?.toString(),
                    onClick = onOpenHistory
                )
                Item(
                    icon = Icons.Default.SettingsBackupRestore,
                    title = "Restore from a file",
                    subtitle = "Pick an .echo backup from anywhere on this device",
                    onClick = onRestoreFromFile
                )
            }

            Group("Where backups go") {
                Item(
                    icon = Icons.Default.Folder,
                    title = "Folder",
                    subtitle = state.destinationLabel
                        ?: "Not chosen yet — backups can't run without one",
                    onClick = { destinationPicker.launch(null) }
                )
                Item(
                    icon = Icons.Default.PhotoLibrary,
                    title = "Include recordings and photos",
                    subtitle = if (state.settings.includeMedia) {
                        "Complete, but large"
                    } else {
                        "Small and fast — memories and transcripts only"
                    },
                    trailing = {
                        Switch(
                            checked = state.settings.includeMedia,
                            onCheckedChange = viewModel::setIncludeMedia
                        )
                    }
                )
                Item(
                    icon = Icons.Default.DeleteSweep,
                    title = "Keep",
                    value = "${state.settings.retentionCount} backups",
                    onClick = { showRetention = true }
                )
            }

            Group("Automatic") {
                Item(
                    icon = Icons.Default.Schedule,
                    title = "How often",
                    value = state.settings.frequency.label,
                    onClick = { showFrequency = true }
                )
                Item(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Only when",
                    subtitle = state.settings.conditions.summary(),
                    onClick = { showConditions = true }
                )
                Item(
                    icon = Icons.Default.Bolt,
                    title = "Also back up when…",
                    value = "${state.settings.triggers.size} events",
                    onClick = { showTriggers = true }
                )
            }

            Group("Protection") {
                Item(
                    icon = Icons.Default.Lock,
                    title = "Encrypt backups",
                    subtitle = if (state.settings.encryptionEnabled) {
                        "AES-256. Only your password can open them."
                    } else {
                        "Anyone with the file can read your diary"
                    },
                    trailing = {
                        Switch(
                            checked = state.settings.encryptionEnabled,
                            onCheckedChange = viewModel::setEncryptionEnabled
                        )
                    }
                )
                if (state.settings.encryptionEnabled) {
                    Item(
                        icon = Icons.Default.Key,
                        title = if (state.hasStoredPassword) "Change password" else "Set a password",
                        subtitle = if (state.hasStoredPassword) {
                            "Applies to new backups; older ones keep the password they were made with"
                        } else {
                            "Required before the first encrypted backup"
                        },
                        onClick = { viewModel.backupNow(BackupProfile.QUICK) }
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showFrequency) {
        ChoiceDialog(
            title = "How often",
            options = BackupFrequency.entries,
            selected = state.settings.frequency,
            label = { it.label },
            // A truthful footnote rather than a silently different behaviour:
            // short intervals really do skip media, and hiding that would make
            // "every hour" look like it protects more than it does.
            footnote = "Anything more often than twice a day backs up memories " +
                "and transcripts only, not recordings.",
            onDismiss = { showFrequency = false },
            onSelect = { viewModel.setFrequency(it); showFrequency = false }
        )
    }

    if (showRetention) {
        ChoiceDialog(
            title = "Keep",
            options = BackupSettings.RETENTION_CHOICES,
            selected = state.settings.retentionCount,
            label = { "$it backups" },
            footnote = "Full and quick backups are kept separately, so frequent " +
                "small backups never push out your complete ones.",
            onDismiss = { showRetention = false },
            onSelect = { viewModel.setRetentionCount(it); showRetention = false }
        )
    }

    if (showConditions) {
        ConditionsDialog(
            conditions = state.settings.conditions,
            onDismiss = { showConditions = false },
            onChange = viewModel::setConditions
        )
    }

    if (showTriggers) {
        TriggersDialog(
            enabled = state.settings.triggers,
            onDismiss = { showTriggers = false },
            onToggle = viewModel::toggleTrigger
        )
    }

    if (showNameDialog) {
        NameBackupDialog(
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                showNameDialog = false
                viewModel.backupNow(
                    if (state.settings.includeMedia) BackupProfile.FULL else BackupProfile.QUICK,
                    name = name
                )
            }
        )
    }

    if (state.needsPasswordSetup) {
        PasswordDialog(
            isChange = state.hasStoredPassword,
            onDismiss = viewModel::dismissPasswordSetup,
            onConfirm = viewModel::setPassword
        )
    }
}

// ── Health ───────────────────────────────────────────────────────────────────

@Composable
private fun HealthCard(health: BackupHealth, onFixDestination: () -> Unit) {
    val (icon, tint, title) = when (health) {
        is BackupHealth.Healthy -> Triple(
            Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "Your memories are protected"
        )
        is BackupHealth.Failed -> Triple(
            Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, "Last backup didn't finish"
        )
        is BackupHealth.Stale -> Triple(
            Icons.Default.WarningAmber, MaterialTheme.colorScheme.error, "Backups have stopped running"
        )
        is BackupHealth.NotConfigured -> Triple(
            Icons.Default.Shield, MaterialTheme.colorScheme.error, "Your memories are not protected yet"
        )
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))

            when (health) {
                is BackupHealth.Healthy -> {
                    HealthRow("Last backup", health.lastBackupAtMillis.asFriendlyTime())
                    health.nextBackupAtMillis?.let { HealthRow("Next", it.asFriendlyTime()) }
                    health.protects?.let { counts ->
                        HealthRow(
                            "Protects",
                            buildList {
                                add("${counts.memories} memories")
                                if (counts.recordings > 0) add("${counts.recordings} recordings")
                                if (counts.photos > 0) add("${counts.photos} photos")
                            }.joinToString(" · ")
                        )
                    }
                    if (health.lastSizeBytes > 0) {
                        HealthRow("Size", StorageReporter.format(health.lastSizeBytes))
                    }
                    health.spaceWarning?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "The next backup may not fit — needs " +
                                "${StorageReporter.format(it.requiredBytes)}, " +
                                "${StorageReporter.format(it.availableBytes)} free.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                is BackupHealth.Failed -> {
                    Text(
                        health.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (health.isDestinationProblem) {
                        Spacer(Modifier.height(14.dp))
                        EchoButton(text = "Choose another folder", onClick = onFixDestination)
                    }
                }

                is BackupHealth.Stale -> Text(
                    "No backup for ${health.daysSince} days. Check the conditions below — " +
                        "backups wait for charging unless you say otherwise.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is BackupHealth.NotConfigured -> Text(
                    if (!health.hasDestination) {
                        "Choose a folder below and Echo will keep a copy of everything, " +
                            "so uninstalling or losing this phone doesn't lose your diary."
                    } else {
                        "Take your first backup to start protecting your memories."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String) {
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

@Composable
private fun RunningCard(progress: com.dhaval.echo.domain.backup.BackupProgress, onCancel: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                progress.stage.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (progress.filesTotal > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${progress.filesDone} of ${progress.filesTotal} · " +
                        StorageReporter.format(progress.bytesDone),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.bytesTotal > 0) {
                            (progress.bytesDone.toFloat() / progress.bytesTotal).coerceIn(0f, 1f)
                        } else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    footnote: String? = null,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelect(option) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                footnote?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun ConditionsDialog(
    conditions: BackupConditions,
    onDismiss: () -> Unit,
    onChange: (BackupConditions) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Only back up when") },
        text = {
            Column {
                CheckRow("Charging", conditions.requireCharging) {
                    onChange(conditions.copy(requireCharging = it))
                }
                CheckRow("Battery isn't low", conditions.requireBatteryNotLow) {
                    onChange(conditions.copy(requireBatteryNotLow = it))
                }
                CheckRow("Phone is idle", conditions.requireDeviceIdle) {
                    onChange(conditions.copy(requireDeviceIdle = it))
                }
                CheckRow("On Wi-Fi", conditions.requireUnmetered) {
                    onChange(conditions.copy(requireUnmetered = it))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Backups are written to this phone, so Wi-Fi only matters if you " +
                        "later choose a folder that syncs to the cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun TriggersDialog(
    enabled: Set<BackupTrigger>,
    onDismiss: () -> Unit,
    onToggle: (BackupTrigger, Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Also back up when") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BackupTrigger.entries.forEach { trigger ->
                    CheckRow(trigger.label, trigger in enabled) { onToggle(trigger, it) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "These write a quick backup — memories, transcripts and settings, " +
                        "without recordings — so they stay small enough to run often.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NameBackupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this backup") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Before Europe trip") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Named backups are never deleted automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            EchoButton(
                text = "Back up",
                onClick = { onConfirm(name.trim().ifBlank { "Saved" }) }
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The one dialog in the app where the warning matters more than the field.
 *
 * A forgotten backup password is unrecoverable by construction — there is no
 * reset, because a reset would mean Echo could read the backups, which is the
 * thing encryption is for. Saying so plainly, before the fact, is the only
 * honest option.
 */
@Composable
private fun PasswordDialog(
    isChange: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != password
    val valid = password.length >= MIN_PASSWORD_LENGTH && password == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isChange) "Change backup password" else "Create a backup password") },
        text = {
            Column {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "This password cannot be recovered. If you forget it, your " +
                            "backups cannot be opened — by you, by Echo, by anyone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text("At least $MIN_PASSWORD_LENGTH characters") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) { { Text("Doesn't match") } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isChange) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Backups you already have keep their current password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(password.toCharArray()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private const val MIN_PASSWORD_LENGTH = 8

// ── Shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), content = content)
        }
    }
}

@Composable
private fun Item(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
            }
            trailing?.invoke()
        }
    }
}

private fun BackupConditions.summary(): String = buildList {
    if (requireCharging) add("charging")
    if (requireBatteryNotLow) add("battery is fine")
    if (requireDeviceIdle) add("idle")
    if (requireUnmetered) add("on Wi-Fi")
}.let { if (it.isEmpty()) "Any time" else it.joinToString(", ").replaceFirstChar(Char::uppercase) }

/** "Today 20:15", "Yesterday 08:12", "3 Aug 2026" — relative where it helps. */
internal fun Long.asFriendlyTime(): String {
    if (this <= 0) return "Never"
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    val today = now / day
    val then = this / day
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))
    return when {
        then == today -> "Today $time"
        then == today - 1 -> "Yesterday $time"
        now - this < 7 * day -> SimpleDateFormat("EEEE HH:mm", Locale.getDefault()).format(Date(this))
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(this))
    }
}
