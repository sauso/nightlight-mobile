package com.sauso.nightlight

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android half of [SessionVault]: AES-256-GCM with a key that lives in the Android Keystore and never leaves it.
 *
 * - The key needs NO user authentication (the app must restore a sign-in without a prompt), so the "key permanently
 *   invalidated by a new lock screen" case does not apply; a key that is gone or unusable is still handled ([SessionVault.KeyUnusable]).
 * - The IV is chosen by the Keystore when encrypting and read back from the cipher: a Keystore key REFUSES a caller-supplied
 *   IV, so a cipher that took one here would work in a JVM test and fail on every phone (review of phase D).
 * - The saved address goes in as additional authenticated data, so a blob cannot be moved to another address's slot.
 *
 * Glue over java.security, with no JVM test (it needs the Android Keystore): the phone check "sign in at home, restart on
 * Tailscale, already signed in" is its proof, and the PR says so. The logic around it (SessionVault) is tested with a
 * software cipher behind the same interface.
 */
class KeystoreSessionCipher : SessionVault.SessionCipher {

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "nightlight_session_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(create: Boolean): SecretKey {
        val ks = try { keyStore() } catch (e: Exception) { throw SessionVault.KeyTransient(e) }
        val existing = try {
            ks.getKey(ALIAS, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            throw SessionVault.KeyUnusable(e)
        } catch (e: KeyStoreException) {
            throw SessionVault.KeyTransient(e)
        } catch (e: ProviderException) {
            throw SessionVault.KeyTransient(e)
        }
        if (existing != null) return existing
        if (!create) throw SessionVault.KeyUnusable() // the key a stored blob was sealed with is gone
        return try {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        } catch (e: ProviderException) {
            throw SessionVault.KeyTransient(e)
        } catch (e: Exception) {
            throw SessionVault.KeyTransient(e)
        }
    }

    override fun seal(plain: ByteArray, aad: ByteArray): SessionVault.Sealed {
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true)) // the IV is generated here, by the Keystore
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plain)
            return SessionVault.Sealed(cipher.iv, ciphertext)
        } catch (e: SessionVault.KeyUnusable) {
            throw e
        } catch (e: SessionVault.KeyTransient) {
            throw e
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SessionVault.KeyUnusable(e)
        } catch (e: InvalidKeyException) {
            throw SessionVault.KeyUnusable(e)
        } catch (e: ProviderException) {
            throw SessionVault.KeyTransient(e)
        }
    }

    override fun open(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        } catch (e: SessionVault.KeyUnusable) {
            throw e
        } catch (e: SessionVault.KeyTransient) {
            throw e
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SessionVault.KeyUnusable(e)
        } catch (e: InvalidKeyException) {
            throw SessionVault.KeyUnusable(e)
        } catch (e: ProviderException) {
            throw SessionVault.KeyTransient(e)
        }
        // Any other failure (a bad tag, a truncated blob) propagates as it is: SessionVault deletes an entry it cannot read.
    }

    override fun resetKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nothing more can be done; the next seal() fails and the caller reports it.
        }
    }
}

/** [SessionVault.KeyValueStore] over a SharedPreferences file. */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : SessionVault.KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
