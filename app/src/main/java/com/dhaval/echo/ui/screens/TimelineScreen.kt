package com.dhaval.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.ui.components.EchoEmptyState
import com.dhaval.echo.ui.components.EchoSearchBar
import com.dhaval.echo.ui.components.EchoTopBar
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onEntryClick: (String) -> Unit,
    onProfileClick: () -> Unit = {},
    onAddOnDate: (Long) -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val storyNarrative by viewModel.storyNarrative.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    // The month the calendar is showing — the user can page back and forward
    // through it independently of which day is selected.
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    // Month / Year / Day, like the Samsung Calendar the user asked to mirror.
    var calendarView by remember { mutableStateOf(CalendarView.MONTH) }

    // Device-calendar (Google/Samsung/phone) events, shown alongside memories.
    val phoneEvents by viewModel.phoneEvents.collectAsState()
    val context = LocalContext.current
    var calendarGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarGranted = granted
        if (granted) viewModel.loadPhoneCalendar()
    }
    // Load once if already permitted.
    LaunchedEffect(Unit) { if (calendarGranted) viewModel.loadPhoneCalendar() }
    // Phone-event dates for month-grid dots, and the selected day's events.
    val phoneEventDates = remember(phoneEvents) { phoneEvents.map { it.date }.toSet() }
    val selectedDayEvents = remember(phoneEvents, selectedDate) {
        phoneEvents.filter { it.date == selectedDate }.sortedBy { it.beginMillis }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = "Story", onProfileClick = onProfileClick) }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            // Header: the month + how much of the story is recorded.
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    state.monthLabel.ifBlank { "Your story" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (state.memoryCount > 0) {
                    Text(
                        "${state.memoryCount} ${if (state.memoryCount == 1) "memory" else "memories"} recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            EchoSearchBar(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = "Look back through your story…",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            if (state.days.isEmpty() && !state.isLoading) {
                EchoEmptyState(
                    message = if (state.searchQuery.isEmpty()) "Your story begins here." else "Nothing from that moment yet.",
                    description = if (state.searchQuery.isEmpty()) "Every memory you capture becomes part of the story of your life." else "Try a different moment, person, or place.",
                    icon = Icons.Default.Mic,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()

                // Memory counts per day, and where each day's header sits in the list
                // (item 0 is the calendar) — so tapping a calendar day scrolls to it.
                val countByDate = state.days.associate { d ->
                    d.date to d.parts.sumOf { it.entries.size }
                }
                // Leading items before the first day header: the story narrative
                // (when present) and the calendar. Scroll targets must count them.
                val showNarrative = storyNarrative != null && state.searchQuery.isEmpty()
                // Leading items before the first day header: story narrative (0/1) +
                // view switcher + calendar + add-on-date bar (3 when there are lived days).
                val leadingItems = (if (showNarrative) 1 else 0) +
                    (if (countByDate.isNotEmpty()) 3 else 0)
                val indexByDate = remember(state.days, leadingItems) {
                    val map = HashMap<LocalDate, Int>()
                    var idx = leadingItems
                    state.days.forEach { d ->
                        map[d.date] = idx
                        idx += 1 + d.parts.size
                    }
                    map
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    if (showNarrative) {
                        item(key = "story_narrative") {
                            StoryNarrativeHeader(storyNarrative!!)
                        }
                    }
                    if (countByDate.isNotEmpty()) {
                        item(key = "calendar_view_switcher") {
                            CalendarViewSwitcher(
                                selected = calendarView,
                                onSelect = { calendarView = it }
                            )
                        }
                        item(key = "life_calendar") {
                            when (calendarView) {
                                CalendarView.MONTH -> LifeCalendar(
                                    month = displayedMonth,
                                    countByDate = countByDate,
                                    selectedDate = selectedDate,
                                    phoneEventDates = phoneEventDates,
                                    onPrevMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                                    onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                                    onDayClick = { date ->
                                        viewModel.onDateSelected(date)
                                        indexByDate[date]?.let { i ->
                                            scope.launch { listState.animateScrollToItem(i) }
                                        }
                                    }
                                )
                                CalendarView.YEAR -> YearGrid(
                                    year = displayedMonth.year,
                                    countByDate = countByDate,
                                    onPrevYear = { displayedMonth = displayedMonth.minusYears(1) },
                                    onNextYear = { displayedMonth = displayedMonth.plusYears(1) },
                                    onMonthClick = { ym ->
                                        displayedMonth = ym
                                        calendarView = CalendarView.MONTH
                                    }
                                )
                                CalendarView.DAY -> DayView(
                                    day = selectedDate,
                                    parts = state.days.firstOrNull { it.date == selectedDate }?.parts.orEmpty(),
                                    phoneEvents = selectedDayEvents,
                                    calendarConnected = calendarGranted,
                                    onConnectCalendar = {
                                        calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                                    },
                                    onPrevDay = { viewModel.onDateSelected(selectedDate.minusDays(1)) },
                                    onNextDay = { viewModel.onDateSelected(selectedDate.plusDays(1)) },
                                    onEntryClick = onEntryClick
                                )
                            }
                        }
                        // Samsung-style quick add for the selected day.
                        item(key = "add_on_date") {
                            AddOnDateBar(
                                date = selectedDate,
                                onClick = { onAddOnDate(selectedDate.toEpochDay()) }
                            )
                        }
                    }
                    // Day view shows just the selected day (inside the calendar card);
                    // Month/Year keep the full scrollable timeline below.
                    val timelineDays = if (calendarView == CalendarView.DAY) emptyList() else state.days
                    timelineDays.forEach { day ->
                        item(key = "day_${day.date}") {
                            Text(
                                day.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        day.parts.forEach { part ->
                            item(key = "part_${day.date}_${part.part}") {
                                PartRow(part, onEntryClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Life Calendar: a month grid where each day's colour intensity reflects how
 * much you captured that day — the rhythm of a month at a glance. Tap a lived day
 * to jump to its memories.
 */
/**
 * The "story so far": an AI-written narration of recent memories + mood, shown
 * above the calendar. Only rendered when a cloud key produced one; the free tier
 * relies on the count line in the screen header instead.
 */
@Composable
private fun StoryNarrativeHeader(narrative: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "YOUR STORY SO FAR",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                narrative,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** The three calendar layouts, mirroring Samsung Calendar's Month / Year / Day. */
private enum class CalendarView(val label: String) { MONTH("Month"), YEAR("Year"), DAY("Day") }

/** Segmented Month | Year | Day switcher. */
@Composable
private fun CalendarViewSwitcher(selected: CalendarView, onSelect: (CalendarView) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CalendarView.entries.forEach { view ->
            val isSel = view == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(view) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    view.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSel) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** Year overview: 12 mini-months; months with memories are marked, tap to open one. */
@Composable
private fun YearGrid(
    year: Int,
    countByDate: Map<LocalDate, Int>,
    onPrevYear: () -> Unit,
    onNextYear: () -> Unit,
    onMonthClick: (YearMonth) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onPrevYear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous year")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onNextYear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next year")
                }
            }
        }
        (1..12).chunked(3).forEach { rowMonths ->
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                rowMonths.forEach { m ->
                    val ym = YearMonth.of(year, m)
                    val memoryDays = countByDate.keys.count { it.year == year && it.monthValue == m }
                    Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        MiniMonth(ym, memoryDays, onClick = { onMonthClick(ym) })
                    }
                }
            }
        }
    }
}

/** A compact month cell for the Year view: name + a dot per memory-day. */
@Composable
private fun MiniMonth(month: YearMonth, memoryDays: Int, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            month.format(DateTimeFormatter.ofPattern("MMM")),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (memoryDays > 0) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (memoryDays == 0) "—" else if (memoryDays == 1) "1 day" else "$memoryDays days",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

/** Day view: the selected day with prev/next paging, its memories, and phone events. */
@Composable
private fun DayView(
    day: LocalDate,
    parts: List<StoryPart>,
    phoneEvents: List<com.dhaval.echo.data.calendar.PhoneEvent>,
    calendarConnected: Boolean,
    onConnectCalendar: () -> Unit,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onEntryClick: (String) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                day.format(DateTimeFormatter.ofPattern("EEEE, d MMM")),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = onPrevDay, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onNextDay, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                }
            }
        }

        // Events from the user's phone / Google / Samsung calendars for this day.
        if (!calendarConnected) {
            ConnectCalendarPrompt(onConnectCalendar)
        } else if (phoneEvents.isNotEmpty()) {
            Text(
                "On your calendar",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            phoneEvents.forEach { PhoneEventRow(it) }
            Spacer(Modifier.height(12.dp))
        }

        if (parts.isEmpty()) {
            Text(
                "No memories on this day yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            parts.forEach { part -> PartRow(part, onEntryClick) }
        }
    }
}

/** A single device-calendar event row. */
@Composable
private fun PhoneEventRow(event: com.dhaval.echo.data.calendar.PhoneEvent) {
    val time = if (event.allDay) "All day" else {
        java.time.Instant.ofEpochMilli(event.beginMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                buildString {
                    append(time)
                    event.calendarName?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
    }
}

/** Invitation to connect the device calendar (asks for READ_CALENDAR). */
@Composable
private fun ConnectCalendarPrompt(onConnect: () -> Unit) {
    Surface(
        onClick = onConnect,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Show your calendar here", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Bring in events from your phone, Google and Samsung calendars.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun LifeCalendar(
    month: YearMonth,
    countByDate: Map<LocalDate, Int>,
    selectedDate: LocalDate,
    phoneEventDates: Set<LocalDate> = emptySet(),
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (LocalDate) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val today = LocalDate.now()
    val firstDow = month.atDay(1).dayOfWeek.value // Mon=1 … Sun=7
    val daysInMonth = month.lengthOfMonth()
    val maxCount = (countByDate.values.maxOrNull() ?: 1).coerceAtLeast(1)

    Column {
        // Month header with prev/next paging — move freely across months.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = onPrevMonth, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onNextMonth, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                Text(
                    d, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // Cells: leading blanks + each day of the month, 7 per row.
        val cells = (1 until firstDow).map { null } + (1..daysInMonth).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                for (i in 0 until 7) {
                    val date = week.getOrNull(i)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val count = countByDate[date] ?: 0
                            val alpha = if (count == 0) 0f else 0.25f + 0.6f * (count.toFloat() / maxCount)
                            val isSelected = date == selectedDate
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(primary.copy(alpha = alpha))
                                    .then(
                                        when {
                                            // The tapped day gets a solid ring; today a lighter one.
                                            isSelected -> Modifier.border(2.dp, primary, RoundedCornerShape(10.dp))
                                            date == today -> Modifier.border(1.5.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                            else -> Modifier
                                        }
                                    )
                                    // Every day is tappable now — so any day can be selected and added to.
                                    .clickable { onDayClick(date) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (count > 0) Color.White
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                                // A small dot marks a day that has phone-calendar events.
                                if (date in phoneEventDates) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 3.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(if (count > 0) Color.White else primary)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A quick-add bar for the selected calendar day, à la Samsung Calendar's
 * "Add event on 21 Jul". Tapping it opens a new memory dated to that day.
 */
@Composable
private fun AddOnDateBar(date: LocalDate, onClick: () -> Unit) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Add a memory for today"
        else -> "Add a memory on ${date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))}"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

/** One time-of-day section on the threaded timeline: a rail + its memory cards. */
@Composable
private fun PartRow(part: StoryPart, onEntryClick: (String) -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        // Rail: a continuous line with a dot marking this part.
        Box(Modifier.width(24.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
            Text(
                part.part.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(10.dp))
            part.entries.forEachIndexed { i, entry ->
                StoryMemoryCard(entry, onClick = { onEntryClick(entry.id) })
                if (i < part.entries.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StoryMemoryCard(entry: TimelineEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 2.dp
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.timestamp.format(DateTimeFormatter.ofPattern("h:mm a")),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val snippet = entry.summary ?: entry.transcription ?: entry.textContent
                if (!snippet.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        snippet.take(140),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 3
                    )
                }
            }

            // A memory with a photo shows it here, so the story reads visually at a
            // glance rather than as a wall of text.
            entry.imagePaths?.firstOrNull()?.let { photo ->
                Spacer(Modifier.width(12.dp))
                AsyncImage(
                    model = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
    }
}
