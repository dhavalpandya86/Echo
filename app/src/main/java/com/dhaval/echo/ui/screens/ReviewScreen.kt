package com.dhaval.echo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.data.ai.ReviewPeriod
import com.dhaval.echo.ui.components.EchoCard
import com.dhaval.echo.ui.components.EchoTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { EchoTopBar(title = "Reflection", onBackClick = onNavigateBack) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Period switch.
            TabRow(
                selectedTabIndex = if (state.period == ReviewPeriod.WEEK) 0 else 1,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Tab(selected = state.period == ReviewPeriod.WEEK,
                    onClick = { viewModel.load(ReviewPeriod.WEEK) },
                    text = { Text("This week") })
                Tab(selected = state.period == ReviewPeriod.MONTH,
                    onClick = { viewModel.load(ReviewPeriod.MONTH) },
                    text = { Text("This month") })
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val reflection = state.reflection
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            reflection?.lead ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    reflection?.sections?.forEach { section ->
                        item {
                            EchoCard {
                                Text(
                                    section.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                section.items.forEach { item ->
                                    Text(
                                        "• $item",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(vertical = 2.dp)
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
