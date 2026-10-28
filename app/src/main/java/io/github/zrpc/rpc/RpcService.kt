package io.github.zrpc.rpc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.zrpc.ZrpcApp
import io.github.zrpc.MainActivity
import io.github.zrpc.R
import io.github.zrpc.data.AppSettings
import io.github.zrpc.discord.gateway.DiscordGateway
import io.github.zrpc.discord.gateway.GatewayClient
import io.github.zrpc.discord.gateway.GatewayState
import io.github.zrpc.discord.model.Activity
import io.github.zrpc.discord.model.Presence
import io.github.zrpc.log.AppLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the gateway connection while any RPC mode is
 * active. One mode at a time, like the original app.
 */
class RpcService : LifecycleService() {

    private val container by lazy { (application as ZrpcApp).container }
    private lateinit var gateway: DiscordGateway
    @Volatile private var latestSettings = AppSettings()
    private var modeJob: Job? = null
    private var mode: RpcMode? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    /** Last default network seen; only touched on the ConnectivityManager callback thread. */
    @Volatile private var lastNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        gateway = DiscordGateway(
            client = container.gatewayClient,
            tokenProvider = { container.vault.get() },
            clientType = { GatewayClient.fromName(latestSettings.gatewayClient) },
            parentScope = lifecycleScope,
        )
        gateway.onAuthFailed = {
            AppLog.e(TAG, "Token rejected by Discord, wiping it from the vault")
            container.vault.wipe()
            container.controller.setAuthFailed()
            lifecycleScope.launch { stopEverything() }
        }
        lifecycleScope.launch {
            container.settings.settings.collect { latestSettings = it; applyWakeLock(it.wakeLock) }
        }
        // The wake lock has a timeout (lint-friendly); keep extending it so a
        // long session doesn't silently lose it after a few hours.
        lifecycleScope.launch {
            while (isActive) {
                delay(WAKE_LOCK_RENEW_MS)
                applyWakeLock(latestSettings.wakeLock)
            }
        }
        lifecycleScope.launch {
            gateway.state.collect {
                container.controller.setGateway(it)
                updateNotification()
            }
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        val requested = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { RpcMode.valueOf(it) }.getOrNull() }
            ?: mode ?: run { stopSelf(); return START_NOT_STICKY }

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(requested, gateway.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (!container.vault.hasToken.value) {
            AppLog.w(TAG, "Not logged in")
            stopEverything()
            return START_NOT_STICKY
        }
        run(requested)
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun run(newMode: RpcMode) {
        mode = newMode
        container.controller.setMode(newMode)
        // The settings collector may have run before `mode` was set, in which
        // case applyWakeLock() ignored it. Apply again now that we're active.
        applyWakeLock(latestSettings.wakeLock)
        logBatteryState()
        modeJob?.cancel()
        gateway.start()
        AppLog.i(TAG, "${newMode.title} started")

        val settings = container.settings.settings
        val composer = container.composer
        val activities: Flow<Activity?> = when (newMode) {
            RpcMode.CUSTOM -> settings.distinctUntilChanged { a, b -> a.lastCustom == b.lastCustom && a.applicationId == b.applicationId && a.allowImageUpload == b.allowImageUpload }
                .mapLatest { composer.custom(it.lastCustom, it) }
            RpcMode.CONSOLE -> settings.distinctUntilChanged { a, b -> a.console == b.console && a.applicationId == b.applicationId }
                .mapLatest { composer.console(it.console, it) }
            RpcMode.APPS -> {
                val fg = if (container.appDetector.hasPermission()) container.appDetector.foreground() else flowOf(null)
                combine(fg, settings) { pkg, s -> pkg to s }
                    .distinctUntilChanged { a, b -> a.first == b.first && a.second.enabledApps == b.second.enabledApps && a.second.applicationId == b.second.applicationId }
                    .mapLatest { (pkg, s) -> composer.app(pkg, s) }
            }
            RpcMode.MEDIA -> combine(container.mediaWatcher.media(), settings) { m, s -> m to s }
                .mapLatest { (m, s) -> composer.media(m, s) }
            RpcMode.EXPERIMENTAL -> {
                val fg = if (container.appDetector.hasPermission()) container.appDetector.foreground() else flowOf(null)
                combine(fg, container.mediaWatcher.media(), settings) { pkg, m, s -> Triple(pkg, m, s) }
                    .mapLatest { (pkg, m, s) -> composer.experimental(pkg, m, s) }
            }
        }

        modeJob = lifecycleScope.launch {
            combine(activities, settings.map { it.status }.distinctUntilChanged()) { a, status -> a to status }
                .distinctUntilChanged()
                .collect { (activity, status) ->
                    container.controller.setActivity(activity)
                    gateway.setPresence(Presence(activities = listOfNotNull(activity), status = status))
                    updateNotification()
                }
        }
    }

    private fun stopEverything() {
        modeJob?.cancel()
        gateway.stop()
        mode = null
        container.controller.setMode(null)
        container.controller.setActivity(null)
        applyWakeLock(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Covers destruction that didn't go through stopEverything(); stop() is
        // idempotent and survives lifecycleScope being cancelled below.
        gateway.stop()
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        applyWakeLock(false)
        container.controller.setMode(null)
        container.controller.setGateway(GatewayState.Idle)
        super.onDestroy()
    }

    // ------------------------------------------------------------- helpers

    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Called once on registration and again whenever the *default*
            // network changes. A different Network object means the existing
            // socket is bound to a network we're leaving.
            override fun onAvailable(network: Network) {
                val previous = lastNetwork
                lastNetwork = network
                if (previous != null && previous != network) gateway.networkChanged() else gateway.nudge()
            }

            override fun onLost(network: Network) {
                if (network == lastNetwork) AppLog.w(TAG, "Default network lost")
            }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
    }

    private fun applyWakeLock(on: Boolean) {
        if (on && mode != null) {
            val wl = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zrpc:gateway")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it; AppLog.i(TAG, "Wake lock on") }
            // Not reference-counted, so acquiring again just extends the timeout.
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else if (wakeLock != null) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            AppLog.i(TAG, "Wake lock off")
        }
    }

    /** Records the power state that most often explains heartbeats missed with the screen off. */
    private fun logBatteryState() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val exempt = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        AppLog.i(TAG, "Battery optimization exempt=$exempt, wake lock=${latestSettings.wakeLock}, power saver=${pm.isPowerSaveMode}")
        if (!exempt && !latestSettings.wakeLock) {
            AppLog.w(TAG, "With the screen off the CPU may sleep through heartbeats and Discord will drop the session. " +
                "Turn on the wake lock setting or allow Zrpc to run unrestricted in battery settings.")
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notif_channel_desc); setShowBadge(false) }
        )
    }

    private fun updateNotification() {
        val m = mode ?: return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(m, gateway.state.value))
    }

    private fun buildNotification(m: RpcMode, state: GatewayState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RpcService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val status = when (state) {
            is GatewayState.Connected -> container.controller.activity.value?.name?.let { "Showing: $it" } ?: "Connected"
            GatewayState.Connecting -> "Connecting…"
            is GatewayState.Reconnecting -> "Reconnecting…"
            is GatewayState.Failed -> state.reason
            GatewayState.Idle -> "Starting…"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(m.title)
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val ACTION_STOP = "io.github.zrpc.STOP"
        private const val CHANNEL = "rpc"
        private const val NOTIF_ID = 42
        private const val TAG = "RpcService"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 60 * 60 * 1000L
    }
}
