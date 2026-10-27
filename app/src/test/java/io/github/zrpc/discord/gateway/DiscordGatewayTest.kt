package io.github.zrpc.discord.gateway

import io.github.zrpc.TestTokens
import io.github.zrpc.discord.model.Activity
import io.github.zrpc.discord.model.Presence
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DiscordGatewayTest {
    private lateinit var server: MockWebServer
    private val frames = LinkedBlockingQueue<String>()
    private var serverSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        AppLog.clear()
        AppLog.redactor = TokenRedactor { TestTokens.STANDARD }
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                webSocket.send("""{"op":10,"d":{"heartbeat_interval":45000},"s":null,"t":null}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
        }))
        server.start()
    }

    @After fun tearDown() {
        runCatching { serverSocket?.close(1000, null) }
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun next(): String = frames.poll(10, TimeUnit.SECONDS) ?: error("timed out waiting for a frame")

    private fun gateway() = DiscordGateway(
        client = OkHttpClient(),
        tokenProvider = { TestTokens.STANDARD },
        clientType = { GatewayClient.ANDROID },
        parentScope = scope,
        endpoint = server.url("/").toString().replace("http://", "ws://"),
        isTrustedGateway = { true },
    )

    @Test fun `identifies, becomes ready, sends presence, never logs token`() = runBlocking {
        val gw = gateway()
        gw.setPresence(Presence(activities = listOf(Activity(name = "Minecraft")), status = "idle"))
        gw.start()

        var frame = next()
        // First heartbeat may race the identify; skip heartbeats.
        while (frame.contains("\"op\":1")) frame = next()
        assertTrue(frame.contains("\"op\":2"))
        assertTrue("identify carries the token", frame.contains(TestTokens.STANDARD))
        assertTrue(frame.contains("Discord Android"))

        serverSocket!!.send(
            """{"op":0,"t":"READY","s":1,"d":{"session_id":"abc","resume_gateway_url":"wss://evil.io",
               "user":{"id":"1","username":"tester"}}}"""
        )
        withTimeout(10_000) { gw.state.first { it is GatewayState.Connected } }

        var presence = next()
        while (!presence.contains("\"op\":3")) presence = next()
        assertTrue(presence.contains("Minecraft"))
        assertTrue(presence.contains("\"status\":\"idle\""))
        assertFalse("presence frames never include the token", presence.contains(TestTokens.STANDARD))

        val logs = AppLog.export()
        assertFalse("token must not appear in logs", logs.contains(TestTokens.STANDARD))
        assertTrue(logs.contains("IDENTIFY"))
        gw.stop()
        withTimeout(10_000) { gw.state.first { it is GatewayState.Idle } }
        Unit
    }

    @Test fun `auth failure 4004 stops and reports`() = runBlocking {
        val gw = gateway()
        val authFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        gw.onAuthFailed = { authFailed.set(true) }
        gw.start()
        var frame = next()
        while (!frame.contains("\"op\":2")) frame = next()
        serverSocket!!.close(4004, "Authentication failed.")
        val state = withTimeout(10_000) { gw.state.first { it is GatewayState.Failed } }
        assertTrue((state as GatewayState.Failed).authError)
        assertTrue(authFailed.get())
        assertEquals(1, server.requestCount) // no reconnect loop with a bad token
    }
}
