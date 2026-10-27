package io.github.zrpc.discord.gateway

import io.github.zrpc.discord.ZrpcJson
import io.github.zrpc.discord.model.DiscordUser
import io.github.zrpc.discord.model.Presence
import io.github.zrpc.log.AppLog
import io.github.zrpc.security.DiscordHosts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/** How this session identifies itself to Discord (decides the device icon next to your status). */
enum class GatewayClient(val label: String, val os: String, val browser: String, val device: String) {
    ANDROID("Mobile (Android)", "Android", "Discord Android", "Android"),
    DESKTOP("Desktop", "Windows", "Discord Client", ""),
    WEB("Web", "Windows", "Chrome", "");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: ANDROID
    }
}

sealed interface GatewayState {
    data object Idle : GatewayState
    data object Connecting : GatewayState
    data class Connected(val user: DiscordUser?) : GatewayState
    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : GatewayState
    data class Failed(val reason: String, val authError: Boolean) : GatewayState
}

/**
 * Minimal, resilient Discord gateway client for presence only.
 *
 * - Handles HELLO / heartbeat / ACK, zombie connections, RECONNECT,
 *   INVALID_SESSION and RESUME, with exponential backoff.
 * - Presence updates are coalesced and rate-limited (Discord allows ~5/20s).
 * - The token is only put into IDENTIFY/RESUME frames, which are never logged,
 *   and only sent over wss to a Discord host (resume URLs from the server are
 *   validated too).
 * - All state is mutated on one serial dispatcher, so no locks are needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscordGateway(
    private val client: OkHttpClient,
    private val tokenProvider: () -> String?,
    private val clientType: () -> GatewayClient,
    parentScope: CoroutineScope,
    private val endpoint: String = DEFAULT_URL,
    serialDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val clock: () -> Long = System::currentTimeMillis,
    private val isTrustedGateway: (String) -> Boolean = DiscordHosts::isTrustedGateway,
) {
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + serialDispatcher)

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Idle)
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    /** Called when Discord rejects the token (close 4004). */
    var onAuthFailed: (() -> Unit)? = null

    private var socket: WebSocket? = null
    private var generation = 0
    private var seq: Long? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var user: DiscordUser? = null
    private var ready = false
    private var wanted = false
    private var attempts = 0
    private var awaitingAck = false
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var presenceJob: Job? = null
    private var desired: Presence? = null
    private var lastPresenceAt = 0L

    fun start() {
        scope.launch {
            if (wanted) return@launch
            wanted = true
            attempts = 0
            connect(resume = false)
        }
    }

    fun stop() {
        scope.launch {
            wanted = false
            reconnectJob?.cancel()
            presenceJob?.cancel()
            if (ready) send(OP_PRESENCE, ZrpcJson.encodeToJsonElement(Presence.serializer(), Presence(status = desired?.status ?: "online")))
            teardown(code = 1000, reason = "client stop")
            seq = null; sessionId = null; resumeUrl = null; ready = false
            _state.value = GatewayState.Idle
        }
    }

    fun setPresence(presence: Presence?) {
        scope.launch {
            desired = presence
            schedulePresence()
        }
    }

    /** Network came back: skip the remaining backoff. */
    fun nudge() {
        scope.launch {
            if (wanted && _state.value is GatewayState.Reconnecting) {
                reconnectJob?.cancel()
                connect(resume = sessionId != null)
            }
        }
    }

    // ---------------------------------------------------------------- connection

    private fun connect(resume: Boolean) {
        teardown(code = 4000, reason = "reconnecting")
        if (tokenProvider() == null) {
            fail("Not logged in", authError = true)
            return
        }
        val base = resumeUrl?.takeIf { resume && isTrustedGateway(it) } ?: endpoint
        if (!isTrustedGateway(base)) {
            fail("Refusing to connect to untrusted gateway", authError = false)
            return
        }
        val url = base.trimEnd('/') + "/?v=10&encoding=json"
        _state.value = GatewayState.Connecting
        val gen = ++generation
        AppLog.i(TAG, if (resume) "Connecting (resume)" else "Connecting")
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener(gen, resume))
    }

    private fun teardown(code: Int, reason: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        ready = false
        socket?.let { s ->
            generation++ // ignore callbacks from the old socket
            runCatching { s.close(code, reason) }
        }
        socket = null
    }

    private fun fail(reason: String, authError: Boolean) {
        wanted = false
        teardown(1000, "failed")
        AppLog.e(TAG, reason)
        // Run the callback (e.g. wipe the vault) before anyone observes Failed.
        if (authError) onAuthFailed?.invoke()
        _state.value = GatewayState.Failed(reason, authError)
    }

    private fun scheduleReconnect(resume: Boolean, reason: String) {
        if (!wanted) return
        attempts++
        val backoff = (1000L shl (attempts - 1).coerceAtMost(6)).coerceAtMost(60_000L) + Random.nextLong(0, 1000)
        _state.value = GatewayState.Reconnecting(attempts, backoff, reason)
        AppLog.w(TAG, "Reconnecting in ${backoff / 1000}s ($reason)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            connect(resume)
        }
    }

    // ---------------------------------------------------------------- frames

    private fun handle(text: String, resumeIntent: Boolean) {
        val obj = runCatching { ZrpcJson.parseToJsonElement(text).jsonObject }.getOrElse {
            AppLog.w(TAG, "Unparseable frame")
            return
        }
        val op = obj["op"]?.jsonPrimitive?.intOrNull ?: return
        (obj["s"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull?.let { seq = it }
        val d = obj["d"]
        when (op) {
            OP_HELLO -> {
                val interval = d?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_250L
                startHeartbeat(interval)
                val token = tokenProvider() ?: return fail("Not logged in", authError = true)
                if (resumeIntent && sessionId != null) sendResume(token) else sendIdentify(token)
            }
            OP_HEARTBEAT_ACK -> awaitingAck = false
            OP_HEARTBEAT -> sendHeartbeat()
            OP_RECONNECT -> {
                AppLog.i(TAG, "Server asked to reconnect")
                teardown(4000, "reconnect")
                connect(resume = true)
            }
            OP_INVALID_SESSION -> {
                val resumable = (d as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
                AppLog.w(TAG, "Invalid session (resumable=$resumable)")
                if (!resumable) { sessionId = null; seq = null; resumeUrl = null }
                teardown(4000, "invalid session")
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    delay(Random.nextLong(1000, 5000))
                    connect(resume = resumable)
                }
            }
            OP_DISPATCH -> dispatch(obj["t"]?.jsonPrimitive?.contentOrNull, d)
        }
    }

    private fun dispatch(type: String?, d: JsonElement?) {
        when (type) {
            "READY" -> {
                val data = d?.jsonObject ?: return
                sessionId = data["session_id"]?.jsonPrimitive?.contentOrNull
                resumeUrl = data["resume_gateway_url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { isTrustedGateway(it) }
                user = data["user"]?.let { runCatching { ZrpcJson.decodeFromJsonElement(DiscordUser.serializer(), it) }.getOrNull() }
                onReady("READY as ${user?.username ?: "unknown"}")
            }
            "RESUMED" -> onReady("Session resumed")
        }
    }

    private fun onReady(msg: String) {
        ready = true
        attempts = 0
        _state.value = GatewayState.Connected(user)
        AppLog.i(TAG, msg)
        lastPresenceAt = 0
        schedulePresence()
    }

    private fun sendIdentify(token: String) {
        val c = clientType()
        val d = buildJsonObject {
            put("token", token)
            putJsonObject("properties") {
                put("os", c.os)
                put("browser", c.browser)
                put("device", c.device)
            }
            put("compress", false)
            desired?.let { put("presence", ZrpcJson.encodeToJsonElement(Presence.serializer(), it)) }
        }
        send(OP_IDENTIFY, d, logLabel = "IDENTIFY as ${c.label} (token redacted)")
    }

    private fun sendResume(token: String) {
        val d = buildJsonObject {
            put("token", token)
            put("session_id", sessionId)
            put("seq", seq)
        }
        send(OP_RESUME, d, logLabel = "RESUME (token redacted)")
    }

    private fun startHeartbeat(interval: Long) {
        heartbeatJob?.cancel()
        awaitingAck = false
        heartbeatJob = scope.launch {
            delay((interval * Random.nextDouble()).toLong())
            while (isActive) {
                if (awaitingAck) {
                    AppLog.w(TAG, "No heartbeat ACK, connection is a zombie")
                    socket?.cancel() // -> onFailure -> resume
                    return@launch
                }
                awaitingAck = true
                sendHeartbeat()
                delay(interval)
            }
        }
    }

    private fun sendHeartbeat() {
        send(OP_HEARTBEAT, seq?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull, quiet = true)
    }

    private fun schedulePresence() {
        if (!ready) return
        presenceJob?.cancel()
        val wait = (lastPresenceAt + MIN_PRESENCE_GAP_MS - clock()).coerceAtLeast(0)
        presenceJob = scope.launch {
            delay(wait)
            val p = desired ?: Presence(status = "online")
            send(OP_PRESENCE, ZrpcJson.encodeToJsonElement(Presence.serializer(), p),
                logLabel = "Presence: ${p.activities.firstOrNull()?.name ?: "cleared"} · ${p.status}")
            lastPresenceAt = clock()
        }
    }

    private fun send(op: Int, d: JsonElement, logLabel: String? = null, quiet: Boolean = false) {
        val frame = JsonObject(mapOf("op" to kotlinx.serialization.json.JsonPrimitive(op), "d" to d)).toString()
        val ok = socket?.send(frame) ?: false
        if (!quiet) AppLog.d(TAG, (logLabel ?: "op $op") + if (ok) "" else " (not sent)")
    }

    private inner class Listener(private val gen: Int, private val resume: Boolean) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { if (gen == generation) handle(text, resume) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { if (gen == generation) onDisconnected(code, reason) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch { if (gen == generation) onDisconnected(1006, t.javaClass.simpleName) }
        }
    }

    private fun onDisconnected(code: Int, reason: String) {
        heartbeatJob?.cancel()
        ready = false
        socket = null
        if (!wanted) {
            _state.value = GatewayState.Idle
            return
        }
        AppLog.w(TAG, "Disconnected ($code${if (reason.isNotBlank()) ": $reason" else ""})")
        when (code) {
            4004 -> fail("Discord rejected the token (4004). Please log in again.", authError = true)
            4010, 4011, 4012, 4013, 4014 -> fail("Gateway refused the connection ($code)", authError = false)
            4007, 4009 -> {
                sessionId = null; seq = null
                scheduleReconnect(resume = false, reason = "session expired")
            }
            else -> scheduleReconnect(resume = sessionId != null, reason = "closed $code")
        }
    }

    companion object {
        const val DEFAULT_URL = "wss://gateway.discord.gg"
        private const val TAG = "Gateway"
        private const val MIN_PRESENCE_GAP_MS = 4_500L
        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_PRESENCE = 3
        const val OP_RESUME = 6
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10
        const val OP_HEARTBEAT_ACK = 11
    }
}
