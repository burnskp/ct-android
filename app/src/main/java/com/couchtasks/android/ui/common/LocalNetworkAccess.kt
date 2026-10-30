package com.couchtasks.android.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a runner that asks for ACCESS_LOCAL_NETWORK (enforced when targeting API 37) before a
 * user-initiated server connection, so self-hosted LAN servers stay reachable. The action runs
 * whether or not access is granted; public servers do not need it. Background sync uses the
 * same app-wide grant.
 */
@Composable
fun rememberLocalNetworkAccess(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending.value?.invoke()
        pending.value = null
    }
    val currentContext by rememberUpdatedState(context)
    return remember(launcher) {
        { action ->
            // Android 16 has no local network permission; access is unrestricted there.
            val granted = Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(currentContext, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                action()
            } else {
                pending.value = action
                launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
    }
}
