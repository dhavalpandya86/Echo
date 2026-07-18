package com.dhaval.echo.ui.settings.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.R
import com.dhaval.echo.domain.ai.AIProvider
import com.dhaval.echo.domain.ai.AIProviderStatus
import com.dhaval.echo.domain.ai.STTProvider
import com.dhaval.echo.ui.components.EchoTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.currentProvider?.id) {
        apiKeyInput = ""
        showApiKey = false
    }

    Scaffold(
        topBar = {
            EchoTopBar(
                title = "AI Settings",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            item {
                Text(
                    text = "AI Provider",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Select the engine that powers your memory intelligence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(uiState.availableProviders) { provider ->
                ProviderCard(
                    provider = provider,
                    isSelected = provider.id == uiState.currentProvider?.id,
                    onSelect = { viewModel.onProviderSelected(provider.id) }
                )
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.stt_engine_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.stt_engine_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(uiState.availableSttProviders) { provider ->
                SttProviderCard(
                    provider = provider,
                    isSelected = provider.id == uiState.currentSttProvider?.id,
                    onSelect = { viewModel.onSttProviderSelected(provider.id) }
                )
            }

            val currentProvider = uiState.currentProvider
            if (currentProvider != null && requiresApiKey(currentProvider.id)) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "API Key",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = apiKeyDescription(currentProvider.id),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))

                    if (uiState.apiKeyIsSet) {
                        ApiKeySetCard(
                            providerName = currentProvider.displayName,
                            onClear = { viewModel.clearApiKey() }
                        )
                    } else {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("API Key") },
                            placeholder = { Text("sk-ant-...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showApiKey) "Hide key" else "Show key"
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                viewModel.saveApiKey(apiKeyInput)
                                apiKeyInput = ""
                            },
                            enabled = apiKeyInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save API Key")
                        }
                    }
                }
            }

            if (currentProvider != null) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Capabilities",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))
                    CapabilitiesList(currentProvider)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    provider: AIProvider,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val status by provider.status.collectAsState()

    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(status)
                )
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SttProviderCard(
    provider: STTProvider,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        enabled = provider.isEnabled,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (provider.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    text = provider.statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (provider.isEnabled && isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ApiKeySetCard(providerName: String, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("API key is set", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("$providerName is ready to use.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClear) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CapabilitiesList(provider: AIProvider) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapabilityItem("Offline Mode", provider.supportsOffline)
        CapabilityItem("Streaming", provider.supportsStreaming)
        CapabilityItem("Vision (images)", provider.supportsVision)
        CapabilityItem("Vector Embeddings", provider.supportsEmbeddings)
        CapabilityItem("Audio Processing", provider.supportsAudio)
        CapabilityItem("Memory Relationships", provider.supportsRelationships)
        CapabilityItem("Semantic Search", provider.supportsSemanticSearch)
        CapabilityItem("Conversation", provider.supportsConversation)
    }
}

@Composable
private fun CapabilityItem(label: String, isSupported: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isSupported) Icons.Default.Check else Icons.Default.Info,
            contentDescription = null,
            tint = if (isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSupported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

private fun requiresApiKey(providerId: String) = providerId in listOf("claude", "openai", "gemini")

private fun apiKeyDescription(providerId: String) = when (providerId) {
    "claude" -> "Get your key at console.anthropic.com"
    "openai" -> "Get your key at platform.openai.com"
    "gemini" -> "Get your key at aistudio.google.com"
    else -> ""
}

@Composable
private fun statusLabel(status: AIProviderStatus) = when (status) {
    AIProviderStatus.Available -> "Ready"
    AIProviderStatus.NeedsApiKey -> "Needs API key"
    AIProviderStatus.Unavailable -> "Unavailable"
    AIProviderStatus.Offline -> "Offline"
    else -> "Unknown"
}

@Composable
private fun statusColor(status: AIProviderStatus) = when (status) {
    AIProviderStatus.Available -> MaterialTheme.colorScheme.primary
    AIProviderStatus.NeedsApiKey -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
