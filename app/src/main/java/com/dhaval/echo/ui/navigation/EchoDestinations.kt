package com.dhaval.echo.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for Echo.
 * Using Kotlin Serialization for robust, compile-time checked routing.
 */

@Serializable
object HomeRoute

@Serializable
object WelcomeRoute

@Serializable
object LoginRoute

@Serializable
object CreateAccountRoute

@Serializable
object PhoneLoginRoute

@Serializable
object OtpVerificationRoute

@Serializable
object ForgotPasswordRoute

@Serializable
object ProfileSetupRoute

@Serializable
object RecordRoute

@Serializable
object TimelineRoute

@Serializable
data class EntryDetailsRoute(val entryId: String)

@Serializable
object SearchRoute

@Serializable
object SettingsRoute

@Serializable
object AiSettingsRoute

@Serializable
object CollectionsRoute

@Serializable
object ConversationRoute

@Serializable
data class CollectionDetailsRoute(val collectionId: String)

/**
 * Top-level destinations represented in the Bottom Navigation Bar.
 */
enum class TopLevelDestination(
    val route: Any,
    val icon: ImageVector,
    val label: String
) {
    Timeline(TimelineRoute, Icons.Default.History, "Timeline"),
    Home(HomeRoute, Icons.Default.Home, "Home"),
    Chat(ConversationRoute, Icons.Default.AutoAwesome, "Chat"),
    Collections(CollectionsRoute, Icons.Default.Folder, "Collections"),
    Search(SearchRoute, Icons.Default.Search, "Search"),
    Settings(SettingsRoute, Icons.Default.Settings, "Settings")
}
