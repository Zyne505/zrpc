package io.github.zrpc.ui.home

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zrpc.data.AppSettings
import io.github.zrpc.discord.model.DiscordUser
import io.github.zrpc.log.CrashHandler
import io.github.zrpc.rpc.RpcMode
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.Routes
import io.github.zrpc.ui.components.GatewayStatusDot
import io.github.zrpc.ui.components.InfoCard
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.PresencePreview
import io.github.zrpc.ui.components.ScreenColumn
import io.github.zrpc.ui.components.SectionHeader
import io.github.zrpc.ui.components.describe

fun RpcMode.icon(): ImageVector = when (this) {
    RpcMode.CUSTOM -> Icons.Filled.Brush
    RpcMode.APPS -> Icons.Filled.Apps
    RpcMode.MEDIA -> Icons.Filled.MusicNote
    RpcMode.CONSOLE -> Icons.Filled.SportsEsports
    RpcMode.EXPERIMENTAL -> Icons.Filled.Science
}

@Composable
fun rememberUser(): DiscordUser? {
    val container = LocalContainer.current
    val user by produceState<DiscordUser?>(initialValue = UserCache.user) {
        if (value == null) value = runCatching { container.api.me() }.getOrNull()?.also { UserCache.user = it }
    }
    return user
}

/** Tiny in-memory cache so screens don't refetch /users/@me. Cleared on logout. */
object UserCache { @Volatile var user: DiscordUser? = null }

@Composable
fun HomeScreen(settings: AppSettings, open: (String) -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val controller = container.controller
    val mode by controller.mode.collectAsState()
    val gw by controller.gateway.collectAsState()
    val activity by controller.activity.collectAsState()
    val authFailed by controller.authFailed.collectAsState()
    val user = rememberUser()
    var crash by remember { mutableStateOf(CrashHandler.pending(ctx)) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    ZrpcScaffold(
        title = "Zrpc",
        actions = {
            IconButton(onClick = { open(Routes.LOGS) }) { Icon(Icons.Filled.Description, "Logs") }
            IconButton(onClick = { open(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, "Settings") }
            IconButton(onClick = { open(Routes.PROFILE) }) {
                AsyncImage(
                    model = user?.avatarUrl(64),
                    contentDescription = "Profile",
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                )
            }
        },
    ) { padding ->
        ScreenColumn(padding) {
            if (settings.applicationId.isBlank()) {
                InfoCard(
                    Icons.Filled.Settings,
                    "Set an Application ID for images",
                    "Text-only presences work right away. To show images (URLs, app icons, album art) create a free " +
                        "application in the Discord Developer Portal and paste its ID in Settings.",
                    action = { FilledTonalButton(onClick = { open(Routes.SETTINGS) }) { Text("Open settings") } },
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GatewayStatusDot(gw)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            mode?.title ?: "Nothing running",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (mode != null) TextButton(onClick = controller::stop) { Text("Stop") }
                    }
                    Text(gw.describe(), style = MaterialTheme.typography.bodySmall)
                    if (mode != null) PresencePreview(activity = activity, user = user)
                }
            }

            SectionHeader("Presence modes")
            RpcMode.entries.forEach { m ->
                ModeCard(
                    mode = m,
                    running = mode == m,
                    onOpen = { open(Routes.mode(m)) },
                    onToggle = { on -> if (on) controller.start(m) else controller.stop() },
                )
            }
        }
    }

    if (authFailed) {
        AlertDialog(
            onDismissRequest = controller::acknowledgeAuthFailure,
            title = { Text("Logged out") },
            text = { Text("Discord rejected your token (it may have been reset by a password change). It has been erased from this device. Please log in again.") },
            confirmButton = { TextButton(onClick = controller::acknowledgeAuthFailure) { Text("OK") } },
        )
    }

    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { CrashHandler.dismiss(ctx); crash = null },
            title = { Text("Zrpc crashed last time") },
            text = {
                Text(
                    report.take(1500),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    ctx.startActivity(Intent.createChooser(send, "Share crash report (token-free)"))
                    CrashHandler.dismiss(ctx); crash = null
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { CrashHandler.dismiss(ctx); crash = null }) { Text("Dismiss") } },
        )
    }
}

@Composable
private fun ModeCard(mode: RpcMode, running: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                Icon(mode.icon(), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(mode.title, style = MaterialTheme.typography.titleMedium)
                Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = running, onCheckedChange = onToggle)
        }
    }
}
