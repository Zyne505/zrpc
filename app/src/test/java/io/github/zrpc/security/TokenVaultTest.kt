package io.github.zrpc.security

import io.github.zrpc.TestTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same AES-GCM scheme as the Keystore cipher, with a JVM key, for tests. */
private class JvmCipher : TokenCipher {
    var key: SecretKey? = newKey()
    var destroyed = 0
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key ?: newKey().also { key = it }, GCMParameterSpec(128, iv))
        return byteArrayOf(12) + iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(1, 13)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key ?: newKey().also { key = it }, GCMParameterSpec(128, iv))
        return c.doFinal(blob.copyOfRange(13, blob.size))
    }

    override fun destroyKey() { key = null; destroyed++ }
    override fun describe() = "test"
}

private class MemStore : BlobStore {
    var value: String? = null
    override fun read() = value
    override fun write(value: String) { this.value = value }
    override fun clear() { value = null }
}

class TokenVaultTest {
    @Test fun `round trips and never stores plaintext`() {
        val store = MemStore()
        val vault = TokenVault(JvmCipher(), store)
        vault.save(TestTokens.STANDARD)
        val stored = store.value!!
        assertFalse(stored.contains(TestTokens.STANDARD))
        assertFalse(String(Base64.getDecoder().decode(stored), Charsets.ISO_8859_1).contains(TestTokens.STANDARD))
        // A fresh vault instance (app restart) decrypts from disk.
        val cipher = JvmCipher()
        val v1 = TokenVault(cipher, store)
        assertNull("different key must not decrypt", v1.get())
        assertTrue("failed decrypt wipes the vault", store.value == null && cipher.destroyed == 1)
    }

    @Test fun `same key decrypts after restart`() {
        val store = MemStore()
        val cipher = JvmCipher()
        TokenVault(cipher, store).save(TestTokens.STANDARD)
        assertEquals(TestTokens.STANDARD, TokenVault(cipher, store).get())
    }

    @Test fun `tampered ciphertext fails closed`() {
        val store = MemStore()
        val cipher = JvmCipher()
        TokenVault(cipher, store).save(TestTokens.STANDARD)
        val bytes = Base64.getDecoder().decode(store.value)
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        store.value = Base64.getEncoder().encodeToString(bytes)
        val fresh = TokenVault(cipher, store)
        assertNull(fresh.get())
        assertFalse(fresh.hasToken.value)
    }

    @Test fun `wipe removes blob, cache and key`() {
        val store = MemStore()
        val cipher = JvmCipher()
        val vault = TokenVault(cipher, store)
        vault.save(TestTokens.STANDARD)
        assertNotNull(vault.cachedOrNull())
        vault.wipe()
        assertNull(store.value)
        assertNull(vault.cachedOrNull())
        assertNull(cipher.key)
        assertFalse(vault.hasToken.value)
    }

    @Test fun `rejects implausible tokens`() {
        val vault = TokenVault(JvmCipher(), MemStore())
        assertThrows(IllegalArgumentException::class.java) { vault.save("not a token") }
        assertFalse(vault.hasToken.value)
    }

    @Test fun `normalizes pasted token`() {
        val store = MemStore()
        val cipher = JvmCipher()
        TokenVault(cipher, store).save("  \"${TestTokens.STANDARD}\"  ")
        assertEquals(TestTokens.STANDARD, TokenVault(cipher, store).get())
    }
}
