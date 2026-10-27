package io.github.zrpc.ui.experimental

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.zrpc.data.AppSettings
import io.github.zrpc.rpc.RpcMode
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.PermissionCard
import io.github.zrpc.ui.components.ScreenColumn
import io.github.zrpc.ui.components.SectionCard
import io.github.zrpc.ui.components.SectionHeader
import io.github.zrpc.ui.components.SwitchRow
import io.github.zrpc.ui.custom.Field
import kotlinx.coroutines.launch

@Composable
fun ExperimentalRpcScreen(settings: AppSettings, onBack: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by container.controller.mode.collectAsState()
    var e by remember { mutableStateOf(settings.experimental) }
    var usage by remember { mutableStateOf(container.appDetector.hasPermission()) }
    var notif by remember { mutableStateOf(container.mediaWatcher.hasPermission()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usage = container.appDetector.hasPermission()
        notif = container.mediaWatcher.hasPermission()
    }

    ZrpcScaffold(title = RpcMode.EXPERIMENTAL.title, onBack = onBack) { padding ->
        ScreenColumn(padding) {
            Text(
                "Combines App Detection and Media RPC. Uses the apps you picked in App Detection " +
                    "(or every app if none are picked). Placeholders: {app} {title} {artist} {album}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PermissionCard("Usage access", "Needed to see the foreground app.", usage) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            PermissionCard("Notification access", "Needed to see what's playing.", notif) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            SwitchRow("Enable Experimental RPC", checked = running == RpcMode.EXPERIMENTAL, enabled = usage || notif) { on ->
                if (on) container.controller.start(RpcMode.EXPERIMENTAL) else container.controller.stop()
            }
            SectionHeader("When an app is open")
            SectionCard {
                Field("Details", e.appDetails) { e = e.copy(appDetails = it) }
                Field("State", e.appState) { e = e.copy(appState = it) }
                SwitchRow("Show app icon", checked = e.showAppIcon) { e = e.copy(showAppIcon = it) }
            }
            SectionHeader("When media is playing")
            SectionCard {
                Field("Details", e.mediaDetails) { e = e.copy(mediaDetails = it) }
                Field("State", e.mediaState) { e = e.copy(mediaState = it) }
                SwitchRow("Media takes priority over apps", checked = e.preferMedia) { e = e.copy(preferMedia = it) }
            }
            Button(onClick = { scope.launch { container.settings.setExperimental(e) } }) { Text("Save templates") }
        }
    }
}
