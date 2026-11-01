package com.zflgm.player.core.data.repository

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single
class LocalVaultPinRepository(
    private val context: Context,
) : VaultPinRepository {

    private val preferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun hasPinSet(): Boolean = withContext(Dispatchers.IO) {
        preferences.contains(KEY_PIN_HASH)
    }

    override suspend fun setPin(pin: String) = withContext(Dispatchers.IO) {
        val salt = generateSalt()
        preferences.edit {
            putString(KEY_PIN_HASH, hashPin(pin, salt))
            putString(KEY_SALT, salt)
            putBoolean(KEY_BIOMETRIC_ENABLED, false)
        }
    }

    override suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val storedHash = preferences.getString(KEY_PIN_HASH, null) ?: return@withContext false
        val salt = preferences.getString(KEY_SALT, null) ?: return@withContext false
        val matches = if (storedHash.startsWith(PBKDF2_PREFIX)) {
            constantTimeEquals(hashPin(pin, salt), storedHash)
        } else {
            // Legacy single-round SHA-256 record. Accept it once, then upgrade to PBKDF2.
            constantTimeEquals(legacyHashPin(pin, salt), storedHash)
        }
        if (matches && !storedHash.startsWith(PBKDF2_PREFIX)) {
            preferences.edit { putString(KEY_PIN_HASH, hashPin(pin, salt)) }
        }
        matches
    }

    override suspend fun hasShownHideConfirmation(): Boolean = withContext(Dispatchers.IO) {
        preferences.getBoolean(KEY_HIDE_CONFIRMATION_SHOWN, false)
    }

    override suspend fun isBiometricEnabled(): Boolean = withContext(Dispatchers.IO) {
        preferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        preferences.edit { putBoolean(KEY_BIOMETRIC_ENABLED, enabled) }
    }

    override suspend fun setHideConfirmationShown() = withContext(Dispatchers.IO) {
        preferences.edit {
            putBoolean(KEY_HIDE_CONFIRMATION_SHOWN, true)
        }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * PBKDF2-HMAC-SHA256 with a high iteration count. A numeric PIN has a tiny keyspace, so the
     * slow KDF is what makes offline brute force from a stolen prefs file impractical.
     */
    private fun hashPin(pin: String, salt: String): String {
        val spec = PBEKeySpec(
            pin.toCharArray(),
            salt.hexToBytes(),
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_BITS,
        )
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = keyFactory.generateSecret(spec).encoded
        spec.clearPassword()
        return PBKDF2_PREFIX + hash.joinToString("") { "%02x".format(it) }
    }

    /** Pre-PBKDF2 format kept only so existing installs can verify and upgrade in place. */
    private fun legacyHashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        private const val PREFS_NAME = "vault_security_prefs"
        private const val KEY_PIN_HASH = "vault_pin_hash"
        private const val KEY_SALT = "vault_pin_salt"
        private const val KEY_BIOMETRIC_ENABLED = "vault_biometric_enabled"
        private const val KEY_HIDE_CONFIRMATION_SHOWN = "vault_hide_confirmation_shown"
        private const val PBKDF2_PREFIX = "pbkdf2:"
        private const val PBKDF2_ITERATIONS = 200_000
        private const val PBKDF2_KEY_BITS = 256
    }
}
