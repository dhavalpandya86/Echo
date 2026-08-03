package com.dhaval.echo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.db.ExtractedItem
import com.dhaval.echo.data.db.ItemKind
import com.dhaval.echo.ui.components.EchoCard
import com.dhaval.echo.ui.components.EchoEmptyState
import com.dhaval.echo.ui.components.EchoTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TasksScreen(
    onNavigateBack: () -> Unit,
    onOpenMemory: (String) -> Unit = {},
    viewModel: TasksViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showDone by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = "Commitments", onBackClick = onNavigateBack) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Open / Done switch.
            TabRow(
                selectedTabIndex = if (showDone) 1 else 0,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Tab(selected = !showDone, onClick = { showDone = false },
                    text = { Text("Waiting (${state.open.size})") })
                Tab(selected = showDone, onClick = { showDone = true },
                    text = { Text("Done (${state.done.size})") })
            }

            val items = if (showDone) state.done else state.open
            if (!state.isLoading && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EchoEmptyState(
                        message = if (showDone) "Nothing finished yet." else "Nothing is waiting for you.",
                        description = if (showDone) "Commitments you complete will gather here."
                        else "Enjoy the space. Commitments Echo notices in your memories appear here.",
                        icon = Icons.Default.TaskAlt
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp, start = 20.dp, end = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        TaskCard(
                            item = item,
                            done = showDone,
                            onToggle = { if (showDone) viewModel.undoDone(item.id) else viewModel.markDone(item.id) },
                            onOpenMemory = { onOpenMemory(item.memoryId) },
                            onReschedule = { days -> viewModel.reschedule(item.id, days) },
                            onDismiss = { viewModel.dismiss(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    item: ExtractedItem,
    done: Boolean,
    onToggle: () -> Unit,
    onOpenMemory: () -> Unit,
    onReschedule: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    EchoCard(onClick = onOpenMemory) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (done) "Mark not done" else "Mark done",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null
                )
                val meta = buildString {
                    append(if (item.kind == ItemKind.REMINDER) "Reminder" else "Task")
                    item.dueAtMillis?.let { append(" • ").append(formatDue(it)) }
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (!done) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Do it tomorrow") },
                            onClick = { menuOpen = false; onReschedule(1) })
                        DropdownMenuItem(text = { Text("Do it next week") },
                            onClick = { menuOpen = false; onReschedule(7) })
                        DropdownMenuItem(text = { Text("Clear due date") },
                            onClick = { menuOpen = false; onReschedule(null) })
                        DropdownMenuItem(text = { Text("Open memory") },
                            onClick = { menuOpen = false; onOpenMemory() })
                        DropdownMenuItem(text = { Text("Dismiss") },
                            onClick = { menuOpen = false; onDismiss() })
                    }
                }
            }
        }
    }
}

private fun formatDue(millis: Long): String {
    val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("EEE, d MMM • HH:mm"))
}
