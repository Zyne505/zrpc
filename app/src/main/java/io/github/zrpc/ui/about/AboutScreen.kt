package io.github.zrpc.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.zrpc.BuildConfig
import io.github.zrpc.ui.components.InfoCard
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.ScreenColumn

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    ZrpcScaffold(title = "About", onBack = onBack) { padding ->
        ScreenColumn(padding) {
            Text("Zrpc ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text(
                "A custom Discord Rich Presence app for Android, built around protecting your token.",
                style = MaterialTheme.typography.bodyMedium,
            )
            InfoCard(
                Icons.Filled.Shield, "Security model",
                "Token encrypted with a hardware-backed Keystore key · sent only to Discord (enforced by a host allowlist, " +
                    "no redirects) · user-installed CAs not trusted · never logged, backed up or included in crash reports · " +
                    "login session wiped after sign-in · biometric gate for reveal/copy · optional app lock and FLAG_SECURE.",
            )
            TextButton(onClick = { open("https://github.com/Zyne505/zrpc") }) { Text("Source code on GitHub") }
            InfoCard(
                Icons.Filled.Gavel, "Disclaimer",
                "Not affiliated with Discord. Using a user token on the gateway goes against Discord's Terms of Service. " +
                    "Use at your own risk. Licensed under GPL-3.0.",
            )
        }
    }
}
