package com.dhaval.echo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.ui.components.PlaybackControls
import com.dhaval.echo.ui.components.TimelineItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailsScreen(
    onNavigateBack: () -> Unit,
    onEntryClick: (String) -> Unit,
    viewModel: CollectionDetailsViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by playbackViewModel.playbackState.collectAsState()
    
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(uiState.collection?.name ?: "Collection") 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings in this collection yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.entries, key = { it.id }) { entry ->
                    TimelineItem(
                        entry = entry,
                        onClick = { onEntryClick(entry.id) }
                    )
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                PlaybackControls(
                    state = playbackState,
                    onTogglePlayPause = playbackViewModel::togglePlayPause,
                    onSeek = playbackViewModel::seekTo,
                    onSpeedChange = playbackViewModel::setSpeed
                )
            }
        }
    }
}
