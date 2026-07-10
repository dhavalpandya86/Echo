package com.dhaval.echo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhaval.echo.ui.components.EchoCard
import com.dhaval.echo.ui.components.EchoTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    Scaffold(
        topBar = {
            EchoTopBar(title = "Settings")
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsGroup(title = "General") {
                SettingsItem(icon = Icons.Default.Person, title = "Account", subtitle = "Private")
                SettingsItem(icon = Icons.Default.Language, title = "Language", subtitle = "English")
                SettingsItem(icon = Icons.Default.Palette, title = "Appearance", subtitle = "System")
            }

            SettingsGroup(title = "Privacy & Security") {
                SettingsItem(icon = Icons.Default.Lock, title = "Passcode Lock", subtitle = "Off")
                SettingsItem(icon = Icons.Default.Shield, title = "Privacy Policy")
            }

            SettingsGroup(title = "Storage") {
                SettingsItem(icon = Icons.Default.Cloud, title = "Cloud Backup", subtitle = "Coming Soon", enabled = false)
                SettingsItem(icon = Icons.Default.SdCard, title = "Local Storage", subtitle = "1.2 MB used")
            }

            SettingsGroup(title = "Echo Premium") {
                SettingsItem(
                    icon = Icons.Default.AutoAwesome, 
                    title = "AI Transcription", 
                    subtitle = "Join waitlist",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(24.dp))
            
            Text(
                text = "Echo v1.0.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        EchoCard {
            content()
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) color else color.copy(alpha = 0.3f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) color else color.copy(alpha = 0.3f)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = if (enabled) 1f else 0.5f)
                )
            }
        }
        if (enabled) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
