package com.dhaval.echo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.domain.weather.Weather
import com.dhaval.echo.ui.components.EchoProfileAvatar
import com.dhaval.echo.ui.theme.echoBreathe
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HomeUiState(
    val displayName: String? = null,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isSaving: Boolean = false,
    val durationMillis: Long = 0,
    val amplitude: Float = 0f,
    val errorMessage: String? = null,
    val recentRecordings: List<TimelineEntry> = emptyList(),
    val stats: HomeStats = HomeStats(),
    val insightOfDay: com.dhaval.echo.domain.ai.TimelineInsight? = null,
    // Today briefing (Phase 1) — the temporal focus shifts through the day.
    val leadKind: DayLead = DayLead.NONE,
    val yesterdayRecap: String? = null,
    val todayProgress: String? = null,
    val waitingCommitments: List<com.dhaval.echo.data.db.ExtractedItem> = emptyList(),
    val tomorrowCommitments: List<com.dhaval.echo.data.db.ExtractedItem> = emptyList(),
    val continueMemory: TimelineEntry? = null,
    val revisitMemory: TimelineEntry? = null
)

/** Which day the Today page leads with, following the time of day. */
enum class DayLead { YESTERDAY, TODAY, TOMORROW, NONE }

data class HomeStats(
    val todayCount: Int = 0,
    val totalCount: Int = 0,
    val streakDays: Int = 0
)

@Composable
fun HomeScreen(
    onNavigateToRecord: () -> Unit,
    onNavigateToTextEntry: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToCollections: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEntry: (String) -> Unit,
    onNavigateToTasks: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val weather by viewModel.weather.collectAsState()
    var fabExpanded by remember { mutableStateOf(false) }

    // Weather needs coarse location — ask once, load on grant, otherwise stay silent.
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.loadWeather() }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.loadWeather()
        else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TodayTopBar(onProfileClick = onNavigateToSettings) },
        floatingActionButton = {
            ExpandableFab(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onVoice = { fabExpanded = false; onNavigateToRecord() },
                onText = { fabExpanded = false; onNavigateToTextEntry() }
            )
        }
    ) { innerPadding ->
        if (fabExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { fabExpanded = false }
                    )
            )
        }
        HomeScreenContent(
            state = state,
            weather = weather,
            paddingValues = innerPadding,
            onEntryClick = onNavigateToEntry,
            onOpenCommitments = onNavigateToTasks
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayTopBar(onProfileClick: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "Echo",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        actions = { EchoProfileAvatar(onClick = onProfileClick) },
        // Opaque paper background so scrolled content passes *behind* the bar,
        // not through it (the wordmark/avatar must never overlap the list).
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun HomeScreenContent(
    state: HomeUiState,
    weather: Weather?,
    paddingValues: PaddingValues,
    onEntryClick: (String) -> Unit,
    onOpenCommitments: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding() + 8.dp,
            bottom = paddingValues.calculateBottomPadding() + 120.dp,
            start = 24.dp, end = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item { TodayHeader(state.displayName, weather) }

        // Reflection — its focus follows the day (morning→yesterday,
        // afternoon→today, night→tomorrow), always inside the same card shape.
        reflectionContent(state)?.let { (heading, body, muted) ->
            item {
                TodayCard {
                    CardHeader(Icons.Default.AutoAwesome, heading)
                    Spacer(Modifier.height(12.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (muted != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            muted,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // Commitments — the actual list of what's waiting + coming up.
        val commitments = (state.waitingCommitments + state.tomorrowCommitments).distinctBy { it.id }.take(3)
        if (commitments.isNotEmpty()) {
            item {
                TodayCard {
                    CardHeader(Icons.Default.EventNote, "Commitments")
                    Spacer(Modifier.height(12.dp))
                    commitments.forEachIndexed { i, c ->
                        CommitmentRow(c)
                        if (i < commitments.lastIndex) Spacer(Modifier.height(14.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    SoftButton(text = "View all commitments", onClick = onOpenCommitments)
                }
            }
        }

        // Ongoing focus — pick up where you left off.
        state.continueMemory?.let { m ->
            item {
                TodayCard(onClick = { onEntryClick(m.id) }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ONGOING FOCUS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                m.title.ifBlank { "Untitled" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Continue where you left off?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // A memory worth revisiting.
        state.revisitMemory?.let { m ->
            item {
                TodayCard(onClick = { onEntryClick(m.id) }) {
                    Text(
                        "MEMORY WORTH REVISITING",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(m.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val snippet = m.summary ?: m.transcription ?: m.textContent
                    if (!snippet.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "\"${snippet.take(120)}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        m.timestamp.format(DateTimeFormatter.ofPattern("d MMM yyyy")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/** Resolves the reflection card's (heading, body, mutedSecondary?) from the day phase. */
private fun reflectionContent(state: HomeUiState): Triple<String, String, String?>? = when (state.leadKind) {
    DayLead.YESTERDAY -> state.yesterdayRecap?.let { Triple("Yesterday", it, null) }
    DayLead.TODAY -> state.todayProgress?.let { Triple("Today so far", it, state.yesterdayRecap) }
    DayLead.TOMORROW -> {
        val body = if (state.tomorrowCommitments.isNotEmpty()) {
            val n = state.tomorrowCommitments.size
            "You have $n ${if (n == 1) "thing" else "things"} planned for tomorrow."
        } else "Tomorrow is open — rest easy."
        Triple("Looking ahead", body, state.todayProgress)
    }
    DayLead.NONE -> null
}

// ── Reusable Today building blocks (Stitch "Echo Narrative" card style) ──────

/** White, softly-lifted card, 24px radius, 20px padding. */
@Composable
private fun TodayCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Surface(
        modifier = base,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 3.dp
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

/** An icon in a soft tinted square + a bold title — the card header pattern. */
@Composable
private fun CardHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SoftButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            modifier = Modifier.padding(vertical = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CommitmentRow(item: com.dhaval.echo.data.db.ExtractedItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (item.kind == com.dhaval.echo.data.db.ItemKind.REMINDER) Icons.Default.NotificationsNone else Icons.Default.CheckCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
            item.dueAtMillis?.let {
                Text(formatDue(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun TodayHeader(displayName: String?, weather: Weather?) {
    val today = LocalDate.now()
    val dateLine = today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())).uppercase(Locale.getDefault())
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greetingBase = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val greeting = if (displayName.isNullOrBlank()) greetingBase else "$greetingBase, $displayName"

    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = dateLine,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        weather?.let {
            Spacer(Modifier.height(8.dp))
            WeatherLine(it)
        }
    }
}

@Composable
private fun WeatherLine(weather: Weather) {
    val icon = when {
        weather.condition.contains("Clear", true) || weather.condition.contains("clear", true) -> Icons.Rounded.WbSunny
        weather.condition.contains("rain", true) || weather.condition.contains("drizzle", true) ||
            weather.condition.contains("thunder", true) || weather.condition.contains("snow", true) -> Icons.Rounded.WaterDrop
        else -> Icons.Rounded.Cloud
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        val place = if (weather.city.isNotBlank()) " in ${weather.city}" else ""
        Text(
            "${weather.temperatureC}°C • ${weather.condition}$place",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

private fun formatDue(millis: Long): String {
    val dt = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("EEE, d MMM • HH:mm"))
}

@Composable
private fun ExpandableFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onVoice: () -> Unit,
    onText: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.7f),
            exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.7f)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MiniFabItem(icon = Icons.Default.Edit, label = "Write", onClick = onText)
                MiniFabItem(icon = Icons.Default.Mic, label = "Record", onClick = onVoice)
            }
        }
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = if (!expanded) Modifier.echoBreathe() else Modifier
        ) {
            AnimatedContent(targetState = expanded, label = "fab_icon") { isExpanded ->
                if (isExpanded) Icon(Icons.Default.Close, contentDescription = "Close")
                else Icon(Icons.Default.Add, contentDescription = "New Entry")
            }
        }
    }
}

@Composable
private fun MiniFabItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}
