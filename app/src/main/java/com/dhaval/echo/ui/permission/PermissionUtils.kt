package com.dhaval.echo.ui.permission

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications

fun EchoPermission.getDefaultRationale(): PermissionRationale {
    return when (this) {
        EchoPermission.MICROPHONE -> PermissionRationale(
            title = "Microphone Access",
            description = "Echo needs access to your microphone to record and process audio.",
            icon = Icons.Default.Mic
        )
        EchoPermission.NOTIFICATIONS -> PermissionRationale(
            title = "Notifications",
            description = "Echo needs notification access to keep you updated on processing status.",
            icon = Icons.Default.Notifications
        )
        EchoPermission.STORAGE -> PermissionRationale(
            title = "Storage Access",
            description = "Echo needs storage access to save and load your audio files.",
            icon = null
        )
        else -> PermissionRationale(
            title = "Permission Required",
            description = "This feature requires additional permissions to function correctly.",
            icon = null
        )
    }
}
