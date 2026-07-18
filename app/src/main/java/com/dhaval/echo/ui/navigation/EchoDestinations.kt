package com.dhaval.echo.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbSunny
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

@Serializable
object TextEntryRoute

// Memory Understanding Engine surfaces (MU-5)
@Serializable
object TasksRoute

@Serializable
object EntitiesRoute

@Serializable
data class EntityDetailsRoute(val entityId: String)

/**
 * Echo's five states of mind (+ You) — the only navigation the user sees.
 * Labels are human, never software words (Constitution §1). Routes keep their
 * internal names; only the felt layer changes.
 *   Today = Home · Story = Timeline · Worlds = Collections
 *   Remember = Search · Reflect = Conversation · You = Settings
 */
enum class TopLevelDestination(
    val route: Any,
    val icon: ImageVector,
    val label: String
) {
    Today(HomeRoute, Icons.Default.WbSunny, "Today"),
    Story(TimelineRoute, Icons.Default.AutoStories, "Story"),
    Worlds(CollectionsRoute, Icons.Default.Public, "Worlds"),
    Remember(SearchRoute, Icons.Default.Search, "Remember"),
    Reflect(ConversationRoute, Icons.Default.AutoAwesome, "Reflect"),
    You(SettingsRoute, Icons.Default.Person, "You")
}
