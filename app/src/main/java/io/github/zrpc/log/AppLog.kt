package io.github.zrpc.log

import android.util.Log
import io.github.zrpc.security.TokenRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(val time: Long, val level: LogLevel, val tag: String, val message: String) {
    fun format(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))
        return "$ts ${level.name.first()} [$tag] $message"
    }
}

/**
 * In-app log (shown on the Logs screen). Every message passes through the
 * redactor before it is stored or forwarded to logcat, so nothing written
 * here can ever contain the token, even by mistake.
 */
object AppLog {
    private const val MAX = 600
    @Volatile var redactor: TokenRedactor = TokenRedactor()
    @Volatile var mirrorToLogcat: Boolean = false

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, msg: String) = add(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = add(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = add(LogLevel.WARN, tag, withThrowable(msg, t))
    fun e(tag: String, msg: String, t: Throwable? = null) = add(LogLevel.ERROR, tag, withThrowable(msg, t))

    fun clear() { _entries.value = emptyList() }

    fun export(): String = _entries.value.joinToString("\n") { it.format() }

    private fun withThrowable(msg: String, t: Throwable?): String =
        if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}"

    @Synchronized
    private fun add(level: LogLevel, tag: String, raw: String) {
        val clean = redactor.redact(raw)
        val entry = LogEntry(System.currentTimeMillis(), level, tag, clean)
        _entries.value = (_entries.value + entry).takeLast(MAX)
        if (mirrorToLogcat) {
            when (level) {
                LogLevel.DEBUG -> Log.d("Zrpc/$tag", clean)
                LogLevel.INFO -> Log.i("Zrpc/$tag", clean)
                LogLevel.WARN -> Log.w("Zrpc/$tag", clean)
                LogLevel.ERROR -> Log.e("Zrpc/$tag", clean)
            }
        }
    }
}
