package io.github.zrpc.ui.media

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.zrpc.data.AppSettings
import io.github.zrpc.rpc.RpcMode
import io.github.zrpc.rpc.source.MediaInfo
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.PermissionCard
import io.github.zrpc.ui.components.ScreenColumn
import io.github.zrpc.ui.components.SectionCard
import io.github.zrpc.ui.components.SectionHeader
import io.github.zrpc.ui.components.SwitchRow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun MediaRpcScreen(settings: AppSettings, onBack: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by container.controller.mode.collectAsState()
    var hasAccess by remember { mutableStateOf(container.mediaWatcher.hasPermission()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasAccess = container.mediaWatcher.hasPermission() }

    val mediaFlow = remember(hasAccess) { if (hasAccess) container.mediaWatcher.media() else flowOf(null) }
    val media by mediaFlow.collectAsState(initial = null)

    ZrpcScaffold(title = RpcMode.MEDIA.title, onBack = onBack) { padding ->
        ScreenColumn(padding) {
            PermissionCard(
                "Notification access",
                "Android only shares what's playing with apps that have notification access. Zrpc reads media sessions only, not your notifications' content.",
                hasAccess,
            ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

            SwitchRow("Enable Media RPC", checked = running == RpcMode.MEDIA, enabled = hasAccess) { on ->
                if (on) container.controller.start(RpcMode.MEDIA) else container.controller.stop()
            }

            SectionHeader("Now playing")
            NowPlaying(media)

            SectionHeader("Options")
            SectionCard {
                SwitchRow("Hide when paused", checked = settings.mediaHidePaused) { scope.launch { container.settings.setMediaHidePaused(it) } }
                SwitchRow("Show progress", "Time left in the track", settings.mediaTimestamps) { scope.launch { container.settings.setMediaTimestamps(it) } }
                SwitchRow("Show album name", checked = settings.mediaShowAlbum) { scope.launch { container.settings.setMediaShowAlbum(it) } }
                SwitchRow(
                    "Upload album art",
                    if (settings.allowImageUpload) "Album covers are uploaded to a public image host" else "Turn on 'Allow image uploads' in Settings first",
                    settings.uploadAlbumArt,
                    enabled = settings.allowImageUpload,
                ) { scope.launch { container.settings.setUploadAlbumArt(it) } }
            }
        }
    }
}

@Composable
private fun NowPlaying(m: MediaInfo?) {
    SectionCard {
        if (m == null) {
            Text("Nothing playing", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            m.art?.let {
                Image(it.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
            }
            androidx.compose.foundation.layout.Column {
                Text(m.title, fontWeight = FontWeight.SemiBold)
                if (m.artist.isNotBlank()) Text(m.artist, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${m.packageName} · ${if (m.isPlaying) "playing" else "paused"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
