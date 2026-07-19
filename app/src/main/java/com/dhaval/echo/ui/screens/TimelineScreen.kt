package com.dhaval.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.ui.components.EchoEmptyState
import com.dhaval.echo.ui.components.EchoSearchBar
import com.dhaval.echo.ui.components.EchoTopBar
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onEntryClick: (String) -> Unit,
    onProfileClick: () -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = "Story", onProfileClick = onProfileClick) }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            // Header: the month + how much of the story is recorded.
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    state.monthLabel.ifBlank { "Your story" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (state.memoryCount > 0) {
                    Text(
                        "${state.memoryCount} ${if (state.memoryCount == 1) "memory" else "memories"} recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            EchoSearchBar(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = "Look back through your story…",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            if (state.days.isEmpty() && !state.isLoading) {
                EchoEmptyState(
                    message = if (state.searchQuery.isEmpty()) "Your story begins here." else "Nothing from that moment yet.",
                    description = if (state.searchQuery.isEmpty()) "Every memory you capture becomes part of the story of your life." else "Try a different moment, person, or place.",
                    icon = Icons.Default.Mic,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    state.days.forEach { day ->
                        item(key = "day_${day.date}") {
                            Text(
                                day.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        day.parts.forEach { part ->
                            item(key = "part_${day.date}_${part.part}") {
                                PartRow(part, onEntryClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One time-of-day section on the threaded timeline: a rail + its memory cards. */
@Composable
private fun PartRow(part: StoryPart, onEntryClick: (String) -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        // Rail: a continuous line with a dot marking this part.
        Box(Modifier.width(24.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
            Text(
                part.part.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(10.dp))
            part.entries.forEachIndexed { i, entry ->
                StoryMemoryCard(entry, onClick = { onEntryClick(entry.id) })
                if (i < part.entries.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StoryMemoryCard(entry: TimelineEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                entry.timestamp.format(DateTimeFormatter.ofPattern("h:mm a")),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                entry.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val snippet = entry.summary ?: entry.transcription ?: entry.textContent
            if (!snippet.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    snippet.take(140),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 3
                )
            }
        }
    }
}
