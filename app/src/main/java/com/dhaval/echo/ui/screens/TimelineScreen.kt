package com.dhaval.echo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.ui.components.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    onEntryClick: (String) -> Unit,
    onProfileClick: () -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            EchoTopBar(title = "Story", onProfileClick = onProfileClick)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            EchoSearchBar(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = "Look back through your story…",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            if (state.groupedEntries.isEmpty() && !state.isLoading) {
                EchoEmptyState(
                    message = if (state.searchQuery.isEmpty()) "Your story begins here." else "Nothing from that moment yet.",
                    description = if (state.searchQuery.isEmpty()) "Every memory you capture becomes part of the story of your life." else "Try a different moment, person, or place.",
                    icon = Icons.Default.Mic,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Insights Section
                    if (state.insights.isNotEmpty() && state.searchQuery.isEmpty()) {
                        item {
                            TimelineHeader("Insights")
                        }
                        items(state.insights.take(2)) { insight ->
                            EchoInsightCard(
                                title = insight.title,
                                description = insight.description,
                                type = insight.type,
                                onClick = { /* TODO: Project view */ }
                            )
                        }
                    }

                    TimelineGroup.entries.forEach { group ->
                        val entries = state.groupedEntries[group] ?: emptyList()
                        if (entries.isNotEmpty()) {
                            stickyHeader {
                                TimelineHeader(group.label)
                            }
                            items(entries, key = { it.id }) { entry ->
                                EchoTimelineCard(
                                    title = entry.title,
                                    time = entry.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                                    duration = formatDuration(entry.durationMillis),
                                    isFavorite = entry.isFavorite,
                                    status = entry.transcriptionStatus,
                                    analysisStatus = entry.analysisStatus,
                                    description = if (entry.summary.isNullOrBlank()) entry.transcription else entry.summary,
                                    relatedMemoriesCount = state.relatedCounts[entry.id] ?: 0,
                                    onFavoriteClick = { viewModel.toggleFavorite(entry.id) },
                                    onClick = { onEntryClick(entry.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineHeader(label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}
