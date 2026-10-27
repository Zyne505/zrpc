package io.github.zrpc.log

import android.content.Context
import android.os.Build
import io.github.zrpc.BuildConfig
import java.io.File

/**
 * Writes uncaught exceptions to a private file so they can be viewed (and
 * shared) on next launch. The report is redacted first: stack traces can
 * contain request bodies or URLs, and none of that may leak a token.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    fun install() = Thread.setDefaultUncaughtExceptionHandler(this)

    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching {
            val report = buildString {
                appendLine("Zrpc ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread: ${t.name}")
                appendLine()
                appendLine(e.stackTraceToString())
                appendLine("--- recent log ---")
                appendLine(AppLog.export().takeLast(8_000))
            }
            file(context).writeText(AppLog.redactor.redact(report))
        }
        previous?.uncaughtException(t, e)
    }

    companion object {
        fun file(context: Context) = File(context.filesDir, "last_crash.txt")
        fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.readText()
        fun dismiss(context: Context) { file(context).delete() }
    }
}
