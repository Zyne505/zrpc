package io.github.zrpc

import android.app.Application
import android.webkit.WebView
import io.github.zrpc.log.AppLog
import io.github.zrpc.log.CrashHandler
import io.github.zrpc.security.TokenRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZrpcApp : Application() {
    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The redactor knows the live token (if decrypted) so even odd
        // formats get scrubbed from every log line and crash report.
        AppLog.redactor = TokenRedactor { container.vault.cachedOrNull() }
        CrashHandler(this).install()
        // Remote WebView inspection would expose the login page's storage.
        WebView.setWebContentsDebuggingEnabled(false)
        appScope.launch {
            container.settings.settings.collect { AppLog.mirrorToLogcat = it.logcatMirror && BuildConfig.DEBUG }
        }
        AppLog.i("App", "Started ${BuildConfig.VERSION_NAME}")
    }
}
