package com.dhaval.echo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.ui.components.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onEntryClick: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            EchoTopBar(title = "Search")
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            EchoSearchBar(
                value = state.filter.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = "Search memories, tags..."
            )

            Spacer(modifier = Modifier.height(24.dp))

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

            Spacer(modifier = Modifier.height(24.dp))

            if (state.results.isEmpty() && state.filter.query.isNotEmpty()) {
                EchoEmptyState(
                    message = "No results found.",
                    description = "Try searching for something else.",
                    icon = Icons.Default.Search
                )
            } else if (state.results.isEmpty()) {
                EchoEmptyState(
                    message = "Search your memories.",
                    description = "Find that specific moment you're looking for.",
                    icon = Icons.Default.Mic
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(state.results, key = { it.id }) { entry ->
                        EchoTimelineCard(
                            title = entry.title,
                            time = entry.timestamp.format(DateTimeFormatter.ofPattern("MMM d, HH:mm")),
                            duration = formatDuration(entry.durationMillis),
                            isFavorite = entry.isFavorite,
                            onFavoriteClick = { /* Handle favorite in search */ },
                            onClick = { onEntryClick(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}
