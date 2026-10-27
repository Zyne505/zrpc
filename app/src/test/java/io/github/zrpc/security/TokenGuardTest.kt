package io.github.zrpc.security

import io.github.zrpc.TestTokens
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscordHostsTest {
    @Test fun `trusts discord hosts over https only`() {
        assertTrue(DiscordHosts.isTrusted("https://discord.com/api/v10/users/@me".toHttpUrl()))
        assertTrue(DiscordHosts.isTrusted("https://canary.discord.com/x".toHttpUrl()))
        assertTrue(DiscordHosts.isTrusted("https://cdn.discordapp.com/x".toHttpUrl()))
        assertFalse(DiscordHosts.isTrusted("http://discord.com/x".toHttpUrl()))
    }

    @Test fun `rejects lookalike hosts`() {
        listOf(
            "https://discord.com.evil.io/", "https://evildiscord.com/", "https://discord-app.com/",
            "https://discordcom.io/", "https://catbox.moe/", "https://play.google.com/",
        ).forEach { assertFalse(it, DiscordHosts.isTrusted(it.toHttpUrl())) }
    }

    @Test fun `gateway urls must be wss discord`() {
        assertTrue(DiscordHosts.isTrustedGateway("wss://gateway.discord.gg"))
        assertTrue(DiscordHosts.isTrustedGateway("wss://gateway-us-east1-b.discord.gg"))
        assertFalse(DiscordHosts.isTrustedGateway("ws://gateway.discord.gg"))
        assertFalse(DiscordHosts.isTrustedGateway("wss://gateway.discord.gg.evil.io"))
        assertFalse(DiscordHosts.isTrustedGateway("wss://evil.io"))
        assertFalse(DiscordHosts.isTrustedGateway("not a url"))
    }
}

class InterceptorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `auth client refuses non-discord hosts and sends nothing`() {
        val client = OkHttpClient.Builder().addInterceptor(DiscordAuthInterceptor { TestTokens.STANDARD }).build()
        server.enqueue(MockResponse().setBody("ok"))
        assertThrows(TokenLeakBlockedException::class.java) {
            client.newCall(Request.Builder().url(server.url("/steal")).build()).execute()
        }
        assertEquals("no request may reach the server", 0, server.requestCount)
    }

    @Test fun `public client strips authorization header`() {
        val client = OkHttpClient.Builder().addInterceptor(NoAuthInterceptor { TestTokens.STANDARD }).build()
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/img")).header("Authorization", TestTokens.STANDARD).build()).execute().close()
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test fun `public client blocks token in url`() {
        val client = OkHttpClient.Builder().addInterceptor(NoAuthInterceptor { TestTokens.STANDARD }).build()
        assertThrows(TokenLeakBlockedException::class.java) {
            client.newCall(Request.Builder().url(server.url("/x?t=${TestTokens.STANDARD}")).build()).execute()
        }
        assertEquals(0, server.requestCount)
    }
}
