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
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.ui.components.*
import com.dhaval.echo.ui.theme.EchoTheme
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEntry: (String) -> Unit,
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

                    if (!it.summary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SummarySection(it.summary)
                    }

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

                    if (uiState.relatedEntries.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(32.dp))
                        ProjectContextSection(uiState.entry?.title ?: "")

                        Spacer(modifier = Modifier.height(32.dp))
                        RelatedMemoriesSection(
                            relatedEntries = uiState.relatedEntries,
                            onEntryClick = onNavigateToEntry
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    
                    TranscriptSection(
                        transcript = it.transcription,
                        transcriptionStatus = it.transcriptionStatus,
                        analysisStatus = it.analysisStatus
                    )
                    
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
private fun SummarySection(summary: String) {
    Column {
        Text(
            text = "AI Summary",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 24.sp,
                letterSpacing = 0.2.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
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
private fun ProjectContextSection(title: String) {
    Column {
        Text(
            text = "Project Context",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        EchoCard(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Part of $title project",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "This memory contributes to your ongoing work on $title.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedMemoriesSection(
    relatedEntries: List<com.dhaval.echo.domain.timeline.TimelineEntry>,
    onEntryClick: (String) -> Unit
) {
    Column {
        Text(
            text = "Related Memories",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        relatedEntries.forEach { entry ->
            EchoCard(
                onClick = { onEntryClick(entry.id) },
                modifier = Modifier.padding(vertical = 4.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = entry.timestamp.format(DateTimeFormatter.ofPattern("MMM d, yyyy")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptSection(
    transcript: String?,
    transcriptionStatus: IntelligenceStatus = IntelligenceStatus.COMPLETED,
    analysisStatus: IntelligenceStatus = IntelligenceStatus.COMPLETED
) {
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
            val isProcessing = transcriptionStatus == IntelligenceStatus.PROCESSING || 
                           transcriptionStatus == IntelligenceStatus.TRANSCRIBING ||
                           analysisStatus == IntelligenceStatus.PROCESSING ||
                           analysisStatus == IntelligenceStatus.SUMMARIZING ||
                           analysisStatus == IntelligenceStatus.CLASSIFYING ||
                           analysisStatus == IntelligenceStatus.LINKING
            
            val statusText = when {
                transcriptionStatus == IntelligenceStatus.TRANSCRIBING -> "Transcribing audio..."
                analysisStatus == IntelligenceStatus.SUMMARIZING -> "Generating AI summary..."
                analysisStatus == IntelligenceStatus.CLASSIFYING -> "Classifying memory..."
                analysisStatus == IntelligenceStatus.LINKING -> "Finding related memories..."
                analysisStatus == IntelligenceStatus.ANALYZING_TIMELINE -> "Analyzing life patterns..."
                transcriptionStatus == IntelligenceStatus.PROCESSING || analysisStatus == IntelligenceStatus.PROCESSING -> "Processing..."
                transcriptionStatus == IntelligenceStatus.FAILED || analysisStatus == IntelligenceStatus.FAILED -> "AI processing failed."
                else -> null
            }

            if (isProcessing || statusText == "AI processing failed.") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(
                        text = statusText ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (statusText?.contains("failed") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Text(
                    text = transcript ?: "Transcription will appear here once processed by AI.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcript == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                )
            }
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
