package io.github.zrpc.discord

import io.github.zrpc.discord.model.ApplicationAsset
import io.github.zrpc.discord.model.ApplicationInfo
import io.github.zrpc.discord.model.DiscordUser
import io.github.zrpc.discord.model.ExternalAsset
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Discord REST. [client] is the authenticated client built in AppContainer:
 * it attaches the token itself and refuses non-Discord hosts, so nothing in
 * this class handles the token directly (except [verifyToken], which passes a
 * candidate token for a single check before it is saved).
 */
class DiscordApi(
    private val client: OkHttpClient,
    private val base: HttpUrl = "https://discord.com/api/v10/".toHttpUrl(),
) {
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String): HttpUrl = base.resolve(path) ?: error("bad path")

    suspend fun me(): DiscordUser {
        val body = client.newCall(Request.Builder().url(url("users/@me")).get().build()).await().bodyOrThrow()
        return ZrpcJson.decodeFromString(DiscordUser.serializer(), body)
    }

    /** Checks a token before it is stored. Returns the user, or throws. */
    suspend fun verifyToken(token: String): DiscordUser {
        val req = Request.Builder().url(url("users/@me")).header("Authorization", token).get().build()
        val body = client.newCall(req).await().bodyOrThrow()
        return ZrpcJson.decodeFromString(DiscordUser.serializer(), body)
    }

    /**
     * Turns public image URLs into `mp:external/...` asset paths Discord will
     * render in a presence. Runs against discord.com only.
     */
    suspend fun externalAssets(applicationId: String, urls: List<String>): List<ExternalAsset> {
        val payload = buildJsonObject {
            putJsonArray("urls") { urls.forEach { add(it) } }
        }.toString()
        val req = Request.Builder()
            .url(url("applications/$applicationId/external-assets"))
            .post(payload.toRequestBody(jsonType))
            .build()
        val body = client.newCall(req).await().bodyOrThrow()
        return ZrpcJson.decodeFromString(ListSerializer(ExternalAsset.serializer()), body)
    }

    suspend fun applicationAssets(applicationId: String): List<ApplicationAsset> {
        val req = Request.Builder().url(url("oauth2/applications/$applicationId/assets")).get().build()
        val body = client.newCall(req).await().bodyOrThrow()
        return ZrpcJson.decodeFromString(ListSerializer(ApplicationAsset.serializer()), body)
    }

    suspend fun applicationInfo(applicationId: String): ApplicationInfo {
        val req = Request.Builder().url(url("applications/$applicationId/rpc")).get().build()
        val body = client.newCall(req).await().bodyOrThrow()
        return ZrpcJson.decodeFromString(ApplicationInfo.serializer(), body)
    }
}
