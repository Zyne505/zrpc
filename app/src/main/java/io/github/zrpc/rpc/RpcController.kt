package io.github.zrpc.rpc

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.zrpc.discord.gateway.GatewayState
import io.github.zrpc.discord.model.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RpcMode(val title: String, val subtitle: String) {
    CUSTOM("Custom RPC", "Your own presence with images, buttons and timestamps"),
    APPS("App Detection", "Shows the app you're using"),
    MEDIA("Media RPC", "Shows what's playing"),
    CONSOLE("Console RPC", "Pretend you're on PlayStation, Xbox or Switch"),
    EXPERIMENTAL("Experimental RPC", "Apps + media with templates"),
}

/** Shared state between the service and the UI (same process). */
class RpcController(private val context: Context) {
    private val _mode = MutableStateFlow<RpcMode?>(null)
    val mode: StateFlow<RpcMode?> = _mode.asStateFlow()

    private val _gateway = MutableStateFlow<GatewayState>(GatewayState.Idle)
    val gateway: StateFlow<GatewayState> = _gateway.asStateFlow()

    private val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()

    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    fun start(mode: RpcMode) {
        _authFailed.value = false
        val i = Intent(context, RpcService::class.java).putExtra(RpcService.EXTRA_MODE, mode.name)
        ContextCompat.startForegroundService(context, i)
    }

    fun stop() {
        context.startService(Intent(context, RpcService::class.java).setAction(RpcService.ACTION_STOP))
    }

    internal fun setMode(m: RpcMode?) { _mode.value = m }
    internal fun setGateway(s: GatewayState) { _gateway.value = s }
    internal fun setActivity(a: Activity?) { _activity.value = a }
    internal fun setAuthFailed() { _authFailed.value = true }
    fun acknowledgeAuthFailure() { _authFailed.value = false }
}
