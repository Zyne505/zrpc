package io.github.zrpc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zrpc.discord.model.Activity
import io.github.zrpc.discord.model.ActivityType
import io.github.zrpc.discord.model.DiscordUser
import kotlinx.coroutines.delay

/** Turns a resolved asset (mp:external/..., app asset id, URL, content://) into something Coil can load. */
fun assetModel(asset: String?, applicationId: String?): Any? {
    val a = asset?.trim().orEmpty()
    return when {
        a.isEmpty() -> null
        a.startsWith("mp:external/") -> "https://media.discordapp.net/" + a.removePrefix("mp:")
        a.startsWith("mp:") -> "https://media.discordapp.net/" + a.removePrefix("mp:")
        a.startsWith("http") || a.startsWith("content://") -> a
        a.all { it.isDigit() } && !applicationId.isNullOrBlank() -> "https://cdn.discordapp.com/app-assets/$applicationId/$a.png"
        else -> null
    }
}

/** A Discord-style profile card showing how the presence will look. */
@Composable
fun PresencePreview(
    activity: Activity?,
    user: DiscordUser?,
    largeModel: Any? = assetModel(activity?.assets?.largeImage, activity?.applicationId),
    smallModel: Any? = assetModel(activity?.assets?.smallImage, activity?.applicationId),
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = user?.avatarUrl(128),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(user?.displayName ?: "You", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(user?.username ?: "preview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (activity == null) {
                Text("No activity", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val type = ActivityType.fromCode(activity.type)
            Text(
                type.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp)) {
                    if (largeModel != null) {
                        AsyncImage(
                            model = largeModel,
                            contentDescription = activity.assets?.largeText,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.SportsEsports, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                    if (smallModel != null) {
                        AsyncImage(
                            model = smallModel,
                            contentDescription = activity.assets?.smallText,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(26.dp)
                                .align(Alignment.BottomEnd)
                                .offset(4.dp, 4.dp)
                                .clip(CircleShape)
                                .border(3.dp, MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(activity.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    activity.details?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    activity.state?.let { s ->
                        val party = activity.party?.size?.takeIf { it.size == 2 }?.let { " (${it[0]} of ${it[1]})" }.orEmpty()
                        Text(s + party, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    activity.timestamps?.let { ts -> ElapsedText(ts.start, ts.end) }
                }
            }
            activity.buttons?.forEach { label ->
                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = true) { Text(label) }
            }
        }
    }
}

@Composable
private fun ElapsedText(start: Long?, end: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val text = when {
        end != null -> "${fmt((end - now).coerceAtLeast(0))} left"
        start != null -> "${fmt((now - start).coerceAtLeast(0))} elapsed"
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
