package com.dhaval.echo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.ui.components.EchoCard
import com.dhaval.echo.ui.components.EchoEmptyState
import com.dhaval.echo.ui.components.EchoTopBar
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntityDetailScreen(
    onNavigateBack: () -> Unit,
    onEntryClick: (String) -> Unit,
    onEntityClick: (String) -> Unit = {},
    viewModel: EntityDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val title = state.entity?.name ?: ""

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = title, onBackClick = onNavigateBack) }
    ) { padding ->
        if (!state.isLoading && state.memories.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EchoEmptyState(
                    message = "No memories",
                    description = "Memories mentioning this will appear here.",
                    icon = Icons.Default.Notes
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 20.dp, end = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.entity?.let { entity ->
                    item(key = "header") {
                        val n = entity.memoryCount
                        Text(
                            text = "${entity.type.lowercase().replaceFirstChar { it.uppercase() }} • " +
                                if (n == 1) "1 memory" else "$n memories",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
                if (state.related.isNotEmpty()) {
                    item(key = "connected") {
                        ConnectedSection(state.related, onEntityClick = onEntityClick)
                    }
                }
                items(state.memories, key = { it.id }) { entry ->
                    MemoryRow(entry = entry, onClick = { onEntryClick(entry.id) })
                }
            }
        }
    }
}

/** The entity graph, made visible: who/what this entity is connected to. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedSection(
    related: List<com.dhaval.echo.data.db.RelatedEntityView>,
    onEntityClick: (String) -> Unit
) {
    Column {
        Text(
            text = "Connected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            related.forEach { r ->
                AssistChip(
                    onClick = { onEntityClick(r.entityId) },
                    label = {
                        val times = if (r.weight == 1) "1 memory" else "${r.weight} memories"
                        Text("${r.name} · $times")
                    }
                )
            }
        }
    }
}

@Composable
private fun MemoryRow(entry: DiaryEntry, onClick: () -> Unit) {
    EchoCard(onClick = onClick) {
        Text(
            text = entry.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        val snippet = entry.summary?.takeIf { it.isNotBlank() }
            ?: entry.transcript?.takeIf { it.isNotBlank() }
            ?: entry.textContent?.takeIf { it.isNotBlank() }
        if (snippet != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = snippet.take(140),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 2
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.createdAt.format(DateTimeFormatter.ofPattern("d MMM yyyy • HH:mm")),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
