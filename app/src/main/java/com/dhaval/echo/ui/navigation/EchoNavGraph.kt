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
    val startDestination: Any = when (authState) {
        is AuthState.Authenticated -> HomeRoute
        is AuthState.Loading -> WelcomeRoute
        else -> WelcomeRoute
    }

    LaunchedEffect(authState) {
        Log.d("EchoNavGraph", "AuthState changed: $authState")
        if (authState is AuthState.Authenticated) {
            Log.d("EchoNavGraph", "Navigating to Home")
            navController.navigate(HomeRoute) {
                popUpTo(WelcomeRoute) { inclusive = true }
            }
        }
    }

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
            val googleAuthHelper = remember { GoogleAuthHelper(context) }
            val error by authViewModel.error.collectAsState()

            WelcomeScreen(
                onCreateAccount = { navController.navigate(CreateAccountRoute) },
                onLogin = { navController.navigate(LoginRoute) },
                onPhoneLogin = { navController.navigate(PhoneLoginRoute) },
                onGoogleSignIn = {
                    scope.launch {
                        Log.d("EchoNavGraph", "Google Sign-In clicked")
                        val idToken = googleAuthHelper.signIn()
                        Log.d("EchoNavGraph", "ID Token received: ${idToken != null}")
                        idToken?.let { token ->
                            authViewModel.loginWithGoogle(token)
                        }
                    }
                },
                onFacebookSignIn = { /* TODO */ },
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEntry = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
                }
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
            SettingsScreen(
                onNavigateToAiSettings = { navController.navigate(AiSettingsRoute) }
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
                }
            )
        }

        composable<ConversationRoute> {
            ConversationScreen(
                onNavigateToEntry = { entryId ->
                    navController.navigate(EntryDetailsRoute(entryId))
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
