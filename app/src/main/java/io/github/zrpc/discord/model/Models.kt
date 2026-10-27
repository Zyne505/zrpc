package io.github.zrpc.discord.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ActivityType(val code: Int, val label: String) {
    PLAYING(0, "Playing"),
    STREAMING(1, "Streaming"),
    LISTENING(2, "Listening to"),
    WATCHING(3, "Watching"),
    COMPETING(5, "Competing in");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: PLAYING
    }
}

enum class UserStatus(val wire: String, val label: String) {
    ONLINE("online", "Online"),
    IDLE("idle", "Idle"),
    DND("dnd", "Do Not Disturb"),
    INVISIBLE("invisible", "Invisible");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: ONLINE
    }
}

/** What the member list shows next to your name (Discord `status_display_type`). */
enum class StatusDisplay(val code: Int?, val label: String) {
    DEFAULT(null, "Activity name"),
    STATE(1, "State line"),
    DETAILS(2, "Details line");

    companion object {
        fun fromCode(code: Int?) = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

@Serializable
data class Timestamps(val start: Long? = null, val end: Long? = null)

@Serializable
data class Assets(
    @SerialName("large_image") val largeImage: String? = null,
    @SerialName("large_text") val largeText: String? = null,
    @SerialName("small_image") val smallImage: String? = null,
    @SerialName("small_text") val smallText: String? = null,
)

@Serializable
data class Party(val id: String? = null, val size: List<Int>? = null)

@Serializable
data class ActivityMetadata(@SerialName("button_urls") val buttonUrls: List<String>)

/** Gateway activity as sent by a user client (buttons are labels + metadata URLs). */
@Serializable
data class Activity(
    val name: String,
    val type: Int = 0,
    val url: String? = null,
    @SerialName("application_id") val applicationId: String? = null,
    val details: String? = null,
    val state: String? = null,
    val timestamps: Timestamps? = null,
    val assets: Assets? = null,
    val party: Party? = null,
    val buttons: List<String>? = null,
    val metadata: ActivityMetadata? = null,
    @SerialName("status_display_type") val statusDisplayType: Int? = null,
)

@Serializable
data class Presence(
    val since: Long? = null,
    val activities: List<Activity> = emptyList(),
    val status: String = "online",
    val afk: Boolean = false,
)

@Serializable
data class DiscordUser(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null,
    val avatar: String? = null,
    val discriminator: String? = null,
    val banner: String? = null,
) {
    val displayName: String get() = globalName ?: username

    fun avatarUrl(size: Int = 256): String = if (avatar != null) {
        val ext = if (avatar.startsWith("a_")) "gif" else "png"
        "https://cdn.discordapp.com/avatars/$id/$avatar.$ext?size=$size"
    } else {
        val index = (id.toLongOrNull() ?: 0L).shr(22) % 6
        "https://cdn.discordapp.com/embed/avatars/$index.png"
    }
}

@Serializable
data class ExternalAsset(
    val url: String,
    @SerialName("external_asset_path") val externalAssetPath: String,
)

@Serializable
data class ApplicationAsset(val id: String, val name: String, val type: Int = 1)

@Serializable
data class ApplicationInfo(val id: String, val name: String, val icon: String? = null)
