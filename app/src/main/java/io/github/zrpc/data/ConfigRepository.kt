package io.github.zrpc.data

import android.content.Context
import io.github.zrpc.discord.ZrpcJson
import io.github.zrpc.security.TokenFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class Preset(val title: String, val config: RpcConfig)

/** Saved custom presence configs (one JSON file each) and bundled presets. */
class ConfigRepository(private val context: Context) {
    private val dir = File(context.filesDir, "configs").apply { mkdirs() }
    private val _saved = MutableStateFlow(loadAll())
    val saved: StateFlow<List<Pair<String, RpcConfig>>> = _saved.asStateFlow()

    private fun loadAll(): List<Pair<String, RpcConfig>> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.nameWithoutExtension.lowercase() }
            .mapNotNull { f -> runCatching { f.nameWithoutExtension to ZrpcJson.decodeFromString(RpcConfig.serializer(), f.readText()) }.getOrNull() }

    suspend fun save(title: String, config: RpcConfig) = withContext(Dispatchers.IO) {
        val safe = title.trim().replace(Regex("""[^\w\- ]"""), "_").take(48).ifEmpty { "config" }
        File(dir, "$safe.json").writeText(encode(config))
        _saved.value = loadAll()
    }

    suspend fun delete(title: String) = withContext(Dispatchers.IO) {
        File(dir, "$title.json").delete()
        _saved.value = loadAll()
    }

    fun encode(config: RpcConfig): String = ZrpcJson.encodeToString(RpcConfig.serializer(), config)

    /**
     * Parses an imported config. Rejects files that contain anything token-shaped:
     * a shared "config" is a classic way to trick people into pasting tokens around.
     */
    fun decodeImport(text: String): RpcConfig {
        require(!TokenFormat.EMBEDDED.containsMatchIn(text)) { "This file contains something that looks like a Discord token. Not imported." }
        return ZrpcJson.decodeFromString(RpcConfig.serializer(), text)
    }

    fun presets(): List<Preset> = runCatching {
        val text = context.assets.open("presets.json").bufferedReader().use { it.readText() }
        ZrpcJson.decodeFromString(ListSerializer(Preset.serializer()), text)
    }.getOrDefault(emptyList())
}
