package com.dhaval.echo.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Visibility
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
object BackupRoute

@Serializable
object BackupHistoryRoute

/**
 * @param archiveUri set when arriving from history with a specific archive;
 *   null means "let the user pick a file", which is the reinstall path from the
 *   Welcome screen.
 */
@Serializable
data class RestoreRoute(val archiveUri: String? = null)

@Serializable
object PrivacyPolicyRoute

@Serializable
object CollectionsRoute

@Serializable
object ConversationRoute

@Serializable
data class CollectionDetailsRoute(val collectionId: String)

@Serializable
data class TextEntryRoute(
    /** Epoch-day the new memory should be dated to (from the calendar); null = today. */
    val dateEpochDay: Long? = null
)

// Memory Understanding Engine surfaces (MU-5)
@Serializable
object TasksRoute

@Serializable
object EntitiesRoute

@Serializable
data class EntityDetailsRoute(val entityId: String)

/** A discovered World's detail page, anchored by its seed entity. */
@Serializable
data class WorldDetailsRoute(val seedEntityId: String)

/** A generated periodic reflection (weekly / monthly review). */
@Serializable
object ReviewRoute

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
    Today(HomeRoute, Icons.Rounded.Today, "Today"),
    Story(TimelineRoute, Icons.Rounded.AutoStories, "Story"),
    Worlds(CollectionsRoute, Icons.Rounded.Public, "Worlds"),
    Reflect(ConversationRoute, Icons.Rounded.SelfImprovement, "Reflect"),
    Remember(SearchRoute, Icons.Rounded.Visibility, "Remember")
}
