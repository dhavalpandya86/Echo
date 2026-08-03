package com.dhaval.echo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Hub
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
import com.dhaval.echo.ui.components.EchoCard
import com.dhaval.echo.ui.components.EchoEmptyState
import com.dhaval.echo.ui.components.EchoTopBar

@Composable
fun EntitiesScreen(
    onNavigateBack: () -> Unit,
    onEntityClick: (String) -> Unit,
    viewModel: EntitiesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = "People & Topics", onBackClick = onNavigateBack) }
    ) { padding ->
        if (!state.isLoading && state.groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EchoEmptyState(
                    message = "Nothing here yet",
                    description = "As you add memories, the people, projects and topics Echo recognizes will appear here.",
                    icon = Icons.Default.Hub
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.groups.forEach { group ->
                    item(key = "h_${group.type}") {
                        Text(
                            text = group.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(group.entities, key = { it.id }) { entity ->
                        EntityRow(entity = entity, onClick = { onEntityClick(entity.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EntityRow(entity: EntityNode, onClick: () -> Unit) {
    EchoCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entity.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val count = entity.memoryCount
                Text(
                    text = if (count == 1) "1 memory" else "$count memories",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
