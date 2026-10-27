package io.github.zrpc.discord.gateway

import io.github.zrpc.TestTokens
import io.github.zrpc.discord.gateway.ScriptedGatewayServer.Companion.isOp
import io.github.zrpc.log.AppLog
import io.github.zrpc.security.TokenRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the reconnect loop. Each one uses only the gateway's
 * original public API, so they can be run against the old implementation to
 * show the bug (see the patch notes).
 */
class GatewayReconnectRegressionTest {
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

    private fun gateway(server: ScriptedGatewayServer, parent: CoroutineScope = scope) = DiscordGateway(
        client = OkHttpClient(),
        tokenProvider = { TestTokens.STANDARD },
        clientType = { GatewayClient.ANDROID },
        parentScope = parent,
        endpoint = server.wsUrl,
        isTrustedGateway = { true },
    )

    /**
     * Bug: attempts was reset to 0 on every READY/RESUMED, so a session that
     * Discord drops right after READY retried every ~1 s forever.
     */
    @Test fun `backoff keeps growing when the session drops right after READY`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 12) { conn, ws, frame ->
            when {
                isOp(frame, 2) -> { ready(ws, "s$conn"); ws.close(4000, "drop") }
                isOp(frame, 6) -> { resumed(ws); ws.close(4000, "drop") }
            }
        }.also { fake = it }
        val gw = gateway(s)
        gw.start()
        val st = withTimeout(8_000) {
            gw.state.first { it is GatewayState.Reconnecting && it.attempt >= 2 }
        } as GatewayState.Reconnecting
        assertTrue("second consecutive drop must back off longer", st.delayMs >= 2_000)
        gw.stop()
    }

    /**
     * Bug: op 9 INVALID_SESSION reconnected on a fixed 1-5 s timer without
     * counting the attempt or reporting Reconnecting, so repeated
     * invalidations looped silently and nudge() could not see it.
     */
    @Test fun `invalid session is reported and counted as a reconnect attempt`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 4) { _, ws, frame ->
            if (isOp(frame, 2)) ws.send("""{"op":9,"d":false,"s":null,"t":null}""")
        }.also { fake = it }
        val gw = gateway(s)
        gw.start()
        val st = withTimeout(6_000) { gw.state.first { it is GatewayState.Reconnecting } } as GatewayState.Reconnecting
        assertEquals(1, st.attempt)
        assertTrue(st.reason.contains("invalid session"))
        gw.stop()
    }

    /** A connection whose heartbeats are never ACKed is a zombie: drop it and RESUME. */
    @Test fun `zombie connection is replaced and the session resumed`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 3, helloIntervalMs = 300) { conn, ws, frame ->
            if (conn == 0 && isOp(frame, 2)) ready(ws, "abc") // never ACK heartbeats
        }.also { fake = it }
        val gw = gateway(s)
        gw.start()
        val resume = s.next { it.conn == 1 && isOp(it.text, 6) }
        assertTrue("resume carries the old session", resume.text.contains("\"session_id\":\"abc\""))
        gw.stop()
    }

    /**
     * Bug: RpcService calls stop() and then stopSelf(); onDestroy cancels the
     * parent scope, which could drop the queued stop() and leave the socket
     * (and a ghost session) open.
     */
    @Test fun `stop still closes the socket when the parent scope is cancelled right after`() = runBlocking {
        val s = ScriptedGatewayServer(connections = 2) { conn, ws, frame ->
            if (isOp(frame, 2)) ready(ws, "s$conn")
        }.also { fake = it }
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gw = gateway(s, parent)
        gw.start()
        withTimeout(10_000) { gw.state.first { it is GatewayState.Connected } }
        gw.stop()
        parent.cancel()
        val closed = s.clientCloseCodes.poll(5, TimeUnit.SECONDS)
        assertTrue("client must close the socket on stop", closed != null)
        assertEquals("a deliberate stop closes with 1000", 1000, closed!!.second)
    }
}
