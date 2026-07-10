package com.dhaval.echo.ui.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * A reusable Permission Manager for Jetpack Compose.
 * Handles checking, requesting, rationales, and "Don't ask again" scenarios.
 */
@Composable
fun HandlePermission(
    permission: EchoPermission,
    onPermissionResult: (Boolean) -> Unit,
    rationale: PermissionRationale,
    showRequest: Boolean,
    onDismissRequest: () -> Unit
) {
    if (!permission.isRequired) {
        onPermissionResult(true)
        return
    }

    val context = LocalContext.current
    val activity = context as? Activity ?: return

    var showRationaleDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onPermissionResult(true)
        } else {
            // Check if we should show rationale after denial
            if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission.manifestPermission)) {
                // Denied with "Don't ask again"
                showSettingsDialog = true
            } else {
                onPermissionResult(false)
            }
        }
    }

    LaunchedEffect(showRequest) {
        if (showRequest) {
            when {
                ContextCompat.checkSelfPermission(
                    context,
                    permission.manifestPermission
                ) == PackageManager.PERMISSION_GRANTED -> {
                    onPermissionResult(true)
                }
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission.manifestPermission) -> {
                    showRationaleDialog = true
                }
                else -> {
                    launcher.launch(permission.manifestPermission)
                }
            }
        }
    }

    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { 
                showRationaleDialog = false
                onDismissRequest()
            },
            title = { Text(rationale.title) },
            text = { Text(rationale.description) },
            confirmButton = {
                Button(onClick = {
                    showRationaleDialog = false
                    launcher.launch(permission.manifestPermission)
                }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRationaleDialog = false
                    onDismissRequest()
                    onPermissionResult(false)
                }) {
                    Text("Deny")
                }
            }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { 
                showSettingsDialog = false
                onDismissRequest()
            },
            title = { Text("Permission Required") },
            text = { Text("${rationale.title} is required for this feature. Please enable it in Settings.") },
            confirmButton = {
                Button(onClick = {
                    showSettingsDialog = false
                    context.openSettings()
                    onDismissRequest()
                }) {
                    Text("Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSettingsDialog = false
                    onDismissRequest()
                    onPermissionResult(false)
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun Context.openSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
