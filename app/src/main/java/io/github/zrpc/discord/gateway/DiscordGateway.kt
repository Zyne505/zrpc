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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.serialization.json.JsonPrimitive
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

/** What to do after the socket closes, following Discord's documented gateway close codes. */
enum class CloseAction { RESUME, REIDENTIFY, FATAL_AUTH, FATAL }

internal object GatewayCloseCodes {
    fun action(code: Int): CloseAction = when (code) {
        4004 -> CloseAction.FATAL_AUTH
        4010, 4011, 4012, 4013, 4014 -> CloseAction.FATAL
        // No usable session on the server side: start a new one.
        4003, 4005, 4007, 4009 -> CloseAction.REIDENTIFY
        else -> CloseAction.RESUME
    }

    /** Our own payload was rejected; resending the same payload cannot succeed. */
    fun isProtocolError(code: Int) = code == 4001 || code == 4002

    fun describe(code: Int): String = when (code) {
        1000 -> "normal closure"
        1001 -> "going away"
        1006 -> "connection lost"
        4000 -> "unknown error"
        4001 -> "unknown opcode"
        4002 -> "decode error"
        4003 -> "not authenticated"
        4004 -> "authentication failed"
        4005 -> "already authenticated"
        4007 -> "invalid seq"
        4008 -> "rate limited"
        4009 -> "session timed out"
        4010 -> "invalid shard"
        4011 -> "sharding required"
        4012 -> "invalid API version"
        4013 -> "invalid intents"
        4014 -> "disallowed intents"
        else -> "code $code"
    }

    /** Codes OkHttp lets us send (RFC 6455 §7.4: 1004-1006 and 1015-2999 are reserved). */
    fun isSendable(code: Int) = code in 1000..4999 && code !in 1004..1006 && code !in 1015..2999
}

/**
 * Minimal, resilient Discord gateway client for presence only.
 *
 * - Handles HELLO / heartbeat / ACK, zombie connections, RECONNECT,
 *   INVALID_SESSION and RESUME, with exponential backoff.
 * - The backoff is only forgiven after a connection has stayed up for
 *   [stableAfterMs], so "connect, get dropped" cannot spin in a tight loop.
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
    private val backoffBaseMs: Long = 1_000L,
    private val stableAfterMs: Long = 60_000L,
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
    private var protocolErrors = 0
    private var awaitingAck = false
    private var heartbeatSentAt = 0L
    private var connectedAt = 0L
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var presenceJob: Job? = null
    private var stableJob: Job? = null
    private var desired: Presence? = null
    private var lastPresenceAt = 0L

    fun start() {
        scope.launch {
            if (wanted) return@launch
            wanted = true
            attempts = 0
            protocolErrors = 0
            connect(resume = false)
        }
    }

    fun stop() {
        // NonCancellable: the service calls stop() and then stopSelf(), and
        // onDestroy cancels the parent scope. Without this the queued block
        // could be dropped, leaving the socket and a ghost session open.
        scope.launch(NonCancellable) {
            wanted = false
            reconnectJob?.cancel()
            presenceJob?.cancel()
            if (ready) send(OP_PRESENCE, presenceJson(Presence(status = desired?.status ?: "online")))
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

    /** A network became available: skip the remaining backoff. */
    fun nudge() {
        scope.launch {
            if (wanted && _state.value is GatewayState.Reconnecting) {
                AppLog.i(TAG, "Network available, retrying now")
                reconnectJob?.cancel()
                connect(resume = canResume())
            }
        }
    }

    /**
     * The default network switched (e.g. Wi-Fi to mobile data). A socket bound
     * to the old network goes silently dead and would otherwise only be noticed
     * after two missed heartbeat ACKs (~80 s), so resume on a fresh one now.
     */
    fun networkChanged() {
        scope.launch {
            if (!wanted) return@launch
            when (_state.value) {
                is GatewayState.Connected, is GatewayState.Reconnecting -> {
                    AppLog.i(TAG, "Default network changed, resuming on the new network")
                    reconnectJob?.cancel()
                    connect(resume = canResume())
                }
                else -> Unit // already connecting, on whatever network is current
            }
        }
    }

    // ---------------------------------------------------------------- connection

    private fun canResume() = sessionId != null && seq != null

    private fun connect(resume: Boolean) {
        // 4000 (not 1000/1001) keeps the session resumable on Discord's side.
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

    private fun teardown(code: Int, reason: String, hard: Boolean = false) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        stableJob?.cancel()
        stableJob = null
        ready = false
        socket?.let { s ->
            generation++ // ignore callbacks from the old socket
            runCatching { if (hard) s.cancel() else s.close(code, reason) }
        }
        socket = null
    }

    private fun fail(reason: String, authError: Boolean) {
        wanted = false
        reconnectJob?.cancel()
        teardown(1000, "failed")
        AppLog.e(TAG, reason)
        // Run the callback (e.g. wipe the vault) before anyone observes Failed.
        if (authError) onAuthFailed?.invoke()
        _state.value = GatewayState.Failed(reason, authError)
    }

    private fun scheduleReconnect(resume: Boolean, reason: String, minDelayMs: Long = 0) {
        if (!wanted) return
        attempts++
        val exp = (backoffBaseMs shl (attempts - 1).coerceAtMost(6)).coerceAtMost(MAX_BACKOFF_MS)
        val backoff = maxOf(exp, minDelayMs) + Random.nextLong(0, backoffBaseMs)
        _state.value = GatewayState.Reconnecting(attempts, backoff, reason)
        AppLog.w(TAG, "Reconnect #$attempts in ${backoff}ms ($reason)")
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
        (obj["s"] as? JsonPrimitive)?.longOrNull?.let { seq = it }
        val d = obj["d"]
        when (op) {
            OP_HELLO -> {
                val interval = d?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_250L
                AppLog.d(TAG, "HELLO, heartbeat every ${interval}ms")
                startHeartbeat(interval)
                val token = tokenProvider() ?: return fail("Not logged in", authError = true)
                if (resumeIntent && canResume()) sendResume(token) else sendIdentify(token)
            }
            OP_HEARTBEAT_ACK -> {
                awaitingAck = false
                val rtt = clock() - heartbeatSentAt
                if (heartbeatSentAt > 0 && rtt > SLOW_ACK_MS) AppLog.w(TAG, "Slow heartbeat ACK (${rtt}ms)")
            }
            OP_HEARTBEAT -> sendHeartbeat()
            OP_RECONNECT -> {
                AppLog.i(TAG, "Server asked to reconnect (op 7)")
                connect(resume = canResume())
            }
            OP_INVALID_SESSION -> {
                val resumable = (d as? JsonPrimitive)?.booleanOrNull ?: false
                AppLog.w(TAG, "Invalid session (resumable=$resumable)")
                if (!resumable) { sessionId = null; seq = null; resumeUrl = null }
                teardown(4000, "invalid session")
                // Discord asks for a 1-5 s wait. Counting it as an attempt makes
                // repeated invalidations back off instead of looping.
                scheduleReconnect(
                    resume = resumable,
                    reason = "invalid session",
                    minDelayMs = Random.nextLong(backoffBaseMs, backoffBaseMs * 5),
                )
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
        connectedAt = clock()
        _state.value = GatewayState.Connected(user)
        AppLog.i(TAG, msg)
        lastPresenceAt = 0
        schedulePresence()
        // Only forgive earlier failures once the session has stayed up a while.
        // Resetting on READY itself kept the backoff at ~1 s when Discord drops
        // the session right after it starts.
        stableJob?.cancel()
        stableJob = scope.launch {
            delay(stableAfterMs)
            if (attempts > 0) AppLog.d(TAG, "Connection stable, backoff reset")
            attempts = 0
            protocolErrors = 0
        }
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
            desired?.let { put("presence", presenceJson(it)) }
        }
        send(OP_IDENTIFY, d, logLabel = "IDENTIFY as ${c.label} (token redacted)")
    }

    private fun sendResume(token: String) {
        val d = buildJsonObject {
            put("token", token)
            put("session_id", sessionId)
            put("seq", seq)
        }
        send(OP_RESUME, d, logLabel = "RESUME seq=$seq (token redacted)")
    }

    private fun startHeartbeat(interval: Long) {
        heartbeatJob?.cancel()
        awaitingAck = false
        heartbeatSentAt = 0
        heartbeatJob = scope.launch {
            val first = (interval * Random.nextDouble()).toLong()
            var expectedAt = clock() + first
            delay(first)
            while (isActive) {
                // delay() runs on the monotonic clock, which stops while the CPU
                // is in deep sleep; the wall clock does not. A big gap means the
                // device dozed and Discord has probably already dropped us.
                val late = clock() - expectedAt
                if (late > interval / 2) {
                    AppLog.w(TAG, "Heartbeat ran ${late / 1000}s late: the CPU was asleep (Doze / battery saver). " +
                        "Enable the wake lock setting or exempt Zrpc from battery optimization.")
                }
                if (awaitingAck) {
                    AppLog.w(TAG, "No heartbeat ACK within ${interval}ms, connection is a zombie")
                    // Cancel rather than close: a dead link can't finish a close
                    // handshake. The session stays resumable.
                    teardown(4000, "zombie", hard = true)
                    scheduleReconnect(resume = canResume(), reason = "no heartbeat ACK")
                    return@launch
                }
                awaitingAck = true
                heartbeatSentAt = clock()
                sendHeartbeat()
                expectedAt = clock() + interval
                delay(interval)
            }
        }
    }

    private fun sendHeartbeat() {
        send(OP_HEARTBEAT, seq?.let { JsonPrimitive(it) } ?: JsonNull, quiet = true)
    }

    private fun schedulePresence() {
        if (!ready) return
        presenceJob?.cancel()
        val wait = (lastPresenceAt + MIN_PRESENCE_GAP_MS - clock()).coerceAtLeast(0)
        presenceJob = scope.launch {
            delay(wait)
            val p = desired ?: Presence(status = "online")
            send(OP_PRESENCE, presenceJson(p),
                logLabel = "Presence: ${p.activities.firstOrNull()?.name ?: "cleared"} · ${p.status}")
            lastPresenceAt = clock()
        }
    }

    private fun send(op: Int, d: JsonElement, logLabel: String? = null, quiet: Boolean = false) {
        val frame = JsonObject(mapOf("op" to JsonPrimitive(op), "d" to d)).toString()
        val ok = socket?.send(frame) ?: false
        if (!quiet) AppLog.d(TAG, (logLabel ?: "op $op") + if (ok) "" else " (not sent)")
    }

    private inner class Listener(private val gen: Int, private val resume: Boolean) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch { if (gen == generation) AppLog.d(TAG, "Socket open") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { if (gen == generation) handle(text, resume) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Echo the server's code (RFC 6455 §5.5.1). Reserved codes such as
            // 1005 "no status" can't be sent and used to throw here, which
            // OkHttp then reported as a bogus 1006 failure.
            val reply = if (GatewayCloseCodes.isSendable(code)) code else 4000
            runCatching { webSocket.close(reply, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { if (gen == generation) onDisconnected(code, reason) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Keep the cause: "SocketTimeoutException" alone can't tell a dead
            // network from an HTTP 429 on the handshake. No body is read.
            val http = response?.code
            val retryAfterMs = response?.header("Retry-After")?.trim()?.toLongOrNull()
                ?.coerceIn(0, MAX_RETRY_AFTER_S)?.times(1000) ?: 0L
            val detail = buildString {
                append(t.javaClass.simpleName)
                t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(160)) }
                if (http != null) append(" [HTTP ").append(http).append(']')
            }
            scope.launch { if (gen == generation) onDisconnected(1006, detail, retryAfterMs) }
        }
    }

    private fun onDisconnected(code: Int, reason: String, retryAfterMs: Long = 0) {
        heartbeatJob?.cancel()
        stableJob?.cancel()
        ready = false
        socket = null
        val upFor = if (connectedAt > 0) ", was up ${(clock() - connectedAt) / 1000}s" else ""
        connectedAt = 0
        if (!wanted) {
            _state.value = GatewayState.Idle
            return
        }
        val what = "$code ${GatewayCloseCodes.describe(code)}"
        AppLog.w(TAG, "Disconnected ($what${if (reason.isNotBlank()) ": $reason" else ""}$upFor)")
        when (GatewayCloseCodes.action(code)) {
            CloseAction.FATAL_AUTH -> fail("Discord rejected the token (4004). Please log in again.", authError = true)
            CloseAction.FATAL -> fail("Gateway refused the connection ($what)", authError = false)
            CloseAction.REIDENTIFY -> {
                sessionId = null; seq = null; resumeUrl = null
                scheduleReconnect(resume = false, reason = "closed $what")
            }
            CloseAction.RESUME -> {
                if (GatewayCloseCodes.isProtocolError(code) && ++protocolErrors >= MAX_PROTOCOL_ERRORS) {
                    fail("Discord keeps rejecting what Zrpc sends ($what). Stopped instead of looping; see Logs.", authError = false)
                    return
                }
                scheduleReconnect(resume = canResume(), reason = "closed $what", minDelayMs = retryAfterMs)
            }
        }
    }

    /** Update Presence requires `since` (nullable); ZrpcJson drops nulls, so put it back. */
    private fun presenceJson(p: Presence): JsonElement {
        val o = ZrpcJson.encodeToJsonElement(Presence.serializer(), p).jsonObject
        return if ("since" in o) o else JsonObject(o + ("since" to JsonNull))
    }

    companion object {
        const val DEFAULT_URL = "wss://gateway.discord.gg"
        private const val TAG = "Gateway"
        private const val MIN_PRESENCE_GAP_MS = 4_500L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_RETRY_AFTER_S = 600L
        private const val MAX_PROTOCOL_ERRORS = 3
        private const val SLOW_ACK_MS = 10_000L
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
