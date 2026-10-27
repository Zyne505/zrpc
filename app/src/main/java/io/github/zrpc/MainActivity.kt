package io.github.zrpc

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import io.github.zrpc.data.AppSettings
import io.github.zrpc.security.AppLock
import io.github.zrpc.security.SecureScreen
import io.github.zrpc.ui.LocalContainer
import io.github.zrpc.ui.ZrpcNavHost
import io.github.zrpc.ui.lock.LockScreen
import io.github.zrpc.ui.theme.ZrpcTheme
import kotlinx.coroutines.runBlocking

class MainActivity : FragmentActivity() {
    private val container by lazy { (application as ZrpcApp).container }
    private var locked by mutableStateOf(false)
    private var backgroundedAt = 0L
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Decide the lock state before anything renders.
        val initial = runBlocking { container.settings.current() }
        locked = initial.appLock && savedInstanceState?.getBoolean(KEY_UNLOCKED) != true

        setContent {
            val s by container.settings.settings.collectAsState(initial = initial)
            CompositionLocalProvider(LocalContainer provides container) {
                ZrpcTheme(s.theme, s.dynamicColor) {
                    // App lock also hides the app's content from the recents screen.
                    SecureScreen(enabled = s.secureEverywhere || s.appLock)
                    if (locked && s.appLock) {
                        LockScreen(onUnlock = ::promptUnlock)
                    } else {
                        ZrpcNavHost(settings = s)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (locked) promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val s: AppSettings = runBlocking { container.settings.current() }
        if (s.appLock && backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) {
            locked = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, !locked)
    }

    private fun promptUnlock() {
        if (authInProgress) return
        authInProgress = true
        AppLock.authenticate(this, getString(R.string.unlock_title), getString(R.string.unlock_subtitle)) { ok ->
            authInProgress = false
            if (ok) locked = false
        }
    }

    private companion object {
        const val KEY_UNLOCKED = "unlocked"
        const val RELOCK_AFTER_MS = 30_000L
    }
}
