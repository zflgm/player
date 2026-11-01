package com.zflgm.player.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.koin.core.annotation.Single

/**
 * AES-GCM encryption backed by the Android Keystore. The key is generated on first use and never
 * leaves the secure hardware, so a stolen database or backup yields only ciphertext.
 *
 * Wire format: `enc:v1:<base64 iv>:<base64 ciphertext>`. Anything else — including plaintext
 * rows written before this class existed — is returned unchanged by [decrypt] and replaced with
 * ciphertext on the next save.
 */
@Single
class KeystoreSecretCipher : SecretCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return plainText
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return buildString {
            append(PREFIX)
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    override fun decrypt(cipherText: String): String {
        if (!cipherText.startsWith(PREFIX)) return cipherText
        val parts = cipherText.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return cipherText
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key loss (e.g. restored backup on a new device) cannot be recovered by design.
            // Fail closed to an empty secret rather than crash the whole connection list.
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "player_secret_cipher"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "enc:v1:"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
    }
}
