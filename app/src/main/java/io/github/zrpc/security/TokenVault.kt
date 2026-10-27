package io.github.zrpc.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

/** Where the encrypted blob lives. Plain interface so tests can use memory. */
interface BlobStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

class PrefsBlobStore(context: Context) : BlobStore {
    // MODE_PRIVATE + excluded from every backup path (see data_extraction_rules.xml).
    private val prefs = context.getSharedPreferences("token_vault", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(value: String) { prefs.edit().putString(KEY, value).commit() }
    override fun clear() { prefs.edit().remove(KEY).commit() }
    private companion object { const val KEY = "blob_v1" }
}

/**
 * The only place the Discord token is stored.
 *
 * - On disk it is only ever AES-256-GCM ciphertext under a Keystore key.
 * - Plaintext exists in memory only while the app runs; [wipe] clears it,
 *   deletes the ciphertext AND destroys the key, so even a leftover copy of
 *   the old prefs file is useless.
 * - If decryption fails (key invalidated, tampered blob) the vault wipes
 *   itself instead of retrying, and you simply log in again.
 */
class TokenVault(
    private val cipher: TokenCipher,
    private val store: BlobStore,
) {
    @Volatile private var cached: String? = null
    private val _hasToken = MutableStateFlow(store.read() != null)
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    fun save(rawToken: String) {
        val token = TokenFormat.normalize(rawToken)
        require(TokenFormat.isPlausible(token)) { "That doesn't look like a Discord token" }
        val bytes = token.toByteArray(Charsets.UTF_8)
        try {
            val blob = cipher.encrypt(bytes)
            store.write(Base64.getEncoder().encodeToString(blob))
        } finally {
            bytes.fill(0)
        }
        cached = token
        _hasToken.value = true
    }

    /** Returns the token or null. Never logs, never throws. */
    fun get(): String? {
        cached?.let { return it }
        val encoded = store.read() ?: return null
        return try {
            val plain = cipher.decrypt(Base64.getDecoder().decode(encoded))
            val token = String(plain, Charsets.UTF_8)
            plain.fill(0)
            cached = token
            token
        } catch (_: Exception) {
            // Key was invalidated or blob tampered with: fail closed.
            wipe()
            null
        }
    }

    /** Peek used by the redactor; doesn't trigger a decrypt. */
    fun cachedOrNull(): String? = cached

    fun wipe() {
        cached = null
        store.clear()
        cipher.destroyKey()
        _hasToken.value = false
    }

    fun describeProtection(): String = cipher.describe()
}
