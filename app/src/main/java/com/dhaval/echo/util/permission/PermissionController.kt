package com.dhaval.echo.util.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * A controller that handles permission requests and state tracking in Jetpack Compose.
 */
@Stable
class PermissionController(
    private val context: Context,
    private val permission: EchoPermission,
    private val onPermissionResult: (Boolean) -> Unit
) {
    var state by mutableStateOf<PermissionState>(PermissionState.NotRequested)
        private set

    /**
     * Updates the current state by checking the system status.
     */
    fun updateState() {
        val manifestPermission = permission.manifestPermission
        if (manifestPermission.isEmpty()) {
            state = PermissionState.Granted
            return
        }

        val isGranted = ContextCompat.checkSelfPermission(
            context, manifestPermission
        ) == PackageManager.PERMISSION_GRANTED

        state = if (isGranted) {
            PermissionState.Granted
        } else {
            val activity = context as? Activity
            if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, manifestPermission)) {
                PermissionState.Denied
            } else {
                // If it's not granted and we shouldn't show rationale, it's either NotRequested 
                // or PermanentlyDenied. In this minimal architecture, we default to NotRequested
                // until a real request is made.
                PermissionState.NotRequested
            }
        }
    }

    /**
     * Opens the app settings screen to allow the user to manually enable a permanently denied permission.
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Remembers a PermissionController for a specific permission.
 */
@Composable
fun rememberPermissionController(
    permission: EchoPermission,
    onPermissionResult: (Boolean) -> Unit = {}
): PermissionController {
    val context = LocalContext.current
    val controller = remember(permission) {
        PermissionController(context, permission, onPermissionResult)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        controller.updateState()
        // Special case: If not granted and rationale is false, it's likely Permanently Denied
        if (!isGranted) {
            val activity = context as? Activity
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission.manifestPermission)) {
                // This block is reached if "Don't ask again" was selected previously or just now.
                // We don't set state directly here to keep logic in updateState if possible,
                // but this is where the logic lives.
            }
        }
        onPermissionResult(isGranted)
    }

    return controller.apply {
        // We can't call launcher.launch() inside the controller directly without exposing the launcher.
        // So we add a request method here.
    }
}
