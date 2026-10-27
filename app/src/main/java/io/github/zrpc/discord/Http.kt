package io.github.zrpc.discord

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val ZrpcJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

class HttpException(val code: Int, message: String) : IOException(message)

/** Suspends on an OkHttp call; cancelling the coroutine cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Reads the body or throws with the status (body text is never included: it may echo input). */
fun Response.bodyOrThrow(): String = use {
    if (!it.isSuccessful) throw HttpException(it.code, "HTTP ${it.code} from ${it.request.url.host}")
    it.body?.string().orEmpty()
}
