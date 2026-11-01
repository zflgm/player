package dev.anilbeesetti.nextplayer.core.data.security

/**
 * Encrypts secret strings (network passwords, key passphrases) before they are persisted.
 *
 * Implementations must tolerate values written before encryption existed: [decrypt] returns a
 * payload it cannot recognize unchanged, so existing plaintext rows keep working and get
 * re-encrypted the next time the row is saved.
 */
interface SecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
