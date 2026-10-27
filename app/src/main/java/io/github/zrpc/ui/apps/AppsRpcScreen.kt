package io.github.zrpc.ui.apps

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.zrpc.data.AppSettings
import io.github.zrpc.rpc.RpcMode
import io.github.zrpc.rpc.source.InstalledApp
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.PermissionCard
import io.github.zrpc.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppsRpcScreen(settings: AppSettings, onBack: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by container.controller.mode.collectAsState()
    var hasUsage by remember { mutableStateOf(container.appDetector.hasPermission()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasUsage = container.appDetector.hasPermission() }

    val apps by produceState<List<InstalledApp>>(emptyList()) {
        value = withContext(Dispatchers.IO) { container.appDetector.launchableApps() }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    val enabled = settings.enabledApps

    ZrpcScaffold(title = RpcMode.APPS.title, onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp, end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PermissionCard(
                        "Usage access",
                        "Android only tells apps what's in the foreground with Usage access. Nothing else is read.",
                        hasUsage,
                    ) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    SwitchRow(
                        "Enable App Detection",
                        "${enabled.size} app(s) selected",
                        checked = running == RpcMode.APPS,
                        enabled = hasUsage,
                    ) { on -> if (on) container.controller.start(RpcMode.APPS) else container.controller.stop() }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        placeholder = { Text("Search apps") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (enabled.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { container.settings.setEnabledApps(emptySet()) } }) { Text("Clear selection") }
                    }
                }
            }
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in enabled
                val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
                    value = withContext(Dispatchers.IO) {
                        container.appDetector.icon(app.packageName)?.toBitmap(96, 96)?.asImageBitmap()
                    }
                }
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { icon?.let { Image(it, null, Modifier.size(40.dp)) } },
                    trailingContent = {
                        Checkbox(checked = checked, onCheckedChange = { on ->
                            scope.launch { container.settings.setEnabledApps(if (on) enabled + app.packageName else enabled - app.packageName) }
                        })
                    },
                    modifier = Modifier.clickable {
                        scope.launch { container.settings.setEnabledApps(if (checked) enabled - app.packageName else enabled + app.packageName) }
                    }.padding(vertical = 0.dp),
                )
            }
        }
    }
}
