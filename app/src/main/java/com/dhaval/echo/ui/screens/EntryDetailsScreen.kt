package com.dhaval.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.ui.components.*
import com.dhaval.echo.ui.theme.EchoTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailsScreen(
    onNavigateBack: () -> Unit,
    viewModel: EntryDetailsViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
    collectionsViewModel: CollectionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by playbackViewModel.playbackState.collectAsState()
    val collectionsState by collectionsViewModel.uiState.collectAsState()
    
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCollectionPicker by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.entry) {
        uiState.entry?.let {
            playbackViewModel.play(it.audioPath)
        }
    }

    Scaffold(
        topBar = {
            EchoTopBar(
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(onClick = viewModel::toggleFavorite) {
                        val isFavorite = uiState.entry?.isFavorite == true
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, 
                            contentDescription = "Favorite",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { 
                        viewModel.deleteEntry()
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = uiState.error!!)
            }
        } else {
            uiState.entry?.let {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    EntryHeader(
                        title = it.title,
                        date = uiState.formattedDate,
                        onRenameClick = { showRenameDialog = true }
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    PlaybackCard(
                        state = playbackState,
                        viewModel = playbackViewModel
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    InfoSection(
                        duration = formatDuration(it.durationMillis),
                        tags = uiState.tags,
                        onAddTag = viewModel::addTag,
                        onRemoveTag = viewModel::removeTag
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    CollectionsSection(
                        collections = uiState.collections,
                        onAddClick = { showCollectionPicker = true },
                        onRemove = viewModel::removeFromCollection
                    )

                    Spacer(modifier = Modifier.height(32.dp))
                    
                    TranscriptSection(it.transcription)
                    
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentTitle = uiState.entry?.title ?: "",
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle ->
                viewModel.updateTitle(newTitle)
                showRenameDialog = false
            }
        )
    }

    if (showCollectionPicker) {
        CollectionPickerDialog(
            collections = collectionsState.collections.map { it.collection.name to it.collection.id },
            onDismiss = { showCollectionPicker = false },
            onSelect = { id ->
                viewModel.addToCollection(id)
                showCollectionPicker = false
            }
        )
    }
}

@Composable
private fun EntryHeader(
    title: String,
    date: String,
    onRenameClick: () -> Unit
) {
    Column {
        Text(
            text = date,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRenameClick) {
                Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PlaybackCard(
    state: com.dhaval.echo.domain.audio.PlaybackState,
    viewModel: PlaybackViewModel
) {
    EchoCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        PlaybackControls(
            state = state,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSeek = viewModel::seekTo,
            onSpeedChange = viewModel::setSpeed
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoSection(
    duration: String,
    tags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit
) {
    var showTagInput by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(text = duration, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEach { tag ->
                EchoTag(text = tag, onRemove = { onRemoveTag(tag) })
            }
            IconButton(
                onClick = { showTagInput = true },
                modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Tag", modifier = Modifier.size(16.dp))
            }
        }

        if (showTagInput) {
            AlertDialog(
                onDismissRequest = { showTagInput = false },
                title = { Text("Add Tag") },
                text = {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("Tag Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newTag.isNotBlank()) {
                            onAddTag(newTag.trim())
                            newTag = ""
                            showTagInput = false
                        }
                    }) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTagInput = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionsSection(
    collections: List<com.dhaval.echo.data.db.EchoCollection>,
    onAddClick: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column {
        Text(
            text = "Collections",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            collections.forEach { collection ->
                EchoTag(text = collection.name, onRemove = { onRemove(collection.id) })
            }
            IconButton(
                onClick = onAddClick,
                modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add to Collection", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TranscriptSection(transcript: String?) {
    Column {
        Text(
            text = "Transcript",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        EchoCard(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Text(
                text = transcript ?: "Transcription will appear here once processed by AI.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (transcript == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(currentTitle) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Memory") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(title) }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CollectionPickerDialog(
    collections: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Collection") },
        text = {
            LazyColumn {
                items(collections) { (name, id) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        modifier = Modifier.clickable { onSelect(id) }
                    )
                }
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}
