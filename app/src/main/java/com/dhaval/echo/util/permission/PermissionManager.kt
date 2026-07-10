package com.dhaval.echo.util.permission

import android.Manifest
import android.os.Build

/**
 * Supported permission types for Echo.
 * Maps high-level features to Android Manifest permissions.
 */
enum class EchoPermission(val manifestPermission: String) {
    Microphone(Manifest.permission.RECORD_AUDIO),
    Notifications(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            "" // Not required for API < 33
        }
    ),
    // Future support placeholders
    Storage(Manifest.permission.READ_EXTERNAL_STORAGE),
    Location(Manifest.permission.ACCESS_FINE_LOCATION),
    Contacts(Manifest.permission.READ_CONTACTS),
    Bluetooth(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
    )
}

/**
 * Exhaustive states for a permission request.
 */
sealed interface PermissionState {
    object Granted : PermissionState
    object Denied : PermissionState
    object PermanentlyDenied : PermissionState
    object NotRequested : PermissionState
}
