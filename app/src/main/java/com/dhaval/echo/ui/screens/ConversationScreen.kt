package com.dhaval.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.domain.ai.Citation
import com.dhaval.echo.domain.ai.Message
import com.dhaval.echo.domain.ai.MessageRole
import com.dhaval.echo.ui.components.EchoCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = hiltViewModel(),
    onNavigateToEntry: (String) -> Unit,
    onOpenReview: () -> Unit = {},
    onProfileClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.messages.size, uiState.isThinking) {
        if (uiState.messages.isNotEmpty()) {
            scrollState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Reflect", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.startNewConversation() }) {
                        Icon(Icons.Default.Add, contentDescription = "New reflection")
                    }
                    com.dhaval.echo.ui.components.EchoProfileAvatar(onClick = onProfileClick)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // A search bar on top — same shape as Story and Remember — replaces the
            // old bottom chat input. Ask a reflection; results fill in below.
            com.dhaval.echo.ui.components.EchoSearchBar(
                value = uiState.currentQuestion,
                onValueChange = viewModel::onQuestionChange,
                placeholder = "What would you like to understand?",
                onSearch = viewModel::askQuestion,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            if (uiState.messages.isEmpty()) {
                WelcomeView(
                    suggestedQuestions = uiState.suggestedQuestions,
                    onQuestionClick = {
                        viewModel.onQuestionChange(it)
                        viewModel.askQuestion()
                    },
                    onOpenReview = onOpenReview,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.messages) { message ->
                        MessageBubble(message, onNavigateToEntry)
                    }
                    if (uiState.isThinking) {
                        item {
                            ThinkingIndicator()
                        }
                    }
                }
            }
        }
    }
}

private data class ReflectPrompt(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val description: String,
    val question: String
)

private val reflectPrompts = listOf(
    ReflectPrompt(
        Icons.Default.Article, "Summarize this week",
        "A narrative overview of your thoughts, actions, and milestones from the last seven days.",
        "Summarize what I've been thinking about and doing this week."
    ),
    ReflectPrompt(
        Icons.Default.Mood, "How has my mood changed?",
        "See the emotional shape of your recent memories and any patterns in it.",
        "How has my mood changed recently, and what seems to affect it?"
    ),
    ReflectPrompt(
        Icons.Default.TaskAlt, "What have I promised?",
        "The commitments and intentions you've spoken into Echo lately.",
        "What commitments and promises have I made recently?"
    ),
    ReflectPrompt(
        Icons.Default.Bolt, "What am I focused on?",
        "The people, projects, and ideas taking up your attention right now.",
        "What projects, people and topics am I most focused on right now?"
    )
)

@Composable
fun WelcomeView(
    suggestedQuestions: List<String>,
    onQuestionClick: (String) -> Unit,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    "What would you like to reflect on?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose a lens, or just ask in the bar above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        // The generated reflection — real, from your own memories.
        item {
            Surface(
                onClick = onOpenReview,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "See your reflection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            "A look back at your week and month, from your own memories.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
        items(reflectPrompts) { prompt ->
            ReflectPromptCard(prompt, onClick = { onQuestionClick(prompt.question) })
        }
    }
}

@Composable
private fun ReflectPromptCard(prompt: ReflectPrompt, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(prompt.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(prompt.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    prompt.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    onCitationClick: (String) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = message.content,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                if (message.reasoning != null && !isUser) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message.reasoning!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
        
        if (message.citations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Sources",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.citations.take(3).forEach { citation ->
                    CitationCard(citation, onCitationClick)
                }
            }
        }
    }
}

@Composable
fun CitationCard(
    citation: Citation,
    onClick: (String) -> Unit
) {
    Surface(
        onClick = { onClick(citation.memoryId) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(140.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                citation.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                citation.date.take(10), // Show only date
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

