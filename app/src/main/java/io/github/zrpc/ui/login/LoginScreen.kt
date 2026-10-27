package io.github.zrpc.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.zrpc.discord.HttpException
import io.github.zrpc.discord.ZrpcJson
import io.github.zrpc.log.AppLog
import io.github.zrpc.security.DiscordHosts
import io.github.zrpc.security.SecureScreen
import io.github.zrpc.security.TokenFormat
import io.github.zrpc.security.WebSessionWiper
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.components.InfoCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

private enum class LoginStep { CHOOSE, WEB, PASTE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    // No screenshots / recordings / recents thumbnail while a token may be on screen.
    SecureScreen()
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(LoginStep.CHOOSE) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /** Verifies the candidate with Discord, then encrypts it into the vault. */
    fun accept(candidate: String, webView: WebView?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val token = TokenFormat.normalize(candidate)
                require(TokenFormat.isPlausible(token)) { "That doesn't look like a Discord token." }
                val user = container.api.verifyToken(token)
                container.vault.save(token)
                AppLog.i("Login", "Logged in as ${user.username}")
                WebSessionWiper.wipe(ctx, webView)
                webView?.destroy()
                onLoggedIn()
            } catch (e: HttpException) {
                error = if (e.code == 401) "Discord rejected that token." else "Discord returned HTTP ${e.code}. Try again."
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't reach Discord. Check your connection."
                AppLog.w("Login", "Verification failed", e)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == LoginStep.WEB) "Log in to Discord" else "Welcome") },
                navigationIcon = {
                    if (step != LoginStep.CHOOSE) TextButton(onClick = { step = LoginStep.CHOOSE; error = null }) { Text("Back") }
                },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                LoginStep.CHOOSE -> ChooseStep(
                    onWeb = { step = LoginStep.WEB },
                    onPaste = { step = LoginStep.PASTE },
                )
                LoginStep.WEB -> DiscordWebLogin(onToken = { token, view -> accept(token, view) })
                LoginStep.PASTE -> PasteStep(busy = busy, error = error, onSubmit = { accept(it, null) })
            }
            if (busy && step == LoginStep.WEB) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            if (error != null && step == LoginStep.WEB) {
                InfoCard(
                    Icons.Filled.Warning, "Login failed", error!!,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun ChooseStep(onWeb: () -> Unit, onPaste: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Zrpc", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Discord Rich Presence for Android, with your token locked down.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            Icons.Filled.Shield,
            "How your token is protected",
            "• Encrypted with AES-256-GCM using a hardware-backed Android Keystore key\n" +
                "• Only ever sent to discord.com and Discord's gateway, enforced in code\n" +
                "• Never written to logs, crash reports or backups\n" +
                "• Login page data is wiped right after sign-in\n" +
                "• Screenshots blocked on sensitive screens; reveal needs your fingerprint/PIN",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Button(onClick = onWeb, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.Login, null)
            Spacer(Modifier.width(8.dp))
            Text("Log in with Discord")
        }
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Key, null)
            Spacer(Modifier.width(8.dp))
            Text("Use a token instead")
        }
        InfoCard(
            Icons.Filled.Warning,
            "Heads up",
            "This app connects to Discord with your account token, which Discord's Terms treat as self-botting. " +
                "Presence-only use is rarely actioned, but use it at your own risk. Never give your token to anyone, " +
                "including people claiming to be from Discord or Zrpc.",
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PasteStep(busy: Boolean, error: String?, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    // Clear the field from memory when leaving the screen.
    DisposableEffect(Unit) { onDispose { value = "" } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Paste your token", style = MaterialTheme.typography.headlineSmall)
        Text(
            "It's checked with Discord, then encrypted on this device. It is never sent anywhere except discord.com.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.filterNot { c -> c.isWhitespace() } },
            label = { Text("Discord token") },
            singleLine = true,
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            // Password keyboard type: keyboards don't learn or suggest it.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSubmit(value) }, enabled = !busy && value.isNotBlank()) { Text("Verify & save") }
            if (busy) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * Discord's own login page in a locked-down WebView:
 *  - only discord.com pages may load at the top level,
 *  - no JS bridge is exposed to the page, no file/content access,
 *  - the token is read once from the page's storage after login and
 *    everything the WebView stored is wiped right after.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiscordWebLogin(onToken: (String, WebView) -> Unit) {
    val scope = rememberCoroutineScope()
    var delivered by remember { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebSessionWiper.wipe(ctx) // start from a clean session
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Block top-level navigation away from Discord (phishing, ads, external links).
                        return !(request.url.scheme == "https" && DiscordHosts.isTrusted(request.url.host.orEmpty()))
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        if (!isTrustedPage(url)) view.stopLoading()
                    }

                    override fun onPageFinished(view: WebView, url: String?) = check(view, url)
                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = check(view, url)

                    private fun check(view: WebView, url: String?) {
                        if (delivered || !isTrustedPage(url)) return
                        val path = Uri.parse(url).path.orEmpty()
                        if (path.startsWith("/app") || path.startsWith("/channels")) {
                            extract(view, scope) { token ->
                                if (!delivered) {
                                    delivered = true
                                    onToken(token, view)
                                }
                            }
                        }
                    }
                }
                loadUrl("https://discord.com/login")
            }
        },
    )
}

private fun isTrustedPage(url: String?): Boolean {
    val u = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
    return u.scheme == "https" && DiscordHosts.isTrusted(u.host.orEmpty())
}

/**
 * Discord removes window.localStorage after load, but a fresh same-origin
 * iframe still exposes it. Polls briefly because the token is written a
 * moment after the redirect to /app.
 */
private const val EXTRACT_JS =
    "(function(){try{var f=document.createElement('iframe');f.style.display='none';" +
        "document.body.appendChild(f);var t=f.contentWindow.localStorage.getItem('token');" +
        "f.remove();return t;}catch(e){return null;}})()"

private fun extract(view: WebView, scope: CoroutineScope, onFound: (String) -> Unit) {
    scope.launch {
        repeat(20) {
            if (!isTrustedPage(view.url)) return@launch
            var found: String? = null
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            view.evaluateJavascript(EXTRACT_JS) { raw ->
                found = runCatching {
                    // evaluateJavascript returns a JSON-encoded value; the stored token is itself quoted.
                    val outer = ZrpcJson.parseToJsonElement(raw).jsonPrimitive
                    if (outer.isString) TokenFormat.normalize(outer.content) else null
                }.getOrNull()
                done.complete(Unit)
            }
            done.await()
            found?.takeIf { TokenFormat.isPlausible(it) }?.let { onFound(it); return@launch }
            delay(700)
        }
        AppLog.w("Login", "Couldn't read the session after login")
    }
}
