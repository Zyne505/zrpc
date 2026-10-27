package io.github.zrpc.security

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Network-level rules that keep the token on Discord's own servers.
 *
 * The token is ONLY ever attached to HTTPS requests to Discord hosts.
 * Every other request (image hosts, Play Store icon lookups, avatars) goes
 * through a client that strips Authorization and refuses to send it.
 */
object DiscordHosts {
    private val exact = setOf("discord.com", "discordapp.com", "gateway.discord.gg")
    private val suffixes = listOf(".discord.com", ".discord.gg", ".discordapp.com")

    fun isTrusted(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h in exact || suffixes.any { h.endsWith(it) }
    }

    fun isTrusted(url: HttpUrl): Boolean = url.isHttps && isTrusted(url.host)

    /** Gateway URLs (including resume_gateway_url sent by the server) must be wss + Discord. */
    fun isTrustedGateway(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("wss", ignoreCase = true) && u.host != null &&
            (u.host.lowercase().endsWith(".discord.gg") || u.host.lowercase() == "discord.gg")
    }
}

class TokenLeakBlockedException(msg: String) : IOException(msg)

/**
 * Adds the token to requests for Discord hosts, and blocks any request that
 * would carry it anywhere else (defence against a future bug or a malicious
 * redirect). Redirects are disabled on this client, see AppContainer.
 */
class DiscordAuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!DiscordHosts.isTrusted(req.url)) {
            throw TokenLeakBlockedException("Blocked authenticated request to untrusted host ${req.url.host}")
        }
        // A request may already carry a candidate token (login verification).
        if (req.header("Authorization") != null) return chain.proceed(req)
        val token = tokenProvider() ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", token).build())
    }
}

/** For every non-Discord client: never let an Authorization header or the token out. */
class NoAuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder().removeHeader("Authorization").build()
        val token = tokenProvider()
        if (token != null && req.url.toString().contains(token)) {
            throw TokenLeakBlockedException("Blocked request containing token in URL")
        }
        return chain.proceed(req)
    }
}
