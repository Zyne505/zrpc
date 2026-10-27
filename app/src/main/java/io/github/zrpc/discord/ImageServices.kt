package io.github.zrpc.discord

import android.content.Context
import io.github.zrpc.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/**
 * Anonymous public image host for gallery images, app icons and album art.
 * Uses the token-free client: the image host never sees anything about your
 * Discord account. Uploaded images are PUBLIC, the UI says so.
 */
class ImageHost(private val client: OkHttpClient, private val context: Context) {
    private val cache = context.getSharedPreferences("image_host_cache", Context.MODE_PRIVATE)

    suspend fun upload(bytes: ByteArray, mime: String = "image/png", fileName: String = "image.png"): String? {
        val key = sha256(bytes)
        cache.getString(key, null)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("reqtype", "fileupload")
                    .addFormDataPart("fileToUpload", fileName, bytes.toRequestBody(mime.toMediaType()))
                    .build()
                val req = Request.Builder().url("https://catbox.moe/user/api.php").post(body).build()
                val url = client.newCall(req).await().bodyOrThrow().trim()
                require(url.startsWith("https://")) { "unexpected upload response" }
                cache.edit().putString(key, url).apply()
                url
            }.onFailure { AppLog.w("ImageHost", "Upload failed", it) }.getOrNull()
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

/** Finds an app's public icon URL from its Play Store page (no token involved, cached). */
class PlayStoreIcons(private val client: OkHttpClient, context: Context) {
    private val cache = context.getSharedPreferences("playstore_icon_cache", Context.MODE_PRIVATE)
    private val ogImage = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""")

    suspend fun iconUrl(packageName: String): String? {
        cache.getString(packageName, null)?.let { return it.ifEmpty { null } }
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://play.google.com/store/apps/details?id=$packageName&hl=en")
                    .build()
                val html = client.newCall(req).await().bodyOrThrow()
                ogImage.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            }.getOrNull().also { cache.edit().putString(packageName, it ?: "").apply() }
        }
    }
}
