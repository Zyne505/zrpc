package io.github.zrpc.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.fragment.app.FragmentActivity
import io.github.zrpc.BuildConfig
import io.github.zrpc.R
import io.github.zrpc.data.ActivityBuilder
import io.github.zrpc.data.AppSettings
import io.github.zrpc.data.ThemeMode
import io.github.zrpc.discord.gateway.GatewayClient
import io.github.zrpc.discord.model.UserStatus
import io.github.zrpc.security.AppLock
import io.github.zrpc.security.findActivity
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.DropdownField
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.ScreenColumn
import io.github.zrpc.ui.components.SectionCard
import io.github.zrpc.ui.components.SectionHeader
import io.github.zrpc.ui.components.SwitchRow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(s: AppSettings, onBack: () -> Unit, openAbout: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.settings
    val ctx = LocalContext.current
    val activity = ctx.findActivity() as? FragmentActivity
    val scope = rememberCoroutineScope()
    val customAppId = s.applicationId.takeUnless { it == AppSettings.DEFAULT_APPLICATION_ID }.orEmpty()
    var appId by remember { mutableStateOf(customAppId) }
    val appIdValid = appId.isBlank() || appId.matches(ActivityBuilder.SNOWFLAKE)

    ZrpcScaffold(title = "Settings", onBack = onBack) { padding ->
        ScreenColumn(padding) {
            SectionHeader("Discord")
            SectionCard {
                OutlinedTextField(
                    value = appId,
                    onValueChange = { appId = it.filter(Char::isDigit) },
                    label = { Text("Application ID (optional)") },
                    singleLine = true,
                    isError = !appIdValid,
                    supportingText = { Text("Leave empty to use Zrpc's built-in app. Set your own from discord.com/developers/applications") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { scope.launch { repo.setApplicationId(appId); container.assetResolver.clear() }; Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() },
                        enabled = appIdValid && appId != customAppId,
                    ) { Text("Save") }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/developers/applications")))
                    }) { Text("Developer Portal") }
                }
                DropdownField("Status while active", UserStatus.entries, UserStatus.fromWire(s.status), { it.label }) {
                    scope.launch { repo.setStatus(it.wire) }
                }
                DropdownField("Show as device", GatewayClient.entries, GatewayClient.fromName(s.gatewayClient), { it.label }) {
                    scope.launch { repo.setGatewayClient(it.name) }
                }
                Text("Device changes apply the next time a presence starts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionHeader("Security")
            SectionCard {
                val lockAvailable = activity?.let { AppLock.isAvailable(it) } ?: false
                SwitchRow(
                    "App lock",
                    if (lockAvailable) "Fingerprint/face/PIN to open the app (re-locks after 30 s in background)" else "Set a screen lock in Android settings first",
                    s.appLock,
                    enabled = lockAvailable,
                ) { on ->
                    val a = activity ?: return@SwitchRow
                    // Confirm identity before changing the setting either way.
                    AppLock.authenticate(a, ctx.getString(R.string.reveal_title), "Confirm to change app lock") { ok ->
                        if (ok) scope.launch { repo.setAppLock(on) }
                    }
                }
                SwitchRow(
                    "Secure screen everywhere",
                    "Block screenshots and recording on every screen (login and profile are always protected)",
                    s.secureEverywhere,
                ) { scope.launch { repo.setSecureEverywhere(it) } }
            }

            SectionHeader("Images")
            SectionCard {
                SwitchRow(
                    "Allow image uploads",
                    "App icons and album art are uploaded to catbox.moe automatically so Discord can show them. " +
                        "Gallery images you pick are always uploaded. Uploads are PUBLIC and never include your token.",
                    s.allowImageUpload,
                ) { scope.launch { repo.setAllowImageUpload(it) } }
                SwitchRow(
                    "Use Play Store icons",
                    "Look up app icons from the Play Store instead of uploading them",
                    s.usePlayStoreIcons,
                ) { scope.launch { repo.setUsePlayStoreIcons(it) } }
            }

            SectionHeader("Appearance")
            SectionCard {
                DropdownField("Theme", ThemeMode.entries, s.theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
                    scope.launch { repo.setTheme(it) }
                }
                SwitchRow("Material You colors", "Use your wallpaper colors (Android 12+)", s.dynamicColor) {
                    scope.launch { repo.setDynamicColor(it) }
                }
            }

            SectionHeader("Background")
            SectionCard {
                SwitchRow("Keep CPU awake", "Helps on aggressive phones; uses more battery", s.wakeLock) {
                    scope.launch { repo.setWakeLock(it) }
                }
                FilledTonalButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Battery optimization settings") }
                Text(
                    "If presence stops when the screen is off, set Zrpc to \"Unrestricted\" there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (BuildConfig.DEBUG) {
                SectionHeader("Developer")
                SectionCard {
                    SwitchRow("Mirror logs to logcat", "Debug builds only. Logs are still redacted.", s.logcatMirror) {
                        scope.launch { repo.setLogcatMirror(it) }
                    }
                }
            }

            TextButton(onClick = openAbout) { Text("About Zrpc") }
        }
    }
}
