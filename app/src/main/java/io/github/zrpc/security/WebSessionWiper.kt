package io.github.zrpc.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase

/**
 * After the in-app Discord login we extract the token and then erase
 * everything the WebView kept (cookies, localStorage, IndexedDB, cache,
 * form data, HTTP auth). The vault becomes the token's only home.
 */
object WebSessionWiper {
    /**
     * @param onDone called on the main thread once cookies are actually gone.
     *   Cookie removal is asynchronous, so anything that loads a page right
     *   after a wipe must wait for this, or the wipe can delete the cookies
     *   the freshly loading page just set (Cloudflare's, for example).
     */
    fun wipe(context: Context, webView: WebView? = null, onDone: (() -> Unit)? = null) {
        webView?.apply {
            stopLoading()
            clearCache(true)
            clearHistory()
            clearFormData()
            clearSslPreferences()
        }
        WebStorage.getInstance().deleteAllData()
        runCatching {
            WebViewDatabase.getInstance(context).apply {
                clearHttpAuthUsernamePassword()
                @Suppress("DEPRECATION") clearFormData()
            }
        }
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            onDone?.invoke()
        }
    }
}

/**
 * Copy for the "copy token" action: marked sensitive so Android 13+ hides
 * it from the clipboard preview/keyboards, and auto-cleared after 30 s.
 */
object SensitiveClipboard {
    private const val CLEAR_AFTER_MS = 30_000L

    fun copy(context: Context, label: String, value: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == value) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
                    else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, CLEAR_AFTER_MS)
    }
}
