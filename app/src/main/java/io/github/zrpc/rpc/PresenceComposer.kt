package io.github.zrpc.rpc

import android.content.Context
import android.net.Uri
import io.github.zrpc.data.ActivityBuilder
import io.github.zrpc.data.AppSettings
import io.github.zrpc.data.ConsoleConfig
import io.github.zrpc.data.ResolvedImages
import io.github.zrpc.data.RpcConfig
import io.github.zrpc.discord.AssetResolver
import io.github.zrpc.discord.ImageHost
import io.github.zrpc.discord.PlayStoreIcons
import io.github.zrpc.discord.model.Activity
import io.github.zrpc.discord.model.ActivityType
import io.github.zrpc.log.AppLog
import io.github.zrpc.rpc.source.AppDetector
import io.github.zrpc.rpc.source.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ConsolePlatform(val id: String, val label: String) {
    PS5("PS5", "PlayStation 5"),
    PS4("PS4", "PlayStation 4"),
    XBOX_SERIES("XSX", "Xbox Series X|S"),
    XBOX_ONE("XB1", "Xbox One"),
    SWITCH("NSW", "Nintendo Switch"),
    SWITCH2("NS2", "Nintendo Switch 2"),
    WII_U("WIIU", "Wii U"),
    N3DS("3DS", "Nintendo 3DS"),
    STEAM_DECK("DECK", "Steam Deck"),
    PC("PC", "PC");

    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: PS5 }
}

/** Builds the Activity for each RPC mode. Everything image-related is resolved here. */
class PresenceComposer(
    private val context: Context,
    private val resolver: AssetResolver,
    private val imageHost: ImageHost,
    private val playIcons: PlayStoreIcons,
    private val apps: AppDetector,
) {
    private var appSincePkg: String? = null
    private var appSince: Long = 0

    // ------------------------------------------------------------- custom

    suspend fun custom(cfg: RpcConfig, s: AppSettings): Activity {
        val appId = cfg.applicationId.ifBlank { s.applicationId }.ifBlank { null }
        val large = image(cfg.largeImage, appId, s)
        val small = image(cfg.smallImage, appId, s)
        return ActivityBuilder.build(cfg, ResolvedImages(large, small), appId)
    }

    // ------------------------------------------------------------- console

    suspend fun console(c: ConsoleConfig, s: AppSettings): Activity? {
        if (c.game.isBlank()) return null
        val platform = ConsolePlatform.fromId(c.platform)
        val cfg = RpcConfig(
            name = c.game,
            details = c.game,
            state = c.state.ifBlank { "on ${platform.label}" },
            largeImage = c.coverUrl,
            largeText = "${c.game} · ${platform.label}",
            timestamps = c.timestamps,
            startMs = consoleSince(c),
        )
        return custom(cfg, s)
    }

    private var consoleKey: String? = null
    private var consoleStart = 0L
    private fun consoleSince(c: ConsoleConfig): Long {
        val key = c.platform + c.game
        if (key != consoleKey) { consoleKey = key; consoleStart = System.currentTimeMillis() }
        return consoleStart
    }

    // ------------------------------------------------------------- apps

    suspend fun app(pkg: String?, s: AppSettings, details: String? = null, state: String? = null): Activity? {
        if (pkg == null || pkg !in s.enabledApps) {
            appSincePkg = null
            return null
        }
        return appActivity(pkg, s, details, state)
    }

    private suspend fun appActivity(pkg: String, s: AppSettings, details: String?, state: String?): Activity {
        if (pkg != appSincePkg) { appSincePkg = pkg; appSince = System.currentTimeMillis() }
        val appId = s.applicationId.ifBlank { null }
        val icon = appIcon(pkg, appId, s)
        val label = apps.label(pkg)
        val cfg = RpcConfig(
            name = label,
            details = details.orEmpty(),
            state = state.orEmpty(),
            largeText = label,
            timestamps = true,
            startMs = appSince,
        )
        return ActivityBuilder.build(cfg, ResolvedImages(large = icon), appId)
    }

    // ------------------------------------------------------------- media

    suspend fun media(m: MediaInfo?, s: AppSettings, detailsTpl: String = "{title}", stateTpl: String = "by {artist}"): Activity? {
        if (m == null || (!m.isPlaying && s.mediaHidePaused)) return null
        val appId = s.applicationId.ifBlank { null }
        val playerName = apps.label(m.packageName)
        val large = if (s.uploadAlbumArt && s.allowImageUpload && m.art != null) {
            imageHost.upload(AppDetector.bitmapToPng(m.art))?.let { resolver.resolve(it, appId) }
        } else null
        val playerIcon = appIcon(m.packageName, appId, s)
        val type = if (m.packageName in VIDEO_APPS) ActivityType.WATCHING else ActivityType.LISTENING
        val now = System.currentTimeMillis()
        val withTs = s.mediaTimestamps && m.isPlaying && m.durationMs > 0
        val cfg = RpcConfig(
            name = playerName,
            type = type.code,
            details = fill(detailsTpl, playerName, m),
            state = fill(stateTpl, playerName, m).takeIf { m.artist.isNotBlank() || !stateTpl.contains("{artist}") }.orEmpty(),
            largeText = if (s.mediaShowAlbum && m.album.isNotBlank()) m.album else playerName,
            smallText = if (large != null) playerName else "",
            timestamps = withTs,
            startMs = if (withTs) now - m.positionMs else null,
            endMs = if (withTs) now - m.positionMs + m.durationMs else null,
            statusDisplay = 2,
        )
        val images = if (large != null) ResolvedImages(large, playerIcon) else ResolvedImages(playerIcon, null)
        return ActivityBuilder.build(cfg, images, appId)
    }

    // ------------------------------------------------------------- experimental

    suspend fun experimental(pkg: String?, m: MediaInfo?, s: AppSettings): Activity? {
        val e = s.experimental
        val mediaActivity = media(m, s, e.mediaDetails, e.mediaState)
        if (e.preferMedia && mediaActivity != null) return mediaActivity
        val eligible = pkg != null && (s.enabledApps.isEmpty() || pkg in s.enabledApps)
        if (eligible) {
            val label = apps.label(pkg!!)
            val a = appActivity(pkg, s, fillApp(e.appDetails, label), fillApp(e.appState, label))
            return if (e.showAppIcon) a else a.copy(assets = null)
        }
        return mediaActivity
    }

    // ------------------------------------------------------------- images

    private suspend fun image(input: String, appId: String?, s: AppSettings): String? {
        val v = input.trim()
        if (v.startsWith("content://")) {
            if (!s.allowImageUpload) {
                AppLog.w(TAG, "Gallery images need 'Allow image uploads' in Settings")
                return null
            }
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(Uri.parse(v))?.use { it.readBytes() } }.getOrNull()
            } ?: return null
            if (bytes.size > MAX_UPLOAD) {
                AppLog.w(TAG, "Image too large (max 8 MB)")
                return null
            }
            val mime = context.contentResolver.getType(Uri.parse(v)) ?: "image/png"
            val ext = mime.substringAfter('/', "png")
            val url = imageHost.upload(bytes, mime, "image.$ext") ?: return null
            return resolver.resolve(url, appId)
        }
        return resolver.resolve(v, appId)
    }

    private suspend fun appIcon(pkg: String, appId: String?, s: AppSettings): String? {
        if (appId == null) return null
        val url = (if (s.usePlayStoreIcons) playIcons.iconUrl(pkg) else null)
            ?: if (s.allowImageUpload) apps.iconPng(pkg)?.let { imageHost.upload(it) } else null
        return url?.let { resolver.resolve(it, appId) }
    }

    private fun fill(tpl: String, app: String, m: MediaInfo) = tpl
        .replace("{app}", app).replace("{title}", m.title)
        .replace("{artist}", m.artist).replace("{album}", m.album)

    private fun fillApp(tpl: String, app: String) = tpl.replace("{app}", app)
        .replace(Regex("""\{(title|artist|album)}"""), "")

    companion object {
        private const val TAG = "Composer"
        private const val MAX_UPLOAD = 8 * 1024 * 1024
        val VIDEO_APPS = setOf(
            "com.google.android.youtube", "app.revanced.android.youtube", "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", "in.startv.hotstar", "com.disney.disneyplus",
            "com.mxtech.videoplayer.ad", "org.videolan.vlc", "com.crunchyroll.crunchyroid",
            "tv.twitch.android.app", "com.jio.media.ondemand",
        )
    }
}
