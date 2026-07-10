package com.dhaval.echo.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.dhaval.echo.ui.screens.CollectionsScreen
import com.dhaval.echo.ui.screens.CollectionDetailsScreen
import com.dhaval.echo.ui.screens.EntryDetailsScreen
import com.dhaval.echo.ui.screens.HomeScreen
import com.dhaval.echo.ui.screens.RecordScreen
import com.dhaval.echo.ui.screens.SearchScreen
import com.dhaval.echo.ui.screens.TimelineScreen
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment

import com.dhaval.echo.ui.screens.SettingsScreen

/**
 * Central Navigation Graph for Echo.
 * Orchestrates all screen transitions and argument passing.
 */
@Composable
fun EchoNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onNavigateToRecord = { navController.navigate(RecordRoute) },
                onNavigateToSearch = { navController.navigate(SearchRoute) },
                onNavigateToCollections = { navController.navigate(CollectionsRoute) },
                onNavigateToEntry = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                }
            )
        }

        composable<RecordRoute> {
            RecordScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<TimelineRoute> {
            TimelineScreen(
                onEntryClick = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                }
            )
        }

        composable<EntryDetailsRoute> {
            EntryDetailsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<SearchRoute> {
            SearchScreen(
                onEntryClick = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen()
        }

        composable<CollectionsRoute> {
            CollectionsScreen(
                onCollectionClick = { id ->
                    navController.navigate(CollectionDetailsRoute(id))
                }
            )
        }

        composable<CollectionDetailsRoute> {
            CollectionDetailsScreen(
                onNavigateBack = { navController.popBackStack() },
                onEntryClick = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                }
            )
        }
    }
}
