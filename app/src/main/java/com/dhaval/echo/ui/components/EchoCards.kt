package com.dhaval.echo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.ui.theme.EchoInk

/**
 * Echo brand card: 14dp radius, Surface-on-Paper by default (never white-on-white),
 * with a soft ink-tinted shadow. Quiet press feedback, no bounce.
 */
@Composable
fun EchoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "card_scale"
    )

    val shape = RoundedCornerShape(14.dp)
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 2.dp,
                shape = shape,
                ambientColor = EchoInk,
                spotColor = EchoInk,
                clip = false
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

@Composable
fun EchoInsightCard(
    title: String,
    description: String,
    type: com.dhaval.echo.domain.ai.InsightType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when (type) {
        com.dhaval.echo.domain.ai.InsightType.ACTIVE_PROJECT -> MaterialTheme.colorScheme.primaryContainer
        com.dhaval.echo.domain.ai.InsightType.RETURNING_IDEA -> MaterialTheme.colorScheme.secondaryContainer
        com.dhaval.echo.domain.ai.InsightType.EMERGING_INTEREST -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    EchoCard(
        onClick = onClick,
        modifier = modifier,
        containerColor = containerColor
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (type) {
                    com.dhaval.echo.domain.ai.InsightType.ACTIVE_PROJECT -> Icons.Default.AutoAwesome
                    com.dhaval.echo.domain.ai.InsightType.DORMANT_PROJECT -> Icons.Default.History
                    com.dhaval.echo.domain.ai.InsightType.RETURNING_IDEA -> Icons.Default.Lightbulb
                    else -> Icons.Default.Info
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun EchoTimelineCard(
    title: String,
    time: String,
    duration: String,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: IntelligenceStatus = IntelligenceStatus.COMPLETED,
    analysisStatus: IntelligenceStatus = IntelligenceStatus.COMPLETED,
    description: String? = null,
    relatedMemoriesCount: Int = 0,
    relevanceScore: Float = 0f
) {
    EchoCard(
        onClick = onClick,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    if (relevanceScore > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${relevanceScore.toInt()}% relevance",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    val isProcessing = status == IntelligenceStatus.PROCESSING || 
                                       status == IntelligenceStatus.TRANSCRIBING ||
                                       analysisStatus == IntelligenceStatus.PROCESSING ||
                                       analysisStatus == IntelligenceStatus.SUMMARIZING ||
                                       analysisStatus == IntelligenceStatus.CLASSIFYING ||
                                       analysisStatus == IntelligenceStatus.LINKING ||
                                       analysisStatus == IntelligenceStatus.ANALYZING_TIMELINE
                    if (isProcessing) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                if (relatedMemoriesCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Related to $relatedMemoriesCount memories",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusText = when {
                        status == IntelligenceStatus.TRANSCRIBING -> "Transcribing..."
                        analysisStatus == IntelligenceStatus.SUMMARIZING -> "Summarizing..."
                        analysisStatus == IntelligenceStatus.CLASSIFYING -> "Classifying..."
                        analysisStatus == IntelligenceStatus.LINKING -> "Finding links..."
                        analysisStatus == IntelligenceStatus.ANALYZING_TIMELINE -> "Analyzing timeline..."
                        status == IntelligenceStatus.PROCESSING || analysisStatus == IntelligenceStatus.PROCESSING -> "Processing..."
                        else -> null
                    }

                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = time,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = duration,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Tiny waveform preview placeholder
                Row(
                    modifier = Modifier.height(12.dp).fillMaxWidth(0.6f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(15) {
                        val height = (4..12).random().dp
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(height)
                                .clip(RoundedCornerShape(1.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        )
                    }
                }
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
fun EchoCollectionCard(
    name: String,
    count: Int,
    isAiGenerated: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    EchoCard(
        onClick = onClick,
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (isAiGenerated) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    "$count memories",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            }
        }
    }
}
