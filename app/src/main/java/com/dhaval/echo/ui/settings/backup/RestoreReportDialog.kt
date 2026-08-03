package com.dhaval.echo.ui.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.backup.BackupLocalState
import com.dhaval.echo.data.backup.BackupManifest
import com.dhaval.echo.data.backup.ConsistencyRecord
import com.dhaval.echo.data.backup.RestoreEngine
import com.dhaval.echo.data.backup.RestoreReportRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads the report a restore left behind before restarting the process.
 *
 * The restore itself cannot show this: it ends by killing the app. Without a
 * report on the next launch, someone who has just replaced their entire diary
 * is dropped onto a home screen with no account of what happened to it.
 */
@HiltViewModel
class RestoreReportViewModel @Inject constructor(
    private val localState: BackupLocalState,
    private val engine: RestoreEngine
) : ViewModel() {

    private val _report = MutableStateFlow(load())
    val report: StateFlow<RestoreReportRecord?> = _report.asStateFlow()

    private fun load(): RestoreReportRecord? = localState.pendingRestoreReport?.let { raw ->
        runCatching { BackupManifest.json.decodeFromString<RestoreReportRecord>(raw) }.getOrNull()
    }

    /**
     * Accepting the restore is what frees the parked copies — up to a few
     * gigabytes of the previous media. Until then they stay, because the user
     * might still want them back.
     */
    fun accept() {
        localState.pendingRestoreReport = null
        engine.releaseUndo()
        _report.value = null
    }

    /** Keeps the report for later; Undo stays available for its full window. */
    fun dismiss() {
        localState.pendingRestoreReport = null
        _report.value = null
    }

    fun undo() {
        viewModelScope.launch {
            if (engine.undo()) engine.restart()
        }
    }
}

/**
 * Shown once, at the first launch after a restore.
 *
 * It reports the *consistency scan*, not just "done": how many recordings and
 * photos the restored database expects against how many are actually on disk.
 * Checksums proved the archive arrived intact; this is the only thing that
 * proves the app did.
 */
@Composable
fun RestoreReportDialog(viewModel: RestoreReportViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsState()
    val record = report ?: return

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Restore complete") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "From the backup made ${record.createdAtMillis.asFriendlyTime()}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                ReportRow("Memories", record.memories.toString())
                if (record.collections > 0) ReportRow("Worlds", record.collections.toString())
                if (record.people > 0) ReportRow("People", record.people.toString())
                ReportRow("Memory graph", if (record.hasMemoryGraph) "Restored" else "None")
                ReportRow("Settings", "Applied")

                record.consistency.forEach { ConsistencyReportRow(it, record.includesMedia) }

                if (!record.includesMedia) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This was a quick backup, so it never contained recordings or " +
                            "photos. Anything already on this phone is untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (record.rekeyedUserId) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "These memories were made under a different account and have " +
                            "been moved to yours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                record.migratedFromVersion?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "The backup came from an older version of Echo and was upgraded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (record.undoAvailable) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Not what you expected? Undo puts back exactly what was here " +
                            "before, for the next 7 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::accept) { Text("Keep this") } },
        dismissButton = if (record.undoAvailable) {
            { TextButton(onClick = viewModel::undo) { Text("Undo restore") } }
        } else null
    )
}

@Composable
private fun ReportRow(label: String, value: String) {
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
private fun ConsistencyReportRow(item: ConsistencyRecord, archiveHadMedia: Boolean) {
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
                item.isComplete -> item.present.toString()
                else -> "${item.present} of ${item.referenced}"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
