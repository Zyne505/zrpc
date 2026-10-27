package io.github.zrpc.rpc.source

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream

data class InstalledApp(val packageName: String, val label: String)

/** Detects the foreground app via UsageStats (needs "Usage access"). */
class AppDetector(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun hasPermission(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Emits the current foreground package (null on the home screen / unknown). */
    fun foreground(pollMs: Long = 3_000): Flow<String?> = flow {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val home = homePackages()
        var current: String? = null
        while (true) {
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 20_000, now)
            val e = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) latest = e.packageName
            }
            if (latest != null) current = latest.takeUnless { it in home || it == "com.android.systemui" }
            emit(current)
            delay(pollMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private fun homePackages(): Set<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()

    fun launchableApps(): List<InstalledApp> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    fun label(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun icon(pkg: String): Drawable? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

    fun iconPng(pkg: String, size: Int = 256): ByteArray? = icon(pkg)?.let { drawableToPng(it, size) }

    companion object {
        fun drawableToPng(d: Drawable, size: Int = 256): ByteArray {
            val bmp = if (d is BitmapDrawable && d.bitmap != null) {
                Bitmap.createScaledBitmap(d.bitmap, size, size, true)
            } else {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    val c = Canvas(it)
                    d.setBounds(0, 0, size, size)
                    d.draw(c)
                }
            }
            return ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }

        fun bitmapToPng(b: Bitmap, max: Int = 512): ByteArray {
            val scale = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
            val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
            return ByteArrayOutputStream().use { out ->
                s.compress(Bitmap.CompressFormat.PNG, 95, out)
                out.toByteArray()
            }
        }
    }
}
