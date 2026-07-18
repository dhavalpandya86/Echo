package com.dhaval.echo.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.ui.components.*
import com.dhaval.echo.ui.theme.echoBreathe
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HomeUiState(
    val displayName: String? = null,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isSaving: Boolean = false,
    val durationMillis: Long = 0,
    val amplitude: Float = 0f,
    val errorMessage: String? = null,
    val recentRecordings: List<TimelineEntry> = emptyList(),
    val stats: HomeStats = HomeStats(),
    val insightOfDay: com.dhaval.echo.domain.ai.TimelineInsight? = null
)

data class HomeStats(
    val todayCount: Int = 0,
    val totalCount: Int = 0,
    val streakDays: Int = 0
)

@Composable
fun HomeScreen(
    onNavigateToRecord: () -> Unit,
    onNavigateToTextEntry: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToCollections: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEntry: (String) -> Unit,
    onNavigateToTasks: () -> Unit = {},
    onNavigateToEntities: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var fabExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EchoTopBar(
                actions = {
                    IconButton(onClick = onNavigateToTasks) {
                        Icon(Icons.Default.TaskAlt, contentDescription = "Tasks")
                    }
                    IconButton(onClick = onNavigateToEntities) {
                        Icon(Icons.Default.Hub, contentDescription = "People & Topics")
                    }
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExpandableFab(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onVoice = {
                    fabExpanded = false
                    onNavigateToRecord()
                },
                onText = {
                    fabExpanded = false
                    onNavigateToTextEntry()
                }
            )
        }
    ) { innerPadding ->
        if (fabExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { fabExpanded = false }
                    )
            )
        }
        HomeScreenContent(
            state = state,
            paddingValues = innerPadding,
            onEntryClick = onNavigateToEntry,
            onFavoriteClick = viewModel::toggleFavorite
        )
    }
}

@Composable
private fun ExpandableFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onVoice: () -> Unit,
    onText: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.7f),
            exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.7f)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MiniFabItem(
                    icon = Icons.Default.Edit,
                    label = "Write",
                    onClick = onText
                )
                MiniFabItem(
                    icon = Icons.Default.Mic,
                    label = "Record",
                    onClick = onVoice
                )
            }
        }

        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = if (!expanded) Modifier.echoBreathe() else Modifier
        ) {
            AnimatedContent(targetState = expanded, label = "fab_icon") { isExpanded ->
                if (isExpanded) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                } else {
                    Icon(Icons.Default.Add, contentDescription = "New Entry")
                }
            }
        }
    }
}

@Composable
private fun MiniFabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HomeScreenContent(
    state: HomeUiState,
    paddingValues: PaddingValues,
    onEntryClick: (String) -> Unit,
    onFavoriteClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + 120.dp,
            start = 24.dp,
            end = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        item { HeaderSection(state.displayName) }

        state.insightOfDay?.let { insight ->
            item {
                EchoInsightCard(
                    title = insight.title,
                    description = insight.description,
                    type = insight.type,
                    onClick = {}
                )
            }
        }

        item { StatsSection(state.stats) }

        item {
            Text(
                text = "Recent Memories",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        if (state.recentRecordings.isEmpty()) {
            item {
                EchoEmptyState(
                    message = "No memories yet.",
                    description = "Tap + to record your voice or write your thoughts.",
                    icon = Icons.Default.Mic
                )
            }
        } else {
            items(state.recentRecordings, key = { it.id }) { entry ->
                EchoTimelineCard(
                    title = entry.title,
                    time = entry.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                    duration = if (entry.entryType == "VOICE") formatDuration(entry.durationMillis) else entry.entryType.lowercase().replaceFirstChar { it.uppercaseChar() },
                    isFavorite = entry.isFavorite,
                    status = entry.transcriptionStatus,
                    description = entry.summary ?: entry.transcription ?: entry.textContent?.take(120),
                    onFavoriteClick = { onFavoriteClick(entry.id) },
                    onClick = { onEntryClick(entry.id) }
                )
            }
        }
    }
}

@Composable
private fun HeaderSection(displayName: String?) {
    val today = LocalDate.now()
    val dayOfWeek = today.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
    val dateText = today.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greetingBase = when (hour) {
        in 0..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        else -> "Good Evening"
    }
    val greeting = if (displayName.isNullOrBlank()) greetingBase else "$greetingBase, $displayName"
    EchoSectionHeader(
        title = greeting,
        subtitle = "$dayOfWeek, $dateText",
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

@Composable
private fun StatsSection(stats: HomeStats) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCard(label = "Today", value = stats.todayCount.toString(), modifier = Modifier.weight(1f))
        StatCard(label = "Total", value = stats.totalCount.toString(), modifier = Modifier.weight(1f))
        StatCard(label = "Streak", value = "${stats.streakDays}d", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    EchoCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column {
            Text(text = value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
