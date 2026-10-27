package io.github.zrpc

import android.content.Context
import io.github.zrpc.data.ConfigRepository
import io.github.zrpc.data.SettingsRepository
import io.github.zrpc.discord.AssetResolver
import io.github.zrpc.discord.DiscordApi
import io.github.zrpc.discord.ImageHost
import io.github.zrpc.discord.PlayStoreIcons
import io.github.zrpc.rpc.PresenceComposer
import io.github.zrpc.rpc.RpcController
import io.github.zrpc.rpc.source.AppDetector
import io.github.zrpc.rpc.source.MediaWatcher
import io.github.zrpc.security.DiscordAuthInterceptor
import io.github.zrpc.security.KeystoreTokenCipher
import io.github.zrpc.security.NoAuthInterceptor
import io.github.zrpc.security.PrefsBlobStore
import io.github.zrpc.security.TokenVault
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI. Two strictly separated HTTP clients: one may carry the token, one never does. */
class AppContainer(context: Context) {
    val vault = TokenVault(KeystoreTokenCipher(), PrefsBlobStore(context))
    val settings = SettingsRepository(context)
    val configs = ConfigRepository(context)

    private val base = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Authenticated client: adds the token, refuses any non-Discord host, and
     * does NOT follow redirects (a redirect could otherwise bounce an
     * authenticated request to another host).
     */
    val discordClient: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(DiscordAuthInterceptor { vault.get() })
        .build()

    /** Everything else (image host, Play Store icons). Never carries the token. */
    val publicClient: OkHttpClient = base.newBuilder()
        .addInterceptor(NoAuthInterceptor { vault.cachedOrNull() })
        .build()

    /** WebSocket client for the gateway (token goes only inside IDENTIFY/RESUME). */
    val gatewayClient: OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .build()

    val api = DiscordApi(discordClient)
    val assetResolver = AssetResolver(api)
    val imageHost = ImageHost(publicClient, context)
    val playIcons = PlayStoreIcons(publicClient, context)
    val appDetector = AppDetector(context)
    val mediaWatcher = MediaWatcher(context)
    val composer = PresenceComposer(context, assetResolver, imageHost, playIcons, appDetector)
    val controller = RpcController(context)
}
