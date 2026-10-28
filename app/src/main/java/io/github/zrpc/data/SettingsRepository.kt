package io.github.zrpc.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zrpc.discord.ZrpcJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Templates used by Experimental RPC. Placeholders: {app} {title} {artist} {album} */
@Serializable
data class ExperimentalConfig(
    val appDetails: String = "Using {app}",
    val appState: String = "",
    val mediaDetails: String = "{title}",
    val mediaState: String = "by {artist}",
    val preferMedia: Boolean = true,
    val showAppIcon: Boolean = true,
)

@Serializable
data class ConsoleConfig(
    val platform: String = "PS5",
    val game: String = "",
    val coverUrl: String = "",
    val state: String = "",
    val timestamps: Boolean = true,
    val recent: List<String> = emptyList(),
)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val appLock: Boolean = false,
    val secureEverywhere: Boolean = false,
    /** Never blank: falls back to Zrpc's built-in Discord application, like Kizzy does. */
    val applicationId: String = DEFAULT_APPLICATION_ID,
    val status: String = "online",
    val gatewayClient: String = "ANDROID",
    val allowImageUpload: Boolean = false,
    val uploadAlbumArt: Boolean = false,
    val usePlayStoreIcons: Boolean = true,
    val enabledApps: Set<String> = emptySet(),
    val mediaHidePaused: Boolean = true,
    val mediaTimestamps: Boolean = true,
    val mediaShowAlbum: Boolean = true,
    val wakeLock: Boolean = false,
    val logcatMirror: Boolean = false,
    val lastCustom: RpcConfig = RpcConfig(),
    val console: ConsoleConfig = ConsoleConfig(),
    val experimental: ExperimentalConfig = ExperimentalConfig(),
) {
    companion object {
        /** Zrpc's own Discord application. Used for images whenever the user hasn't set their own ID. */
        const val DEFAULT_APPLICATION_ID = "1564961954731397310"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val appLock = booleanPreferencesKey("app_lock")
        val secureAll = booleanPreferencesKey("secure_everywhere")
        val appId = stringPreferencesKey("application_id")
        val status = stringPreferencesKey("status")
        val client = stringPreferencesKey("gateway_client")
        val upload = booleanPreferencesKey("allow_image_upload")
        val uploadArt = booleanPreferencesKey("upload_album_art")
        val playIcons = booleanPreferencesKey("play_store_icons")
        val apps = stringSetPreferencesKey("enabled_apps")
        val hidePaused = booleanPreferencesKey("media_hide_paused")
        val mediaTs = booleanPreferencesKey("media_timestamps")
        val mediaAlbum = booleanPreferencesKey("media_album")
        val wake = booleanPreferencesKey("wake_lock")
        val logcat = booleanPreferencesKey("logcat_mirror")
        val custom = stringPreferencesKey("last_custom")
        val console = stringPreferencesKey("console")
        val experimental = stringPreferencesKey("experimental")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            appLock = p[K.appLock] ?: d.appLock,
            secureEverywhere = p[K.secureAll] ?: d.secureEverywhere,
            applicationId = p[K.appId]?.takeIf { it.isNotBlank() } ?: d.applicationId,
            status = p[K.status] ?: d.status,
            gatewayClient = p[K.client] ?: d.gatewayClient,
            allowImageUpload = p[K.upload] ?: d.allowImageUpload,
            uploadAlbumArt = p[K.uploadArt] ?: d.uploadAlbumArt,
            usePlayStoreIcons = p[K.playIcons] ?: d.usePlayStoreIcons,
            enabledApps = p[K.apps] ?: d.enabledApps,
            mediaHidePaused = p[K.hidePaused] ?: d.mediaHidePaused,
            mediaTimestamps = p[K.mediaTs] ?: d.mediaTimestamps,
            mediaShowAlbum = p[K.mediaAlbum] ?: d.mediaShowAlbum,
            wakeLock = p[K.wake] ?: d.wakeLock,
            logcatMirror = p[K.logcat] ?: d.logcatMirror,
            lastCustom = p[K.custom].decodeOr(d.lastCustom),
            console = p[K.console].decodeOr(d.console),
            experimental = p[K.experimental].decodeOr(d.experimental),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private inline fun <reified T> String?.decodeOr(default: T): T =
        this?.let { runCatching { ZrpcJson.decodeFromString<T>(it) }.getOrNull() } ?: default

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setTheme(v: ThemeMode) = set(K.theme, v.name)
    suspend fun setDynamicColor(v: Boolean) = set(K.dynamic, v)
    suspend fun setAppLock(v: Boolean) = set(K.appLock, v)
    suspend fun setSecureEverywhere(v: Boolean) = set(K.secureAll, v)
    suspend fun setApplicationId(v: String) = set(K.appId, v.trim())
    suspend fun setStatus(v: String) = set(K.status, v)
    suspend fun setGatewayClient(v: String) = set(K.client, v)
    suspend fun setAllowImageUpload(v: Boolean) = set(K.upload, v)
    suspend fun setUploadAlbumArt(v: Boolean) = set(K.uploadArt, v)
    suspend fun setUsePlayStoreIcons(v: Boolean) = set(K.playIcons, v)
    suspend fun setEnabledApps(v: Set<String>) = set(K.apps, v)
    suspend fun setMediaHidePaused(v: Boolean) = set(K.hidePaused, v)
    suspend fun setMediaTimestamps(v: Boolean) = set(K.mediaTs, v)
    suspend fun setMediaShowAlbum(v: Boolean) = set(K.mediaAlbum, v)
    suspend fun setWakeLock(v: Boolean) = set(K.wake, v)
    suspend fun setLogcatMirror(v: Boolean) = set(K.logcat, v)
    suspend fun setLastCustom(v: RpcConfig) = set(K.custom, ZrpcJson.encodeToString(RpcConfig.serializer(), v))
    suspend fun setConsole(v: ConsoleConfig) = set(K.console, ZrpcJson.encodeToString(ConsoleConfig.serializer(), v))
    suspend fun setExperimental(v: ExperimentalConfig) = set(K.experimental, ZrpcJson.encodeToString(ExperimentalConfig.serializer(), v))

    /** Logout: drop everything tied to the account. Theme etc. are kept. */
    suspend fun clearAccountData() {
        context.dataStore.edit { it.remove(K.custom) }
    }
}
