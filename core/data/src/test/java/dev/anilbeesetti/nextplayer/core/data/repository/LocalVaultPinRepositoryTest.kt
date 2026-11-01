package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalVaultPinRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val repository = LocalVaultPinRepository(context)

    @Test
    fun `biometrics default to disabled for existing vaults`() = runTest {
        context.getSharedPreferences("vault_security_prefs", Context.MODE_PRIVATE).edit {
            putString("vault_pin_hash", "existing hash")
        }

        assertTrue(repository.hasPinSet())
        assertFalse(repository.isBiometricEnabled())
    }

    @Test
    fun `biometric choice persists across repository instances`() = runTest {
        repository.setPin("1234")
        repository.setBiometricEnabled(true)
        val reopened = LocalVaultPinRepository(context)
        assertTrue(reopened.isBiometricEnabled())

        reopened.setBiometricEnabled(false)

        assertFalse(LocalVaultPinRepository(context).isBiometricEnabled())
        assertTrue(repository.verifyPin("1234"))
    }

    @Test
    fun `setting a new PIN requires a new biometric opt in`() = runTest {
        repository.setPin("1234")
        assertFalse(repository.isBiometricEnabled())
        repository.setBiometricEnabled(true)

        repository.setPin("5678")

        assertFalse(repository.isBiometricEnabled())
        assertTrue(repository.verifyPin("5678"))
    }

    @Test
    fun `wrong pin is rejected`() = runTest {
        repository.setPin("1234")
        assertFalse(repository.verifyPin("0000"))
        assertFalse(repository.verifyPin(""))
        assertTrue(repository.verifyPin("1234"))
    }

    @Test
    fun `new pins are stored as pbkdf2 not raw sha256`() = runTest {
        repository.setPin("1234")
        val stored = context.getSharedPreferences("vault_security_prefs", Context.MODE_PRIVATE)
            .getString("vault_pin_hash", null)!!
        assertTrue(stored.startsWith("pbkdf2:"))
    }

    @Test
    fun `legacy sha256 pins still verify and upgrade in place`() = runTest {
        val salt = "deadbeef"
        val legacyHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((salt + "1234").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("vault_security_prefs", Context.MODE_PRIVATE).edit {
            putString("vault_pin_hash", legacyHash)
            putString("vault_pin_salt", salt)
        }

        assertTrue(repository.verifyPin("1234"))
        assertFalse(repository.verifyPin("0000"))

        val upgraded = context.getSharedPreferences("vault_security_prefs", Context.MODE_PRIVATE)
            .getString("vault_pin_hash", null)!!
        assertTrue(upgraded.startsWith("pbkdf2:"))
        assertTrue(LocalVaultPinRepository(context).verifyPin("1234"))
    }
}
