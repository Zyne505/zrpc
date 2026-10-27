package io.github.zrpc.ui.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import io.github.zrpc.R
import io.github.zrpc.log.AppLog
import io.github.zrpc.security.AppLock
import io.github.zrpc.security.SecureScreen
import io.github.zrpc.security.SensitiveClipboard
import io.github.zrpc.security.WebSessionWiper
import io.github.zrpc.security.findActivity
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.InfoCard
import io.github.zrpc.ui.components.ZrpcScaffold
import io.github.zrpc.ui.components.ScreenColumn
import io.github.zrpc.ui.components.SectionCard
import io.github.zrpc.ui.components.SectionHeader
import io.github.zrpc.ui.home.UserCache
import io.github.zrpc.ui.home.rememberUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    SecureScreen()
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val activity = ctx.findActivity() as? FragmentActivity
    val scope = rememberCoroutineScope()
    val user = rememberUser()
    var revealed by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val protection = remember { container.vault.describeProtection() }
    val hasLock = remember { activity?.let { AppLock.isAvailable(it) } ?: false }

    // Auto-hide a revealed token after 20 seconds.
    LaunchedEffect(revealed) { if (revealed != null) { delay(20_000); revealed = null } }

    fun withAuth(block: () -> Unit) {
        val a = activity ?: return
        AppLock.authenticate(a, ctx.getString(R.string.reveal_title), ctx.getString(R.string.reveal_subtitle)) { ok -> if (ok) block() }
    }

    ZrpcScaffold(title = "Profile", onBack = onBack) { padding ->
        ScreenColumn(padding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(user?.avatarUrl(256), null, Modifier.size(72.dp).clip(CircleShape))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(user?.displayName ?: "…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("@${user?.username ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    user?.id?.let { Text("ID $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            SectionHeader("Token")
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(protection, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    revealed ?: "•".repeat(32),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (revealed != null) revealed = null else withAuth { revealed = container.vault.get() }
                    }) {
                        Icon(Icons.Filled.Visibility, null); Spacer(Modifier.width(6.dp)); Text(if (revealed != null) "Hide" else "Reveal")
                    }
                    FilledTonalButton(onClick = {
                        withAuth {
                            container.vault.get()?.let {
                                SensitiveClipboard.copy(ctx, "Discord token", it)
                                Toast.makeText(ctx, "Copied. Clipboard clears in 30 s.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
                    }
                }
                if (!hasLock) {
                    Text(
                        "Your phone has no screen lock, so revealing isn't protected. Set a PIN or fingerprint in Android settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            InfoCard(
                Icons.Filled.Lock,
                "Keep it secret",
                "Anyone with your token can use your account. Zrpc will never ask you to send it to anyone, " +
                    "and support staff never need it. If you think it leaked, change your Discord password: that resets it.",
            )

            Button(
                onClick = { confirmLogout = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Log out & erase token") }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("This stops any presence, erases the encrypted token, destroys its encryption key and clears the login session.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        container.controller.stop()
                        container.vault.wipe()
                        WebSessionWiper.wipe(ctx)
                        container.assetResolver.clear()
                        container.settings.clearAccountData()
                        UserCache.user = null
                        AppLog.i("Profile", "Logged out, vault wiped")
                    }
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}
