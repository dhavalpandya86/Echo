package com.dhaval.echo.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.intelligence.UnderstandingBackfillWorker
import com.dhaval.echo.data.preferences.AppearanceMode
import com.dhaval.echo.data.user.StorageBreakdown
import com.dhaval.echo.data.user.StorageReporter
import com.dhaval.echo.domain.ai.BrainStatus
import com.dhaval.echo.ui.components.EchoButton
import com.dhaval.echo.ui.components.EchoTopBar

/**
 * You — the user's control centre.
 *
 * Built to three rules, in this order of importance:
 *
 *  1. **Every visible row does something.** A row that opens nothing is worse
 *     than a row that isn't there: it spends the user's attention and returns
 *     nothing. Features that don't exist yet are simply absent, and become new
 *     rows when they land — the structure doesn't need to change to admit them.
 *  2. **Every value is real.** Storage is measured, "last analyzed" comes from
 *     extraction runs, the brains are whatever the capability layer resolves.
 *     Nothing here is a placeholder string.
 *  3. **No implementation vocabulary.** The user thinks in My Memories, My AI,
 *     My Privacy — not providers, models, or embeddings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAiSettings: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showNameDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Storage and brain state are point-in-time reads rather than streams —
    // refresh them when the user actually arrives at the page.
    LaunchedEffect(Unit) {
        viewModel.refreshStorage()
        viewModel.refreshBrains()
    }

    Scaffold(
        topBar = { EchoTopBar(title = "You", onBackClick = onNavigateBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            ProfileHeader(
                name = uiState.displayName,
                onClick = { showNameDialog = true }
            )

            MemoryStatusCard(
                status = uiState.memoryStatus,
                onAnalyzeNow = {
                    UnderstandingBackfillWorker.enqueue(context)
                    Toast.makeText(
                        context,
                        "Echo is reading through your memories.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )

            SettingsGroup(title = "Memory") {
                SettingsItem(
                    icon = Icons.Default.Collections,
                    title = "Collections",
                    subtitle = "Group memories that belong together",
                    onClick = onNavigateToCollections
                )
                SettingsItem(
                    icon = Icons.Default.SdCard,
                    title = "Storage",
                    // The old screen hardcoded "1.2 MB used". This is measured.
                    value = uiState.storage?.let { StorageReporter.format(it.totalBytes) },
                    onClick = { showStorageDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Refresh,
                    title = "Rebuild Memory",
                    subtitle = "Read every memory again from the beginning",
                    onClick = {
                        UnderstandingBackfillWorker.enqueue(context)
                        Toast.makeText(
                            context,
                            "Echo is rebuilding what it knows. This can take a while.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }

            if (uiState.brains.isNotEmpty()) {
                SettingsGroup(title = "AI Intelligence") {
                    uiState.brains.forEachIndexed { index, brain ->
                        if (index > 0) HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        BrainRow(brain = brain, onClick = onNavigateToAiSettings)
                    }
                }
            }

            SettingsGroup(title = "General") {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = "Display Name",
                    value = uiState.displayName ?: "Not set",
                    onClick = { showNameDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Palette,
                    title = "Appearance",
                    value = uiState.appearance.label,
                    onClick = { showAppearanceDialog = true }
                )
            }

            SettingsGroup(title = "About") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Version",
                    value = uiState.appVersion.takeIf { it.isNotBlank() },
                    showChevron = false
                )
                SettingsItem(
                    icon = Icons.Default.Logout,
                    title = "Log Out",
                    color = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    onClick = { viewModel.logout() }
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showNameDialog) {
        EditNameDialog(
            currentName = uiState.displayName.orEmpty(),
            onDismiss = { showNameDialog = false },
            onConfirm = { newName ->
                viewModel.updateDisplayName(newName)
                showNameDialog = false
            }
        )
    }

    if (showAppearanceDialog) {
        AppearanceDialog(
            current = uiState.appearance,
            onDismiss = { showAppearanceDialog = false },
            onSelect = {
                viewModel.setAppearance(it)
                showAppearanceDialog = false
            }
        )
    }

    if (showStorageDialog) {
        uiState.storage?.let {
            StorageDialog(breakdown = it, onDismiss = { showStorageDialog = false })
        }
    }
}

/**
 * Name, and one line of reassurance. No large avatar, no statistics — this is a
 * control centre, and the user already knows who they are.
 */
@Composable
private fun ProfileHeader(name: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name?.trim()?.firstOrNull()?.uppercase() ?: "·",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name?.takeIf { it.isNotBlank() } ?: "Add your name",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Your memories stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The one card on this page that reports live system state.
 *
 * It earns its place because it answers a question the user genuinely has —
 * "has Echo caught up with what I've given it?" — from real extraction runs
 * rather than a decorative tick. When work is outstanding it offers the action
 * that resolves it, so seeing the problem and fixing it is one tap.
 */
@Composable
private fun MemoryStatusCard(status: MemoryStatus, onAnalyzeNow: () -> Unit) {
    val upToDate = status.isUpToDate
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (upToDate) MaterialTheme.colorScheme.surfaceContainerLowest
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "Memory Status",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))

            if (upToDate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Everything is up to date.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                status.lastAnalyzedLabel?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Last analyzed $it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = if (status.awaiting == 1) "1 memory is waiting to be organized."
                    else "${status.awaiting} memories are waiting to be organized.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(14.dp))
                EchoButton(text = "Analyze Now", onClick = onAnalyzeNow)
            }
        }
    }
}

/**
 * One faculty, described in the user's terms.
 *
 * Deliberately never renders a model identifier the capability layer didn't
 * give it — if a brain is served by rules today and a downloaded model
 * tomorrow, this row changes without being edited.
 */
@Composable
private fun BrainRow(brain: BrainStatus, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (brain.isOffline) Icons.Default.PhoneAndroid else Icons.Default.CloudQueue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = brain.role,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = brain.engineName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = brain.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                brain.detail?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AppearanceDialog(
    current: AppearanceMode,
    onDismiss: () -> Unit,
    onSelect: (AppearanceMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = {
            Column {
                AppearanceMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            // The whole row is the target, not just the radio.
                            .clickable { onSelect(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == current, onClick = { onSelect(mode) })
                        Spacer(Modifier.width(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun StorageDialog(breakdown: StorageBreakdown, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage") },
        text = {
            Column {
                StorageRow("Recordings", breakdown.audioBytes)
                StorageRow("Photos & video", breakdown.mediaBytes)
                StorageRow("Memories & connections", breakdown.databaseBytes)
                StorageRow("Speech & search models", breakdown.modelBytes)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                StorageRow("Total", breakdown.totalBytes, bold = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun StorageRow(label: String, bytes: Long, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            StorageReporter.format(bytes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EditNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display Name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { EchoButton(text = "Save", onClick = { onConfirm(name.trim()) }) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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

/**
 * One row, one action.
 *
 * [value] is the right-aligned current state ("Dark", "1.2 GB"); [subtitle] is
 * an explanatory line beneath. A row shows one or the other, never both, so the
 * eye has a single place to look for what a row currently is.
 */
@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    showChevron: Boolean = true,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit = {}
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
            }
            if (showChevron) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
