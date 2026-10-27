package io.github.zrpc.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import javax.crypto.SecretKeyFactory
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts/decrypts small secrets. Split out so the vault can be unit-tested on the JVM. */
interface TokenCipher {
    /** Returns iv || ciphertext+tag. */
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
    /** Destroys the key so any stored ciphertext becomes unreadable. */
    fun destroyKey()
    /** Human-readable description of where the key lives, for the profile screen. */
    fun describe(): String
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore.
 * The raw key never exists in app memory or on disk; StrongBox (a separate
 * secure chip) is used when the device has one.
 */
class KeystoreTokenCipher(private val alias: String = "zrpc_token_key_v1") : TokenCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    @Volatile private var strongBox: Boolean = false

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return generate(preferStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generate(preferStrongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return try {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(builder.build())
            gen.generateKey().also { strongBox = preferStrongBox }
        } catch (e: Exception) {
            if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException || e is java.security.ProviderException)
            ) {
                generate(preferStrongBox = false)
            } else throw e
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        require(ivLen in 12..16 && blob.size > 1 + ivLen) { "corrupt vault blob" }
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    override fun destroyKey() {
        runCatching { keyStore.deleteEntry(alias) }
    }

    @Suppress("DEPRECATION")
    override fun describe(): String = runCatching {
        val k = key()
        val factory = SecretKeyFactory.getInstance(k.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(k, KeyInfo::class.java) as KeyInfo
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox chip"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "hardware TEE"
                else -> "software"
            }
        } else if (info.isInsideSecureHardware) "hardware" else "software"
        "AES-256-GCM · Android Keystore ($level)"
    }.getOrDefault(if (strongBox) "AES-256-GCM · Android Keystore (StrongBox)" else "AES-256-GCM · Android Keystore")

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
