package io.github.zrpc.discord.gateway

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A fake Discord gateway for tests. Every accepted connection gets HELLO, then
 * each client frame is recorded and handed to [script], which can reply
 * (READY, op 9, close codes...) to drive the client through a scenario.
 * No real token or Discord host is involved.
 */
class ScriptedGatewayServer(
    connections: Int,
    private val helloIntervalMs: Long = 45_000,
    private val script: ScriptedGatewayServer.(conn: Int, ws: WebSocket, frame: String) -> Unit,
) {
    data class Frame(val conn: Int, val text: String)

    val server = MockWebServer()
    val frames = LinkedBlockingQueue<Frame>()
    /** Close codes the client sent back, per connection. */
    val clientCloseCodes = LinkedBlockingQueue<Pair<Int, Int>>()
    private val sockets = mutableMapOf<Int, WebSocket>()

    init {
        repeat(connections) { i ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(sockets) { sockets[i] = webSocket }
                    webSocket.send("""{"op":10,"d":{"heartbeat_interval":$helloIntervalMs},"s":null,"t":null}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(Frame(i, text))
                    script(i, webSocket, text)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    clientCloseCodes.add(i to code)
                    runCatching { webSocket.close(1000, null) }
                }
            }))
        }
        server.start()
    }

    val wsUrl: String get() = server.url("/").toString().replace("http://", "ws://")

    fun socket(conn: Int): WebSocket? = synchronized(sockets) { sockets[conn] }

    /** Next client frame matching [predicate] (heartbeats etc. are skipped). */
    fun next(timeoutSec: Long = 10, predicate: (Frame) -> Boolean): Frame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (true) {
            val left = deadline - System.nanoTime()
            val f = frames.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: error("timed out waiting for a frame")
            if (predicate(f)) return f
        }
    }

    fun ready(ws: WebSocket, session: String) = ws.send(
        """{"op":0,"t":"READY","s":1,"d":{"session_id":"$session","user":{"id":"1","username":"tester"}}}"""
    )

    fun resumed(ws: WebSocket) = ws.send("""{"op":0,"t":"RESUMED","s":2,"d":null}""")

    fun shutdown() {
        synchronized(sockets) { sockets.values.forEach { runCatching { it.close(1000, null) } } }
        runCatching { server.shutdown() }
    }

    companion object {
        fun isOp(text: String, op: Int) = text.contains("\"op\":$op,") || text.contains("\"op\":$op}")
    }
}
