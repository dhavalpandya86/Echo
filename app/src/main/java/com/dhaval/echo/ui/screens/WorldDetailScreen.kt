package com.dhaval.echo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.ui.components.EchoCard
import com.dhaval.echo.ui.components.EchoEmptyState
import com.dhaval.echo.ui.components.EchoTopBar
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorldDetailScreen(
    onNavigateBack: () -> Unit,
    onEntityClick: (String) -> Unit,
    onEntryClick: (String) -> Unit,
    viewModel: WorldDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val world = state.world

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = world?.title ?: "World", onBackClick = onNavigateBack) }
    ) { padding ->
        if (!state.isLoading && world == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EchoEmptyState(
                    message = "This World has moved on.",
                    description = "As your memories change, Worlds reshape themselves.",
                    icon = Icons.Rounded.Public
                )
            }
        } else if (world != null) {
            val people = world.entities.filter { it.type == EntityType.PERSON }
            val places = world.entities.filter { it.type == EntityType.PLACE }
            val things = world.entities.filter {
                it.type != EntityType.PERSON && it.type != EntityType.PLACE
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 20.dp, end = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    Text(
                        text = "${world.entities.size} connections · " +
                            if (world.memories.size == 1) "1 memory" else "${world.memories.size} memories",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                entityGroup("People", people, onEntityClick)
                entityGroup("Places", places, onEntityClick)
                entityGroup("Topics & Projects", things, onEntityClick)

                if (world.memories.isNotEmpty()) {
                    item {
                        Text("Memories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(world.memories, key = { it.id }) { entry ->
                        EchoCard(onClick = { onEntryClick(entry.id) }) {
                            Text(
                                entry.title.ifBlank { "Untitled" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.createdAt.format(DateTimeFormatter.ofPattern("d MMM yyyy • HH:mm")),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.entityGroup(
    title: String,
    entities: List<EntityNode>,
    onEntityClick: (String) -> Unit
) {
    if (entities.isEmpty()) return
    item(key = "group_$title") {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entities.forEach { e ->
                    AssistChip(onClick = { onEntityClick(e.id) }, label = { Text(e.name) })
                }
            }
        }
    }
}
