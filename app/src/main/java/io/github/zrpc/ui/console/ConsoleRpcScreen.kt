package io.github.zrpc.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zrpc.data.ActivityBuilder
import io.github.zrpc.data.AppSettings
import io.github.zrpc.data.ResolvedImages
import io.github.zrpc.data.RpcConfig
import io.github.zrpc.rpc.ConsolePlatform
import io.github.zrpc.rpc.RpcMode
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.PresencePreview
import io.github.zrpc.ui.components.ScreenColumn
import io.github.zrpc.ui.components.SectionCard
import io.github.zrpc.ui.components.SectionHeader
import io.github.zrpc.ui.components.SwitchRow
import io.github.zrpc.ui.custom.Field
import io.github.zrpc.ui.home.rememberUser
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsoleRpcScreen(settings: AppSettings, onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val running by container.controller.mode.collectAsState()
    val isRunning = running == RpcMode.CONSOLE
    var c by remember { mutableStateOf(settings.console) }
    val user = rememberUser()
    val platform = ConsolePlatform.fromId(c.platform)

    val preview = remember(c, settings.applicationId) {
        if (c.game.isBlank()) null else ActivityBuilder.build(
            RpcConfig(
                name = c.game, details = c.game, state = c.state.ifBlank { "on ${platform.label}" },
                largeImage = c.coverUrl, largeText = c.game, timestamps = c.timestamps,
            ),
            ResolvedImages(large = c.coverUrl.ifBlank { null }),
            settings.applicationId,
        )
    }

    fun apply() {
        val recent = (listOf(c.game.trim()) + c.recent).filter { it.isNotBlank() }.distinct().take(8)
        val updated = c.copy(recent = recent)
        c = updated
        scope.launch {
            container.settings.setConsole(updated)
            if (!isRunning) container.controller.start(RpcMode.CONSOLE)
        }
    }

    ZrpcScaffold(
        title = RpcMode.CONSOLE.title,
        onBack = onBack,
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isRunning) OutlinedButton(onClick = container.controller::stop, modifier = Modifier.weight(1f)) { Text("Stop") }
                    Button(onClick = ::apply, enabled = c.game.isNotBlank(), modifier = Modifier.weight(1f)) { Text(if (isRunning) "Update" else "Start") }
                }
            }
        },
    ) { padding ->
        ScreenColumn(padding) {
            PresencePreview(activity = preview, user = user)
            SectionHeader("Platform")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsolePlatform.entries.forEach { p ->
                    FilterChip(selected = p == platform, onClick = { c = c.copy(platform = p.id) }, label = { Text(p.label) })
                }
            }
            SectionHeader("Game")
            SectionCard {
                Field("Game title", c.game) { c = c.copy(game = it) }
                if (c.recent.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        c.recent.forEach { g -> AssistChip(onClick = { c = c.copy(game = g) }, label = { Text(g) }) }
                    }
                }
                Field("Cover image URL (optional)", c.coverUrl, keyboard = KeyboardType.Uri) { c = c.copy(coverUrl = it) }
                Field("State (optional)", c.state, supporting = "Defaults to \"on ${platform.label}\"") { c = c.copy(state = it) }
                SwitchRow("Show elapsed time", checked = c.timestamps) { c = c.copy(timestamps = it) }
            }
        }
    }
}
