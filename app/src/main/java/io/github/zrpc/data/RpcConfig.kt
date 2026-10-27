package io.github.zrpc.data

import io.github.zrpc.discord.model.Activity
import io.github.zrpc.discord.model.ActivityMetadata
import io.github.zrpc.discord.model.ActivityType
import io.github.zrpc.discord.model.Assets
import io.github.zrpc.discord.model.Party
import io.github.zrpc.discord.model.Timestamps
import kotlinx.serialization.Serializable

/** A saved/editable custom presence. Never contains anything secret. */
@Serializable
data class RpcConfig(
    val name: String = "",
    val type: Int = ActivityType.PLAYING.code,
    val streamUrl: String = "",
    val details: String = "",
    val state: String = "",
    val largeImage: String = "",
    val largeText: String = "",
    val smallImage: String = "",
    val smallText: String = "",
    val button1Label: String = "",
    val button1Url: String = "",
    val button2Label: String = "",
    val button2Url: String = "",
    val timestamps: Boolean = false,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val partyCurrent: Int? = null,
    val partyMax: Int? = null,
    /** Overrides the global Application ID for this config. */
    val applicationId: String = "",
    val statusDisplay: Int? = null,
)

/** Images already resolved to something Discord accepts (asset id or mp: path). */
data class ResolvedImages(val large: String? = null, val small: String? = null)

/**
 * Pure mapping from config -> gateway Activity, applying Discord's limits so
 * an invalid field can't silently kill the whole presence.
 */
object ActivityBuilder {
    private const val TEXT_MAX = 128
    private const val BUTTON_LABEL_MAX = 32
    private const val URL_MAX = 512

    fun build(
        config: RpcConfig,
        images: ResolvedImages,
        applicationId: String?,
        now: Long = System.currentTimeMillis(),
    ): Activity {
        val buttons = listOf(config.button1Label to config.button1Url, config.button2Label to config.button2Url)
            .map { (l, u) -> l.trim().take(BUTTON_LABEL_MAX) to u.trim() }
            .filter { (l, u) -> l.isNotEmpty() && isWebUrl(u) && u.length <= URL_MAX }
        val type = ActivityType.fromCode(config.type)
        val stream = config.streamUrl.trim().takeIf { type == ActivityType.STREAMING && isWebUrl(it) }

        val assets = Assets(
            largeImage = images.large,
            largeText = field(config.largeText).takeIf { images.large != null },
            smallImage = images.small,
            smallText = field(config.smallText).takeIf { images.small != null },
        ).takeIf { it.largeImage != null || it.smallImage != null }

        val timestamps = if (config.timestamps) {
            Timestamps(start = config.startMs ?: now, end = config.endMs?.takeIf { it > (config.startMs ?: now) })
        } else null

        val party = if (config.partyCurrent != null && config.partyMax != null &&
            config.partyCurrent in 1..config.partyMax
        ) Party(id = "zrpc", size = listOf(config.partyCurrent, config.partyMax)) else null

        return Activity(
            name = config.name.trim().take(TEXT_MAX).ifEmpty { "Zrpc" },
            type = if (type == ActivityType.STREAMING && stream == null) ActivityType.PLAYING.code else type.code,
            url = stream,
            applicationId = applicationId?.trim()?.takeIf { it.matches(SNOWFLAKE) },
            details = field(config.details),
            state = field(config.state),
            timestamps = timestamps,
            assets = assets,
            party = party,
            buttons = buttons.map { it.first }.takeIf { it.isNotEmpty() },
            metadata = buttons.map { it.second }.takeIf { it.isNotEmpty() }?.let { ActivityMetadata(it) },
            statusDisplayType = config.statusDisplay,
        )
    }

    /** Discord rejects 1-char details/state; pad with a blank braille char. */
    fun field(raw: String): String? {
        val t = raw.trim().take(TEXT_MAX)
        return when (t.length) {
            0 -> null
            1 -> t + "⠀"
            else -> t
        }
    }

    fun isWebUrl(s: String) = s.startsWith("https://") || s.startsWith("http://")

    val SNOWFLAKE = Regex("""^\d{15,21}$""")
}
