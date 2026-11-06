package com.sauso.nightlight

import com.sauso.nightlight.SessionRules.Bindings
import com.sauso.nightlight.SessionRules.Entry
import com.sauso.nightlight.SessionVault.KeyTransient
import com.sauso.nightlight.SessionVault.KeyUnusable
import com.sauso.nightlight.SessionVault.Sealed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault's logic against a SOFTWARE AES-GCM key behind the same interface the Android Keystore sits behind. What it pins:
 * the entry round trips, cannot be moved to another address's slot, is deleted when it cannot be read, is replaced and not
 * duplicated, is removed by a clear that needs no network, and is removed on a stale token only when the token matches. What it
 * cannot pin (the Keystore itself, the thread the plugin runs it on) is in the PR's phone check, not here.
 *
 * The software cipher generates its OWN iv in seal(), exactly as a Keystore key forces, and its seal() has no way to be handed
 * one: a cipher that accepted a caller's iv would pass here and fail on every phone.
 */
class SessionVaultTest {

    private val saved = "http://192.168.1.50:4000"
    private val other = "http://192.168.1.51:4000"
    private val ts = "https://nightlight.example.ts.net"
    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val token = "aaa.bbb.ccc"
    private val bindings = Bindings(saved, ts, id)

    private class MapStore : SessionVault.KeyValueStore {
        val map = linkedMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** AES-256-GCM in software, with the Keystore's one rule: the iv is made inside seal(). Failures can be scripted. */
    private class SoftwareCipher : SessionVault.SessionCipher {
        var key: SecretKey = newKey()
        var resets = 0
        var sealFailures = ArrayDeque<Exception>()
        var openFailure: Exception? = null
        var sealCalls = 0

        private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun seal(plain: ByteArray, aad: ByteArray): Sealed {
            sealCalls++
            sealFailures.removeFirstOrNull()?.let { throw it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return Sealed(iv, cipher.doFinal(plain))
        }

        override fun open(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            openFailure?.let { throw it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }

        override fun resetKey() {
            resets++
            key = newKey() // the old entries are now undecryptable, as after a real key loss
        }
    }

    private val store = MapStore()
    private val cipher = SoftwareCipher()
    private val vault = SessionVault(store, cipher)

    private fun entry(address: String = saved, t: String = token) = Entry(t, Bindings(address, ts, id))

    @Test
    fun `an entry round trips with everything it was stamped with`() {
        assertTrue(vault.save(entry()))
        assertEquals(entry(), vault.load(saved))
    }

    @Test
    fun `an entry saved with no Tailscale binding round trips as it was, with the nulls`() {
        val bare = Entry(token, Bindings(saved, null, null))
        assertTrue(vault.save(bare))
        assertEquals(bare, vault.load(saved))
    }

    @Test
    fun `the plain text of the token is not in what is stored`() {
        vault.save(entry(t = "secretpart1.secretpart2.secretpart3"))
        assertFalse(store.map.values.any { it.contains("secretpart") })
    }

    @Test
    fun `saving the same token twice stores different bytes each time (a fresh iv per encryption)`() {
        vault.save(entry())
        val first = store.map.values.single()
        vault.save(entry())
        assertNotEquals(first, store.map.values.single())
    }

    @Test
    fun `nothing saved is no session`() {
        assertNull(vault.load(saved))
    }

    @Test
    fun `an entry copied from one address's slot to another's does not decrypt, and is deleted`() {
        vault.save(entry(saved))
        val blob = store.map.values.single()
        store.map["s:$other"] = blob // the attack: move the blob under another address
        assertNull(vault.load(other))
        assertFalse("the moved blob was deleted", store.map.containsKey("s:$other"))
        assertNotNull("the real one is untouched", vault.load(saved))
    }

    @Test
    fun `a tampered or truncated or malformed blob is no session and is deleted`() {
        vault.save(entry())
        val good = store.map.values.single()
        val parts = good.split(':')
        val flipped = parts[2].let { it.substring(0, it.length - 1) + (if (it.last() == '0') '1' else '0') }
        for (bad in listOf(
            "${parts[0]}:${parts[1]}:$flipped", // one nibble of the ciphertext flipped
            "${parts[0]}:${parts[1]}:${parts[2].dropLast(2)}", // truncated
            "${parts[0]}:${parts[1]}:", // no ciphertext
            "v9:${parts[1]}:${parts[2]}", // unknown format
            "${parts[0]}:zz:${parts[2]}", // not hex
            "${parts[0]}:${parts[1]}", // missing part
            "garbage",
        )) {
            store.map["s:$saved"] = bad
            assertNull("accepted: $bad", vault.load(saved))
            assertFalse("not deleted: $bad", store.map.containsKey("s:$saved"))
        }
    }

    @Test
    fun `an entry stamped with a different address than its slot is deleted even though it decrypts`() {
        // Sealed under the AAD of `saved` but stamped for `other`: the stamp inside must agree with the slot.
        val plain = """{"token":"$token","savedUrl":"$other","tailscaleUrl":null,"tailscaleId":null}""".toByteArray()
        val sealed = cipher.seal(plain, saved.toByteArray())
        store.map["s:$saved"] = "v1:${sealed.iv.joinToString("") { "%02x".format(it) }}:${sealed.ciphertext.joinToString("") { "%02x".format(it) }}"
        assertNull(vault.load(saved))
        assertFalse(store.map.containsKey("s:$saved"))
    }

    @Test
    fun `one entry per saved address, so a second save replaces and two addresses are independent`() {
        vault.save(entry(saved, "one.two.three"))
        vault.save(entry(saved, "four.five.six"))
        vault.save(entry(other, "seven.eight.nine"))
        assertEquals(2, store.map.size)
        assertEquals("four.five.six", vault.load(saved)!!.token)
        assertEquals("seven.eight.nine", vault.load(other)!!.token)
    }

    @Test
    fun `clear removes only that address and needs nothing else`() {
        vault.save(entry(saved))
        vault.save(entry(other))
        assertTrue(vault.clear(saved))
        assertNull(vault.load(saved))
        assertNotNull(vault.load(other))
        assertFalse("nothing left to clear", vault.clear(saved))
    }

    @Test
    fun `clear with a token removes the entry only when it is the stored token`() {
        vault.save(entry(t = "current.token.value"))
        assertFalse("a rejection of an OLD token must not delete a newer login", vault.clear(saved, onlyIfToken = "old.token.value"))
        assertEquals("current.token.value", vault.load(saved)!!.token)
        assertTrue(vault.clear(saved, onlyIfToken = "current.token.value"))
        assertNull(vault.load(saved))
    }

    @Test
    fun `clear with a token on an unreadable entry removes it and reports nothing cleared`() {
        vault.save(entry())
        store.map["s:$saved"] = "v1:00:00"
        assertFalse(vault.clear(saved, onlyIfToken = token))
        assertFalse(store.map.containsKey("s:$saved"))
    }

    @Test
    fun `a lost key is replaced and the save tried once more`() {
        cipher.sealFailures.add(KeyUnusable())
        assertTrue(vault.save(entry()))
        assertEquals(1, cipher.resets)
        assertEquals(2, cipher.sealCalls)
        assertEquals(entry(), vault.load(saved))
    }

    @Test
    fun `a key that stays unusable fails the save and stores nothing`() {
        cipher.sealFailures.add(KeyUnusable())
        cipher.sealFailures.add(KeyUnusable())
        assertFalse(vault.save(entry()))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `a replaced key makes entries sealed with the old one unreadable, and they are deleted`() {
        vault.save(entry())
        cipher.resetKey()
        assertNull(vault.load(saved))
        assertFalse(store.map.containsKey("s:$saved"))
    }

    @Test
    fun `a key store that is only busy fails the save and deletes nothing, not even the old entry`() {
        vault.save(entry(t = "kept.token.value"))
        cipher.sealFailures.add(KeyTransient())
        assertFalse(vault.save(entry(t = "new.token.value")))
        assertEquals(0, cipher.resets)
        cipher.openFailure = null
        assertEquals("kept.token.value", vault.load(saved)!!.token)
    }

    @Test
    fun `a busy key store on a read is no session for now but the entry is kept`() {
        vault.save(entry())
        cipher.openFailure = KeyTransient()
        assertNull(vault.load(saved))
        assertTrue("a transient failure must not delete a good entry", store.map.containsKey("s:$saved"))
        cipher.openFailure = null
        assertNotNull(vault.load(saved))
    }

    @Test
    fun `any other failure to seal is a failed save, not a crash`() {
        cipher.sealFailures.add(IllegalStateException("boom"))
        assertFalse(vault.save(entry()))
        assertTrue(store.map.isEmpty())
    }
}
