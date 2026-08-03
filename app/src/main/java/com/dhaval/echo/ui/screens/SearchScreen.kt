package com.dhaval.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.db.InferredConnectionView
import com.dhaval.echo.domain.search.SearchResult
import com.dhaval.echo.ui.components.EchoEmptyState
import com.dhaval.echo.ui.components.EchoSearchBar
import com.dhaval.echo.ui.components.EchoTopBar
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onEntryClick: (String) -> Unit,
    onEntityClick: (String) -> Unit = {},
    onProfileClick: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val aiAnswer by viewModel.aiAnswer.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = "Remember", onProfileClick = onProfileClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp)
        ) {
            EchoSearchBar(
                value = state.filter.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = "What are you trying to remember?"
            )
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filter.onlyFavorites,
                    onClick = { viewModel.onToggleFavorites(!state.filter.onlyFavorites) },
                    label = { Text("Favorites") },
                    leadingIcon = if (state.filter.onlyFavorites) {
                        { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
            Spacer(Modifier.height(16.dp))

            when {
                state.results.isNotEmpty() || state.entityMatches.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        // Hybrid recall: people, places, projects & feelings first.
                        if (state.entityMatches.isNotEmpty()) {
                            item {
                                EntityMatchesSection(state.entityMatches, onEntityClick = onEntityClick)
                            }
                        }
                        // An inferred connection, surfaced above the browsable memories.
                        if (state.filter.query.isEmpty()) {
                            state.echoConnection?.let { conn ->
                                item { EchoConnectionCard(conn, onOpen = { onEntryClick(conn.memoryId) }) }
                            }
                        }
                        // Echo's written answer to the query, and the "ask" affordance.
                        if (state.filter.query.isNotEmpty() && state.results.isNotEmpty()) {
                            item {
                                AskEchoSection(
                                    answer = aiAnswer,
                                    onAsk = viewModel::askEcho
                                )
                            }
                        }
                        if (state.results.isNotEmpty()) {
                            item {
                                Text(
                                    if (aiAnswer is AiAnswerState.Ready) "Memories Echo used" else "Memories",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        items(state.results, key = { it.entry.id }) { result ->
                            ResultCard(result, onClick = { onEntryClick(result.entry.id) })
                        }
                    }
                }
                state.filter.query.isNotEmpty() -> {
                    EchoEmptyState(
                        message = "I couldn't find that yet.",
                        description = "Try remembering a person, place, or moment instead.",
                        icon = Icons.Default.Search,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    // Empty query: surface an Echo connection + suggestions.
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        state.echoConnection?.let { conn ->
                            item { EchoConnectionCard(conn, onOpen = { onEntryClick(conn.memoryId) }) }
                        }
                        if (state.suggestions.isNotEmpty()) {
                            item {
                                Text(
                                    "Try remembering",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            item {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    state.suggestions.forEach { suggestion ->
                                        SuggestionChip(
                                            onClick = { viewModel.onQueryChanged(suggestion) },
                                            label = { Text(suggestion) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Echo's synthesised answer to the query, above the memories it drew from.
 * Key-gated: the button is always offered, but with no cloud key it resolves to
 * a gentle "add a key" prompt rather than a paragraph.
 */
@Composable
private fun AskEchoSection(answer: AiAnswerState, onAsk: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "ASK ECHO",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))

            when (answer) {
                is AiAnswerState.Ready -> Text(
                    answer.text,
                    style = MaterialTheme.typography.bodyLarge
                )
                AiAnswerState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Echo is thinking…", style = MaterialTheme.typography.bodyMedium)
                }
                AiAnswerState.NeedsKey -> Text(
                    "Add an AI key in Settings and Echo will write you an answer from these memories.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                AiAnswerState.Failed -> Text(
                    "I couldn't put an answer together just now. The memories below are what I found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                AiAnswerState.Idle -> Surface(
                    onClick = onAsk,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ask Echo about this",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Hybrid recall: entity hits (people, places, projects, feelings) as chips. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EntityMatchesSection(
    entities: List<com.dhaval.echo.data.db.EntityNode>,
    onEntityClick: (String) -> Unit
) {
    Column {
        Text(
            "People, places & topics",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            entities.forEach { e ->
                val n = e.memoryCount
                AssistChip(
                    onClick = { onEntityClick(e.id) },
                    label = {
                        Text("${e.name}  ·  ${if (n == 1) "1 memory" else "$n memories"}")
                    }
                )
            }
        }
    }
}

@Composable
private fun ResultCard(result: SearchResult, onClick: () -> Unit) {
    val entry = result.entry
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                entry.timestamp.format(DateTimeFormatter.ofPattern("EEE, d MMM • h:mm a")),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(entry.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val snippet = entry.summary ?: entry.transcription ?: entry.textContent
            if (!snippet.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    snippet.take(160),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 3
                )
            }
        }
    }
}

/** Surfaces a Stage-6 inferred connection: "Echo noticed this relates to X". */
@Composable
private fun EchoConnectionCard(conn: InferredConnectionView, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "ECHO NOTICED A CONNECTION",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "This memory connects to ${conn.entityName} — even though you didn't name it.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "\"${conn.memoryTitle}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Explore connection", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
