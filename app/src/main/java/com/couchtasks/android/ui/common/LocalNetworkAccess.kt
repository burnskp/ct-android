package com.couchtasks.android.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.couchtasks.android.sync.serverUsesLocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOCAL_NETWORK_PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

// Android 16 has no local network permission; access is unrestricted there.
fun Context.hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

private suspend fun needsLocalNetworkAccess(context: Context, serverUrl: String): Boolean =
    !context.hasLocalNetworkAccess() && withContext(Dispatchers.IO) { serverUsesLocalNetwork(serverUrl) }

private fun Context.openAppPermissionSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Returns a runner that asks for ACCESS_LOCAL_NETWORK (enforced when targeting API 37) before a user-initiated
 * connection when the server URL passed to it resolves to a local, link-local, or Tailscale address. The action
 * runs whether or not access is granted; it reports the blocked connection itself.
 */
@Composable
fun rememberLocalNetworkAccess(): (serverUrl: String, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending.value?.invoke()
        pending.value = null
    }
    val currentContext by rememberUpdatedState(context)
    return remember(launcher, scope) {
        { serverUrl, action ->
            scope.launch {
                if (needsLocalNetworkAccess(currentContext, serverUrl)) {
                    pending.value = action
                    launcher.launch(LOCAL_NETWORK_PERMISSION)
                } else {
                    action()
                }
            }
        }
    }
}

/**
 * Asks once for local network access when the configured server is local, and otherwise keeps a banner up until it is
 * granted. Once Android stops showing the prompt, the banner opens the app's permission settings instead, because
 * background sync and other entry points cannot ask on their own.
 */
@Composable
fun LocalNetworkAccessBanner(serverUrl: String, onGranted: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(context.hasLocalNetworkAccess()) }
    var promptBlocked by remember { mutableStateOf(false) }
    var autoRequestedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnGranted by rememberUpdatedState(onGranted)
    LifecycleResumeEffect(context) {
        val nowGranted = context.hasLocalNetworkAccess()
        if (nowGranted && !granted) currentOnGranted()
        granted = nowGranted
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
        if (isGranted) {
            currentOnGranted()
        } else if (activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK_PERMISSION) != true) {
            // Denied twice, or denied earlier with the prompt suppressed: only app settings can grant it now.
            promptBlocked = true
        }
    }
    val local by produceState(false, serverUrl, granted) {
        value = !granted && needsLocalNetworkAccess(context, serverUrl)
    }
    LaunchedEffect(local, serverUrl) {
        if (local && autoRequestedFor != serverUrl) {
            autoRequestedFor = serverUrl
            launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
    if (!local) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (promptBlocked) context.openAppPermissionSettings() else launcher.launch(LOCAL_NETWORK_PERMISSION)
            }
            .testTag("LocalNetworkAccessBanner"),
    ) {
        Text(
            if (promptBlocked) {
                "Sync needs local network access. Tap to open app settings and allow local network access."
            } else {
                "Sync needs local network access to reach your server. Tap to allow."
            },
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
