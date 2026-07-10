package com.dhaval.echo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EchoTag(
    text: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (onRemove != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onRemove() },
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
