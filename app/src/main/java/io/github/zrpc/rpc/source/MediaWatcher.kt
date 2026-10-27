package io.github.zrpc.rpc.source

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import io.github.zrpc.log.AppLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Required by Android to read media sessions. It does not read your notifications' content. */
class MediaListenerService : NotificationListenerService()

data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val art: Bitmap?,
) {
    /** Identity used to avoid re-sending identical presences. */
    val key: String get() = "$packageName|$title|$artist|$album|$isPlaying|${positionMs / 5000}"
}

class MediaWatcher(private val context: Context) {
    private val component = ComponentName(context, MediaListenerService::class.java)

    fun hasPermission(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun media(): Flow<MediaInfo?> = callbackFlow {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

        fun pick(): MediaInfo? {
            val controllers = callbacks.keys.toList()
            val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()
            return active?.let(::toInfo)
        }

        fun emitCurrent() { trySend(pick()) }

        fun track(controllers: List<MediaController>?) {
            callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
            callbacks.clear()
            controllers.orEmpty().forEach { c ->
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = emitCurrent()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = emitCurrent()
                    override fun onSessionDestroyed() = emitCurrent()
                }
                c.registerCallback(cb, handler)
                callbacks[c] = cb
            }
            emitCurrent()
        }

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { track(it) }
        try {
            msm.addOnActiveSessionsChangedListener(listener, component, handler)
            track(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            AppLog.w("Media", "Notification access not granted")
            trySend(null)
        }
        awaitClose {
            runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
            callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        }
    }.distinctUntilChanged { a, b -> a?.key == b?.key }

    private fun toInfo(c: MediaController): MediaInfo? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val state = c.playbackState
        val pos = state?.let {
            if (it.state == PlaybackState.STATE_PLAYING && it.lastPositionUpdateTime > 0) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime
                it.position + (elapsed * it.playbackSpeed).toLong()
            } else it.position
        } ?: 0L
        return MediaInfo(
            packageName = c.packageName,
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = pos.coerceAtLeast(0),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART),
        )
    }
}
