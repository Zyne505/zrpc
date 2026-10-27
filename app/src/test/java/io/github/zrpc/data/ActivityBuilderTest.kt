package io.github.zrpc.data

import io.github.zrpc.discord.ZrpcJson
import io.github.zrpc.discord.model.ActivityType
import io.github.zrpc.discord.model.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityBuilderTest {
    private val appId = "123456789012345678"

    @Test fun `buttons become labels plus metadata urls`() {
        val a = ActivityBuilder.build(
            RpcConfig(name = "Test", button1Label = "Site", button1Url = "https://example.com", button2Label = "Bad", button2Url = "javascript:alert(1)"),
            ResolvedImages(), appId,
        )
        assertEquals(listOf("Site"), a.buttons)
        assertEquals(listOf("https://example.com"), a.metadata?.buttonUrls)
    }

    @Test fun `button labels are truncated to 32 chars`() {
        val a = ActivityBuilder.build(RpcConfig(name = "x", button1Label = "L".repeat(50), button1Url = "https://a.b"), ResolvedImages(), null)
        assertEquals(32, a.buttons!!.first().length)
    }

    @Test fun `single char fields are padded, empty fields dropped`() {
        val a = ActivityBuilder.build(RpcConfig(name = "x", details = "a", state = "  "), ResolvedImages(), null)
        assertEquals(2, a.details!!.length)
        assertNull(a.state)
    }

    @Test fun `streaming without url falls back to playing`() {
        val a = ActivityBuilder.build(RpcConfig(name = "x", type = ActivityType.STREAMING.code), ResolvedImages(), null)
        assertEquals(ActivityType.PLAYING.code, a.type)
        val b = ActivityBuilder.build(RpcConfig(name = "x", type = 1, streamUrl = "https://twitch.tv/me"), ResolvedImages(), null)
        assertEquals(1, b.type)
        assertEquals("https://twitch.tv/me", b.url)
    }

    @Test fun `invalid application id is dropped`() {
        assertNull(ActivityBuilder.build(RpcConfig(name = "x"), ResolvedImages(), "abc").applicationId)
        assertEquals(appId, ActivityBuilder.build(RpcConfig(name = "x"), ResolvedImages(), appId).applicationId)
    }

    @Test fun `assets only when an image resolved`() {
        val none = ActivityBuilder.build(RpcConfig(name = "x", largeText = "hover"), ResolvedImages(), appId)
        assertNull(none.assets)
        val some = ActivityBuilder.build(RpcConfig(name = "x", largeText = "hover"), ResolvedImages(large = "mp:external/abc"), appId)
        assertEquals("mp:external/abc", some.assets?.largeImage)
        assertEquals("hover", some.assets?.largeText)
    }

    @Test fun `timestamps use now when no start given, end must be after start`() {
        val a = ActivityBuilder.build(RpcConfig(name = "x", timestamps = true, endMs = 10), ResolvedImages(), null, now = 1000)
        assertEquals(1000L, a.timestamps?.start)
        assertNull(a.timestamps?.end)
    }

    @Test fun `party requires valid size`() {
        assertNull(ActivityBuilder.build(RpcConfig(name = "x", partyCurrent = 5, partyMax = 2), ResolvedImages(), null).party)
        assertEquals(listOf(2, 4), ActivityBuilder.build(RpcConfig(name = "x", partyCurrent = 2, partyMax = 4), ResolvedImages(), null).party?.size)
    }

    @Test fun `serialized presence omits nulls and keeps type`() {
        val a = ActivityBuilder.build(RpcConfig(name = "Game"), ResolvedImages(), null)
        val json = ZrpcJson.encodeToString(Presence.serializer(), Presence(activities = listOf(a)))
        assertTrue(json.contains("\"type\":0"))
        assertFalse(json.contains("null"))
    }
}
