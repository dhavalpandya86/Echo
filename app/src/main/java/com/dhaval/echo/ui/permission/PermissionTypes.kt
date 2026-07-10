package com.dhaval.echo.ui.permission

import android.Manifest
import android.os.Build

/**
 * Supported permissions in the Echo application.
 */
enum class EchoPermission(val manifestPermission: String) {
    MICROPHONE(Manifest.permission.RECORD_AUDIO),
    NOTIFICATIONS(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            "" // Not needed for older versions
        }
    ),
    // Future placeholders
    STORAGE(Manifest.permission.READ_EXTERNAL_STORAGE), // Example, might need refinement for Scoped Storage
    LOCATION(Manifest.permission.ACCESS_FINE_LOCATION),
    CONTACTS(Manifest.permission.READ_CONTACTS),
    BLUETOOTH(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
    );

    val isRequired: Boolean
        get() = manifestPermission.isNotEmpty()
}

/**
 * UI representation of a permission request rationale.
 */
data class PermissionRationale(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null
)
