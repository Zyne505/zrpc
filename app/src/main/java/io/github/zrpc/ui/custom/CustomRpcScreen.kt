package io.github.zrpc.ui.custom

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zrpc.data.ActivityBuilder
import io.github.zrpc.data.AppSettings
import io.github.zrpc.data.ResolvedImages
import io.github.zrpc.data.RpcConfig
import io.github.zrpc.discord.model.ActivityType
import io.github.zrpc.discord.model.StatusDisplay
import io.github.zrpc.rpc.RpcMode
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.DropdownField
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.PresencePreview
import io.github.zrpc.ui.components.ScreenColumn
import io.github.zrpc.ui.components.SectionCard
import io.github.zrpc.ui.components.SectionHeader
import io.github.zrpc.ui.components.SwitchRow
import io.github.zrpc.ui.home.rememberUser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRpcScreen(settings: AppSettings, onBack: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by container.controller.mode.collectAsState()
    val isRunning = running == RpcMode.CUSTOM
    val saved by container.configs.saved.collectAsState()
    val user = rememberUser()

    var cfg by remember { mutableStateOf(settings.lastCustom) }
    var endInMinutes by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            cfg = if (pickTarget == "large") cfg.copy(largeImage = uri.toString()) else cfg.copy(smallImage = uri.toString())
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(container.configs.encode(cfg).toByteArray()) }
        }.onSuccess { toast("Exported") }.onFailure { toast("Export failed") }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
            container.configs.decodeImport(text)
        }.onSuccess { cfg = it; toast("Imported") }.onFailure { toast(it.message ?: "Not a valid config") }
    }

    fun applyAndRun() {
        val minutes = endInMinutes.toLongOrNull()
        val now = System.currentTimeMillis()
        val final = if (cfg.timestamps && minutes != null && minutes > 0) cfg.copy(startMs = now, endMs = now + minutes * 60_000) else cfg.copy(startMs = null, endMs = null)
        scope.launch {
            container.settings.setLastCustom(final)
            if (!isRunning) container.controller.start(RpcMode.CUSTOM) else toast("Presence updated")
        }
    }

    val appId = cfg.applicationId.ifBlank { settings.applicationId }
    val previewActivity = remember(cfg, appId) {
        // Gallery images (content://) are shown straight from the phone; they're uploaded when the presence starts.
        ActivityBuilder.build(cfg, ResolvedImages(cfg.largeImage.ifBlank { null }, cfg.smallImage.ifBlank { null }), appId)
    }

    ZrpcScaffold(
        title = RpcMode.CUSTOM.title,
        onBack = onBack,
        actions = {
            IconButton(onClick = { showPresets = true }) { Icon(Icons.Filled.Style, "Presets") }
            IconButton(onClick = { showSaved = true }) { Icon(Icons.Filled.Bookmarks, "Saved configs") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Save config…") }, leadingIcon = { Icon(Icons.Filled.Save, null) }, onClick = { menu = false; saveDialog = true })
                DropdownMenuItem(text = { Text("Import from file") }, leadingIcon = { Icon(Icons.Filled.FileDownload, null) }, onClick = { menu = false; importer.launch(arrayOf("application/json", "text/*")) })
                DropdownMenuItem(text = { Text("Export to file") }, leadingIcon = { Icon(Icons.Filled.FileUpload, null) }, onClick = { menu = false; exporter.launch("${cfg.name.ifBlank { "presence" }}.json") })
                DropdownMenuItem(text = { Text("Reset form") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; cfg = RpcConfig() })
            }
        },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isRunning) {
                        OutlinedButton(onClick = container.controller::stop, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                        }
                    }
                    Button(onClick = ::applyAndRun, modifier = Modifier.weight(1f), enabled = cfg.name.isNotBlank()) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (isRunning) "Update" else "Start")
                    }
                }
            }
        },
    ) { padding ->
        ScreenColumn(padding) {
            PresencePreview(activity = previewActivity, user = user)

            SectionHeader("Activity")
            SectionCard {
                Field("Name (required)", cfg.name) { cfg = cfg.copy(name = it) }
                DropdownField("Type", ActivityType.entries, ActivityType.fromCode(cfg.type), { it.label }) { cfg = cfg.copy(type = it.code) }
                if (cfg.type == ActivityType.STREAMING.code) {
                    Field("Stream URL (Twitch/YouTube)", cfg.streamUrl, keyboard = KeyboardType.Uri) { cfg = cfg.copy(streamUrl = it) }
                }
                Field("Details", cfg.details) { cfg = cfg.copy(details = it) }
                Field("State", cfg.state) { cfg = cfg.copy(state = it) }
                DropdownField("Shown in member list", StatusDisplay.entries, StatusDisplay.fromCode(cfg.statusDisplay), { it.label }) {
                    cfg = cfg.copy(statusDisplay = it.code)
                }
            }

            SectionHeader("Images")
            SectionCard {
                Text(
                    "Paste an image URL, an asset name from your Discord app, or pick from gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ImageField("Large image", cfg.largeImage, onPick = { pickTarget = "large"; picker.launch(arrayOf("image/*")) }) { cfg = cfg.copy(largeImage = it) }
                Field("Large image text", cfg.largeText) { cfg = cfg.copy(largeText = it) }
                ImageField("Small image", cfg.smallImage, onPick = { pickTarget = "small"; picker.launch(arrayOf("image/*")) }) { cfg = cfg.copy(smallImage = it) }
                Field("Small image text", cfg.smallText) { cfg = cfg.copy(smallText = it) }
            }

            SectionHeader("Buttons")
            SectionCard {
                Text("Other people see these; you can't click your own buttons on Discord.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Field("Button 1 label", cfg.button1Label) { cfg = cfg.copy(button1Label = it.take(32)) }
                Field("Button 1 URL", cfg.button1Url, keyboard = KeyboardType.Uri) { cfg = cfg.copy(button1Url = it) }
                Field("Button 2 label", cfg.button2Label) { cfg = cfg.copy(button2Label = it.take(32)) }
                Field("Button 2 URL", cfg.button2Url, keyboard = KeyboardType.Uri) { cfg = cfg.copy(button2Url = it) }
            }

            SectionHeader("Time & party")
            SectionCard {
                SwitchRow("Show elapsed time", "Counts up from when you press Start", cfg.timestamps) { cfg = cfg.copy(timestamps = it) }
                if (cfg.timestamps) {
                    Field("Countdown instead (minutes, optional)", endInMinutes, keyboard = KeyboardType.Number) { endInMinutes = it.filter(Char::isDigit) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Party size", cfg.partyCurrent?.toString().orEmpty(), Modifier.weight(1f), KeyboardType.Number) {
                        cfg = cfg.copy(partyCurrent = it.filter(Char::isDigit).toIntOrNull())
                    }
                    Field("Party max", cfg.partyMax?.toString().orEmpty(), Modifier.weight(1f), KeyboardType.Number) {
                        cfg = cfg.copy(partyMax = it.filter(Char::isDigit).toIntOrNull())
                    }
                }
            }

            SectionHeader("Advanced")
            SectionCard {
                Field("Application ID override", cfg.applicationId, keyboard = KeyboardType.Number,
                    supporting = "Leave empty to use the one from Settings") { cfg = cfg.copy(applicationId = it.filter(Char::isDigit)) }
            }
        }
    }

    if (showPresets) {
        ModalBottomSheet(onDismissRequest = { showPresets = false }) {
            val presets = remember { container.configs.presets() }
            Text("Presets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(presets) { p ->
                    ListItem(
                        headlineContent = { Text(p.title) },
                        supportingContent = { Text("${ActivityType.fromCode(p.config.type).label} ${p.config.name}") },
                        modifier = Modifier.clickable { cfg = p.config; showPresets = false },
                    )
                }
            }
        }
    }

    if (showSaved) {
        ModalBottomSheet(onDismissRequest = { showSaved = false }) {
            Text("Saved configs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (saved.isEmpty()) {
                Text("Nothing saved yet. Use ⋮ › Save config.", modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(saved, key = { it.first }) { (title, c) ->
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = { Text(c.name) },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { container.configs.delete(title) } }) { Icon(Icons.Filled.Delete, "Delete") }
                        },
                        modifier = Modifier.clickable { cfg = c; showSaved = false },
                    )
                }
            }
        }
    }

    if (saveDialog) {
        var title by remember { mutableStateOf(cfg.name) }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save config") },
            text = { OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.configs.save(title, cfg); toast("Saved") }
                    saveDialog = false
                }, enabled = title.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        supportingText = supporting?.let { { Text(it) } },
        modifier = modifier,
    )
}

@Composable
private fun ImageField(label: String, value: String, onPick: () -> Unit, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = if (value.startsWith("content://")) "Gallery image" else value,
                onValueChange = { if (!value.startsWith("content://")) onChange(it) else onChange("") },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onPick) { Icon(Icons.Filled.Image, "Pick from gallery") }
    }
}
