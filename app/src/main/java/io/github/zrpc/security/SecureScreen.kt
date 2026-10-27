package io.github.zrpc.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * FLAG_SECURE blocks screenshots, screen recording, casting and the recents
 * thumbnail. Applied automatically to the login and profile screens, and to
 * the whole app when "Secure screen everywhere" is on.
 *
 * Uses a reference count so nested screens don't clear each other's flag.
 */
object SecureWindow {
    private var holders = 0

    fun acquire(activity: Activity) {
        holders++
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable
fun SecureScreen(enabled: Boolean = true) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (activity != null && enabled) SecureWindow.acquire(activity)
        onDispose { if (activity != null && enabled) SecureWindow.release(activity) }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
