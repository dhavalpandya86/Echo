package com.dhaval.echo.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.util.Log
import com.dhaval.echo.MainActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dhaval.echo.domain.auth.AuthState
import com.dhaval.echo.ui.auth.*
import com.dhaval.echo.ui.screens.*
import com.dhaval.echo.ui.settings.ai.AiSettingsScreen

/**
 * Central Navigation Graph for Echo.
 * Orchestrates all screen transitions and argument passing.
 */
@Composable
fun EchoNavGraph(
    navController: NavHostController,
    authState: AuthState,
    modifier: Modifier = Modifier
) {
    // --- DEVELOPMENT BYPASS: Always start at Home ---
    val startDestination: Any = HomeRoute
    // ------------------------------------------------

    // Auto-navigation on auth state change is disabled for bypass

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        // Auth Graph
        composable<WelcomeRoute> {
            val authViewModel: AuthViewModel = hiltViewModel()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            
            // Get the Singleton helpers from Hilt
            val googleAuthHelper = remember { GoogleAuthHelper(context) }
            val activity = context as? MainActivity
            val facebookAuthHelper = activity?.facebookAuthHelper

            val error by authViewModel.error.collectAsState()

            WelcomeScreen(
                onCreateAccount = { navController.navigate(CreateAccountRoute) },
                onLogin = { navController.navigate(LoginRoute) },
                onPhoneLogin = { navController.navigate(PhoneLoginRoute) },
                onGoogleSignIn = {
                    scope.launch {
                        Log.d("EchoNavGraph", "Google Sign-In clicked")
                        googleAuthHelper.signIn(context)
                            .onSuccess { token ->
                                Log.d("EchoNavGraph", "ID Token received successfully")
                                authViewModel.loginWithGoogle(token)
                            }
                            .onFailure { error ->
                                Log.e("EchoNavGraph", "Google Sign-In failed", error)
                                authViewModel.setError(error.message ?: "Google Sign-In failed")
                            }
                    }
                },
                onFacebookSignIn = {
                    scope.launch {
                        if (activity != null && facebookAuthHelper != null) {
                            val token = facebookAuthHelper.signIn(activity)
                            token?.let {
                                authViewModel.loginWithFacebook(it)
                            }
                        }
                    }
                },
                error = error
            )
        }

        composable<LoginRoute> {
            val authViewModel: AuthViewModel = hiltViewModel()
            val isLoading by authViewModel.isLoading.collectAsState()
            val error by authViewModel.error.collectAsState()
            
            LoginScreen(
                onNavigateBack = { navController.popBackStack() },
                onLogin = { email, password -> authViewModel.loginWithEmail(email, password) },
                onForgotPassword = { navController.navigate(ForgotPasswordRoute) },
                isLoading = isLoading,
                error = error
            )
        }

        composable<ForgotPasswordRoute> {
            val authViewModel: AuthViewModel = hiltViewModel()
            val isLoading by authViewModel.isLoading.collectAsState()
            val error by authViewModel.error.collectAsState()
            
            ForgotPasswordScreen(
                onNavigateBack = { navController.popBackStack() },
                onResetPassword = { email -> authViewModel.sendPasswordReset(email) },
                isLoading = isLoading,
                error = error
            )
        }

        composable<CreateAccountRoute> {
            val authViewModel: AuthViewModel = hiltViewModel()
            val isLoading by authViewModel.isLoading.collectAsState()
            val error by authViewModel.error.collectAsState()
            
            CreateAccountScreen(
                onNavigateBack = { navController.popBackStack() },
                onCreateAccount = { name, email, password -> 
                    authViewModel.createAccount(name, email, password) 
                },
                isLoading = isLoading,
                error = error
            )
        }

        composable<PhoneLoginRoute> {
            // Placeholder for Phone Login
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Phone Login - Coming Soon")
            }
        }

        composable<OtpVerificationRoute> {
            // Placeholder for OTP
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("OTP Verification - Coming Soon")
            }
        }

        // Main App Graph
        composable<HomeRoute> {
            HomeScreen(
                onNavigateToRecord = { navController.navigate(RecordRoute) },
                onNavigateToTextEntry = { navController.navigate(TextEntryRoute) },
                onNavigateToSearch = { navController.navigate(SearchRoute) },
                onNavigateToCollections = { navController.navigate(CollectionsRoute) },
                onNavigateToSettings = { navController.navigate(SettingsRoute) },
                onNavigateToTasks = { navController.navigate(TasksRoute) },
                onNavigateToEntry = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                }
            )
        }

        composable<TasksRoute> {
            TasksScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenMemory = { entryId -> navController.navigate(EntryDetailsRoute(entryId)) }
            )
        }

        composable<EntitiesRoute> {
            EntitiesScreen(
                onNavigateBack = { navController.popBackStack() },
                onEntityClick = { id -> navController.navigate(EntityDetailsRoute(id)) }
            )
        }

        composable<WorldDetailsRoute> {
            WorldDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEntityClick = { id -> navController.navigate(EntityDetailsRoute(id)) },
                onEntryClick = { entryId -> navController.navigate(EntryDetailsRoute(entryId)) }
            )
        }

        composable<EntityDetailsRoute> {
            EntityDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEntryClick = { entryId -> navController.navigate(EntryDetailsRoute(entryId)) },
                onEntityClick = { id -> navController.navigate(EntityDetailsRoute(id)) }
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
                },
                onProfileClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<EntryDetailsRoute> {
            EntryDetailsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEntry = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                },
                onNavigateToEntity = { id -> navController.navigate(EntityDetailsRoute(id)) },
                onNavigateToWorld = { id -> navController.navigate(WorldDetailsRoute(id)) }
            )
        }

        composable<SearchRoute> {
            SearchScreen(
                onEntryClick = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                },
                onProfileClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onNavigateToAiSettings = { navController.navigate(AiSettingsRoute) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<AiSettingsRoute> {
            AiSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<CollectionsRoute> {
            CollectionsScreen(
                onCollectionClick = { id ->
                    navController.navigate(CollectionDetailsRoute(id))
                },
                onNavigateToEntities = { navController.navigate(EntitiesRoute) },
                onEntityClick = { id -> navController.navigate(WorldDetailsRoute(id)) },
                onProfileClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<ConversationRoute> {
            ConversationScreen(
                onNavigateToEntry = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                },
                onOpenReview = { navController.navigate(ReviewRoute) },
                onProfileClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<ReviewRoute> {
            ReviewScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable<CollectionDetailsRoute> {
            CollectionDetailsScreen(
                onNavigateBack = { navController.popBackStack() },
                onEntryClick = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                }
            )
        }

        composable<TextEntryRoute> {
            TextEntryScreen(
                onNavigateBack = { navController.popBackStack() },
                onEntrySaved = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId)) {
                        popUpTo(TextEntryRoute) { inclusive = true }
                    }
                }
            )
        }
    }
}
