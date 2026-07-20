package com.dhaval.echo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.dhaval.echo.ui.components.EchoTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEntryScreen(
    onNavigateBack: () -> Unit,
    onEntrySaved: (String) -> Unit,
    viewModel: TextEntryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.savedEntryId) {
        uiState.savedEntryId?.let { onEntrySaved(it) }
    }

    var pendingCameraFilePath by remember { mutableStateOf("") }
    var pendingDictationTarget by remember { mutableStateOf(DictationTarget.BODY) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startDictation(pendingDictationTarget)
    }

    fun toggleDictation(target: DictationTarget) {
        if (uiState.isRecording) {
            viewModel.stopDictation()
        } else if (!uiState.isTranscribing) {
            val perm = Manifest.permission.RECORD_AUDIO
            if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                viewModel.startDictation(target)
            } else {
                pendingDictationTarget = target
                micPermissionLauncher.launch(perm)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.addImages(uris)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCameraFilePath.isNotBlank()) {
            viewModel.onCameraCapture(pendingCameraFilePath)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = viewModel.createCameraFile()
            pendingCameraFilePath = file.absolutePath
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraLauncher.launch(uri)
        }
    }

    fun launchCamera() {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            val file = viewModel.createCameraFile()
            pendingCameraFilePath = file.absolutePath
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(permission)
        }
    }

    Scaffold(
        topBar = {
            EchoTopBar(
                title = "New Entry",
                onBackClick = onNavigateBack,
                actions = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = uiState.textContent.isNotBlank() || uiState.imagePaths.isNotEmpty()
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                placeholder = {
                    Text(
                        "Title (optional)",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedIndicatorColor = MaterialTheme.colorScheme.background,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.background
                ),
                trailingIcon = {
                    MicButton(
                        active = uiState.isRecording && uiState.dictationTarget == DictationTarget.TITLE,
                        busy = uiState.isTranscribing && uiState.dictationTarget == DictationTarget.TITLE,
                        onClick = { toggleDictation(DictationTarget.TITLE) }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            TextField(
                value = uiState.textContent,
                onValueChange = viewModel::onTextChange,
                placeholder = {
                    Text(
                        "What's on your mind?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        lineHeight = 28.sp
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedIndicatorColor = MaterialTheme.colorScheme.background,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.background
                ),
                trailingIcon = {
                    MicButton(
                        active = uiState.isRecording && uiState.dictationTarget == DictationTarget.BODY,
                        busy = uiState.isTranscribing && uiState.dictationTarget == DictationTarget.BODY,
                        onClick = { toggleDictation(DictationTarget.BODY) }
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                minLines = 8
            )

            if (uiState.imagePaths.isNotEmpty()) {
                ImageGrid(
                    paths = uiState.imagePaths,
                    onRemove = viewModel::removeImage
                )
            }

            uiState.error?.let { error ->
                Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
                OutlinedButton(
                    onClick = { launchCamera() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Camera")
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Mic toggle for a text field: tap to record, tap again to stop; spins while transcribing. */
@Composable
private fun MicButton(active: Boolean, busy: Boolean, onClick: () -> Unit) {
    when {
        busy -> CircularProgressIndicator(
            modifier = Modifier.size(22.dp).padding(2.dp), strokeWidth = 2.dp
        )
        else -> IconButton(onClick = onClick) {
            Icon(
                imageVector = if (active) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (active) "Stop dictation" else "Dictate",
                tint = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ImageGrid(paths: List<String>, onRemove: (String) -> Unit) {
    val columns = 3
    val rows = (paths.size + columns - 1) / columns

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    if (index < paths.size) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                            AsyncImage(
                                model = paths[index],
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                    .clickable { onRemove(paths[index]) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
