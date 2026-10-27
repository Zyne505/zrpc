package io.github.zrpc.discord

import io.github.zrpc.log.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts whatever the user typed into an image field into something a
 * presence can display:
 *
 *  - blank                -> no image
 *  - `mp:...`             -> used as-is
 *  - `https://...` URL    -> proxied through Discord's external-assets API (discord.com only)
 *  - anything else        -> treated as an asset *name* of your Discord application
 */
class AssetResolver(private val api: DiscordApi) {
    private val external = ConcurrentHashMap<String, String>()
    private val appAssets = ConcurrentHashMap<String, Map<String, String>>()

    suspend fun resolve(input: String?, applicationId: String?): String? {
        val value = input?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("mp:")) return value
        if (value.startsWith("https://") || value.startsWith("http://")) {
            if (applicationId.isNullOrBlank()) {
                AppLog.w(TAG, "Images from URLs need an Application ID (Settings)")
                return null
            }
            external[value]?.let { return it }
            return runCatching {
                api.externalAssets(applicationId, listOf(value)).firstOrNull()?.let { "mp:${it.externalAssetPath}" }
            }.onFailure { AppLog.w(TAG, "Couldn't proxy image", it) }
                .getOrNull()?.also { external[value] = it }
        }
        if (applicationId.isNullOrBlank()) return value
        val assets = appAssets[applicationId] ?: runCatching {
            api.applicationAssets(applicationId).associate { it.name.lowercase() to it.id }
        }.getOrElse { emptyMap() }.also { appAssets[applicationId] = it }
        return assets[value.lowercase()] ?: value
    }

    fun clear() {
        external.clear()
        appAssets.clear()
    }

    private companion object { const val TAG = "Assets" }
}
