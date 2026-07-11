package com.dhaval.echo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dhaval.echo.ui.navigation.EchoNavGraph
import com.dhaval.echo.ui.navigation.TopLevelDestination
import com.dhaval.echo.ui.theme.EchoTheme

import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.domain.auth.AuthState
import com.dhaval.echo.ui.auth.AuthViewModel
import com.dhaval.echo.ui.navigation.*

/**
 * Root Composable for the Echo application.
 * Manages global UI state, Navigation Bar, and the NavHost.
 */
@Composable
fun EchoApp(
    viewModel: AuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()

    EchoTheme {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        val isAuthenticated = authState is AuthState.Authenticated
        
        Scaffold(
            bottomBar = {
                if (isAuthenticated && shouldShowBottomBar(currentDestination)) {
                    EchoBottomBar(
                        destinations = TopLevelDestination.entries,
                        onNavigateToDestination = { destination ->
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        currentDestination = currentDestination
                    )
                }
            }
        ) { innerPadding ->
            EchoNavGraph(
                navController = navController,
                authState = authState,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

private fun shouldShowBottomBar(destination: androidx.navigation.NavDestination?): Boolean {
    if (destination == null) return false
    return TopLevelDestination.entries.any { destination.hasRoute(it.route::class) }
}

@Composable
private fun EchoBottomBar(
    destinations: List<TopLevelDestination>,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    currentDestination: androidx.navigation.NavDestination?
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        destinations.forEach { destination ->
            val selected = currentDestination?.hierarchy?.any {
                it.hasRoute(destination.route::class)
            } == true

            NavigationBarItem(
                selected = selected,
                onClick = { onNavigateToDestination(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        modifier = Modifier.size(26.dp)
                    )
                },
                label = { 
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    ) 
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                    unselectedTextColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
