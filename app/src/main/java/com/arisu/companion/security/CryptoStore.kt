package com.arisu.companion.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the symmetric key used by [CryptoStore]. On a device this is backed by
 * the Android Keystore (key material never leaves secure hardware/services).
 * Tests substitute [FakeVault].
 */
interface SecretVault {
    fun getOrCreateSecret(alias: String): SecretKey
}

class KeystoreVault : SecretVault {
    override fun getOrCreateSecret(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

class FakeVault(private val secret: SecretKey) : SecretVault {
    override fun getOrCreateSecret(alias: String): SecretKey = secret
}

/**
 * AES-256/GCM encryption for locally stored secrets (API keys).
 *
 * Wire format: base64( 12-byte IV || ciphertext || 16-byte tag ).
 * Keys are never logged, never crash-reported, and never exported with chat backups.
 */
class CryptoStore(private val vault: SecretVault) {

    private val secretAlias = "arisu_master_key"

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, vault.getOrCreateSecret(secretAlias))
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + cipherText.size)
        iv.copyInto(payload, 0)
        cipherText.copyInto(payload, iv.size)
        return java.util.Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(encoded: String): String {
        val payload = java.util.Base64.getDecoder().decode(encoded)
        require(payload.size > IV_LENGTH) { "Corrupt encrypted value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            vault.getOrCreateSecret(secretAlias),
            GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH),
        )
        val plain = cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH)
        return String(plain, Charsets.UTF_8)
    }

    fun encryptOrNull(plain: String): String? = try {
        encrypt(plain)
    } catch (_: Exception) {
        null
    }

    fun decryptOrNull(encoded: String): String? = try {
        if (encoded.isBlank()) null else decrypt(encoded)
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
