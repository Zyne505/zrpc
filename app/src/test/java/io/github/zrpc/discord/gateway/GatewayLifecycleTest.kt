package io.github.zrpc.discord.gateway

import io.github.zrpc.TestTokens
import io.github.zrpc.discord.gateway.ScriptedGatewayServer.Companion.isOp
import io.github.zrpc.discord.model.Activity
import io.github.zrpc.discord.model.Presence
import io.github.zrpc.log.AppLog
import io.github.zrpc.security.TokenRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fake: ScriptedGatewayServer? = null

    @Before fun setUp() {
        AppLog.clear()
        AppLog.redactor = TokenRedactor { TestTokens.STANDARD }
    }

    @After fun tearDown() {
        scope.cancel()
        fake?.shutdown()
    }

    private fun gateway(server: ScriptedGatewayServer, stableAfterMs: Long = 60_000) = DiscordGateway(
        client = OkHttpClient(),
        tokenProvider = { TestTokens.STANDARD },
        clientType = { GatewayClient.ANDROID },
        parentScope = scope,
        endpoint = server.wsUrl,
        isTrustedGateway = { true },
        backoffBaseMs = 50,
        stableAfterMs = stableAfterMs,
    )

    @Test fun `close codes follow Discord's documented reconnect rules`() {
        assertEquals(CloseAction.FATAL_AUTH, GatewayCloseCodes.action(4004))
        for (c in 4010..4014) assertEquals(CloseAction.FATAL, GatewayCloseCodes.action(c))
        for (c in listOf(4003, 4005, 4007, 4009)) assertEquals(CloseAction.REIDENTIFY, GatewayCloseCodes.action(c))
        for (c in listOf(1000, 1001, 1006, 4000, 4001, 4002, 4008)) assertEquals(CloseAction.RESUME, GatewayCloseCodes.action(c))
        assertTrue(GatewayCloseCodes.isProtocolError(4002))
        assertFalse(GatewayCloseCodes.isProtocolError(4000))
        assertFalse("1005 can't be echoed", GatewayCloseCodes.isSendable(1005))
        assertTrue(GatewayCloseCodes.isSendable(4004))
    }

    @Test fun `network switch while connected resumes on a fresh socket`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 3) { conn, ws, frame ->
            if (isOp(frame, 2)) ready(ws, "s$conn")
            if (isOp(frame, 6)) resumed(ws)
        }.also { fake = it }
        val gw = gateway(s)
        gw.start()
        withTimeout(10_000) { gw.state.first { it is GatewayState.Connected } }
        gw.networkChanged()
        val resume = s.next { it.conn == 1 && isOp(it.text, 6) }
        assertTrue(resume.text.contains("\"session_id\":\"s0\""))
        // The old socket was closed with a resumable (non-1000) code.
        val closed = s.clientCloseCodes.poll(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(0 to 4000, closed)
        gw.stop()
    }

    @Test fun `repeated decode errors stop instead of looping forever`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 6) { conn, ws, frame ->
            when {
                isOp(frame, 2) -> { ready(ws, "s$conn"); ws.close(4002, "Error while decoding payload.") }
                isOp(frame, 6) -> { resumed(ws); ws.close(4002, "Error while decoding payload.") }
            }
        }.also { fake = it }
        val gw = gateway(s)
        gw.start()
        val st = withTimeout(10_000) { gw.state.first { it is GatewayState.Failed } } as GatewayState.Failed
        assertFalse(st.authError)
        assertTrue(st.reason.contains("4002"))
        assertEquals("gave up after 3 connections", 3, s.server.requestCount)
        assertTrue(AppLog.export().contains("4002 decode error"))
    }

    @Test fun `a connection that stays up resets the backoff`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 4) { conn, ws, frame ->
            if (isOp(frame, 2) || isOp(frame, 6)) {
                if (isOp(frame, 2)) ready(ws, "s$conn") else resumed(ws)
                if (conn == 0) ws.close(4000, "drop") // only the first session drops fast
            }
        }.also { fake = it }
        val gw = gateway(s, stableAfterMs = 300)
        gw.start()
        withTimeout(10_000) { gw.state.first { it is GatewayState.Reconnecting && it.attempt == 1 } }
        withTimeout(10_000) { gw.state.first { it is GatewayState.Connected } }
        delay(800) // longer than stableAfterMs
        s.socket(1)!!.close(4000, "later drop")
        val st = withTimeout(10_000) { gw.state.first { it is GatewayState.Reconnecting } } as GatewayState.Reconnecting
        assertEquals("backoff forgiven after a stable session", 1, st.attempt)
        gw.stop()
    }

    @Test fun `presence updates always carry the required since field`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 1) { conn, ws, frame ->
            if (isOp(frame, 2)) ready(ws, "s$conn")
        }.also { fake = it }
        val gw = gateway(s)
        gw.setPresence(Presence(activities = listOf(Activity(name = "Minecraft"))))
        gw.start()
        val identify = s.next { isOp(it.text, 2) }
        assertTrue(identify.text.contains("\"since\":null"))
        val presence = s.next { isOp(it.text, 3) }
        assertTrue(presence.text.contains("\"since\":null"))
        assertFalse(presence.text.contains(TestTokens.STANDARD))
        gw.stop()
    }

    @Test fun `logs explain disconnects without leaking the token`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 3) { conn, ws, frame ->
            if (conn == 0 && isOp(frame, 2)) { ready(ws, "s0"); ws.close(4008, "Rate limited.") }
            if (conn > 0 && isOp(frame, 6)) resumed(ws)
        }.also { fake = it }
        val gw = gateway(s)
        gw.start()
        withTimeout(10_000) { gw.state.first { it is GatewayState.Reconnecting } }
        withTimeout(10_000) { gw.state.first { it is GatewayState.Connected } }
        val logs = AppLog.export()
        assertTrue(logs, logs.contains("Disconnected (4008 rate limited: Rate limited."))
        assertTrue(logs.contains("Reconnect #1"))
        assertFalse(logs.contains(TestTokens.STANDARD))
        gw.stop()
    }
}
