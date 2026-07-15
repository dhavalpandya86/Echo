package com.dhaval.echo.ui.settings.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.domain.ai.AICapability
import com.dhaval.echo.domain.ai.AIProvider
import com.dhaval.echo.ui.components.EchoTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            EchoTopBar(
                title = "AI Foundation Settings",
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
                    text = "Core Provider",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
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

            if (uiState.currentProvider != null) {
                item {
                    Divider()
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Active Services",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "These services are powered by your active provider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    ActiveServiceItem("Transcription", uiState.currentProvider!!.displayName)
                    ActiveServiceItem("Summarization", uiState.currentProvider!!.displayName)
                    ActiveServiceItem("Classification", uiState.currentProvider!!.displayName)
                    ActiveServiceItem("Semantic Search", uiState.currentProvider!!.displayName)
                    ActiveServiceItem("Conversational Memory", uiState.currentProvider!!.displayName)
                }

                item {
                    Divider()
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Provider Capabilities",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))
                    CapabilitiesList(uiState.currentProvider!!)
                }
                
                item {
                    Divider()
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Authentication",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("API Key") },
                        placeholder = { Text("Enter your key...") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("This is a placeholder for Sprint AI-01.") }
                    )
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
                    text = "Status: $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = "Selected")
            }
        }
    }
}

@Composable
private fun ActiveServiceItem(label: String, provider: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = provider,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun CapabilitiesList(provider: AIProvider) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapabilityItem("Offline Mode", provider.supportsOffline)
        CapabilityItem("Streaming API", provider.supportsStreaming)
        CapabilityItem("Vector Embeddings", provider.supportsEmbeddings)
        CapabilityItem("Vision Processing", provider.supportsVision)
        CapabilityItem("Audio Processing", provider.supportsAudio)
        CapabilityItem("Memory Relationships", provider.supportsRelationships)
        CapabilityItem("Semantic Search", provider.supportsSemanticSearch)
        CapabilityItem("Conversational Memory", provider.supportsConversation)
    }
}

@Composable
private fun CapabilityItem(label: String, isSupported: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
