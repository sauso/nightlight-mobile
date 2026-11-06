package com.sauso.nightlight

import org.json.JSONObject

/**
 * Where the session token is kept, encrypted, in app-private storage (owner, 2026-11-06; plan phase D).
 *
 * One entry per SAVED ADDRESS (so the number of entries is bounded by the saved-server list, and a hostile page cannot make
 * more). The entry holds the token and the bindings it was saved under ([SessionRules.Entry]); it is encrypted with AES-256-GCM
 * and the saved address is the GCM additional authenticated data, so a blob copied from one address's slot to another's does
 * not decrypt. The cipher and the key-value store are injected: production uses the Android Keystore
 * ([KeystoreSessionCipher]) and a SharedPreferences file ([PREFS_FILE]); the JVM tests use a software key and a map.
 *
 * Not thread safe on its own: every call goes through ONE first-in-first-out executor (ServerConfigPlugin), so a save, a clear
 * and the key's creation can never reorder or race (review of phase D).
 *
 * Failure rules:
 *  - a blob that cannot be read back (tampered, truncated, from another key) is "no session" and is DELETED;
 *  - a key that is unusable ([KeyUnusable]) is replaced ([SessionCipher.resetKey]) and the save tried once more; an entry
 *    sealed with a lost key is undecryptable, so reading it deletes it;
 *  - a key store that is only temporarily unavailable ([KeyTransient]) deletes NOTHING: the entry may be fine, so the call
 *    just reports failure and a later call can succeed.
 */
class SessionVault(private val store: KeyValueStore, private val cipher: SessionCipher) {

    companion object {
        /** The SharedPreferences file the vault lives in. The backup rules exclude exactly this name (BackupRulesTest). */
        const val PREFS_FILE = "nightlight_session"
        private const val FORMAT = "v1"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }

    /** What SharedPreferences gives: a string per key. */
    interface KeyValueStore {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /** The key was lost or can no longer be used: the entries sealed with it are gone. */
    class KeyUnusable(cause: Throwable? = null) : Exception(cause)

    /** The key store is busy or unavailable right now; nothing is wrong with the key or the entries. */
    class KeyTransient(cause: Throwable? = null) : Exception(cause)

    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    /**
     * AES-GCM. The IV is generated INSIDE [seal] and returned: a Keystore key refuses a caller-supplied IV, and a fresh random
     * IV per encryption is what keeps GCM safe. [open] throws on any failure (bad tag, wrong key, bad data).
     */
    interface SessionCipher {
        fun seal(plain: ByteArray, aad: ByteArray): Sealed
        fun open(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
        /** Delete the key so the next [seal] makes a new one. */
        fun resetKey()
    }

    /** Save (or replace) the entry for [SessionRules.Entry.bindings]'s saved address. Returns false when it could not be saved. */
    fun save(entry: SessionRules.Entry): Boolean {
        val key = slot(entry.bindings.savedUrl)
        val plain = encode(entry).toByteArray(Charsets.UTF_8)
        val aad = entry.bindings.savedUrl.toByteArray(Charsets.UTF_8)
        for (attempt in 0..1) {
            try {
                val sealed = cipher.seal(plain, aad)
                store.put(key, "$FORMAT:${hex(sealed.iv)}:${hex(sealed.ciphertext)}")
                return true
            } catch (e: KeyUnusable) {
                if (attempt == 1) return false
                cipher.resetKey() // a lost key: make a new one and try once more
            } catch (e: KeyTransient) {
                return false
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    /** The entry saved for [savedUrl], or null (nothing saved, or what is there cannot be read: that is deleted). */
    fun load(savedUrl: String): SessionRules.Entry? {
        val key = slot(savedUrl)
        val raw = store.get(key) ?: return null
        val parts = raw.split(':')
        if (parts.size != 3 || parts[0] != FORMAT) { store.remove(key); return null }
        val iv = unhex(parts[1])
        val ciphertext = unhex(parts[2])
        if (iv == null || ciphertext == null) { store.remove(key); return null }
        // A real entry has a 12-byte iv and at least a 16-byte GCM tag plus one byte of text. Anything else is not ours, so it is
        // deleted without asking the cipher (an agy review of the plan: do not hand a malformed blob to the Keystore at all).
        if (iv.size != IV_BYTES || ciphertext.size <= TAG_BYTES) { store.remove(key); return null }
        val plain = try {
            cipher.open(iv, ciphertext, savedUrl.toByteArray(Charsets.UTF_8))
        } catch (e: KeyTransient) {
            return null // nothing is wrong with the entry: keep it
        } catch (e: Exception) {
            store.remove(key) // tampered, truncated, or sealed with a key that is gone
            return null
        }
        val entry = try { decode(String(plain, Charsets.UTF_8)) } catch (e: Exception) { null }
        // The AAD already ties the blob to this address; the stamp inside must agree too.
        if (entry == null || entry.bindings.savedUrl != savedUrl) { store.remove(key); return null }
        return entry
    }

    /**
     * Remove the entry for [savedUrl]; with [onlyIfToken] set, only when the stored token is exactly that one (a 401 for a
     * stale token must not delete a newer login). Needs no network. Returns whether an entry was removed.
     */
    fun clear(savedUrl: String, onlyIfToken: String? = null): Boolean {
        val key = slot(savedUrl)
        if (store.get(key) == null) return false
        if (onlyIfToken != null) {
            val entry = load(savedUrl) ?: return false // unreadable entries are deleted by load
            if (entry.token != onlyIfToken) return false
        }
        store.remove(key)
        return true
    }

    private fun slot(savedUrl: String) = "s:$savedUrl"

    private fun encode(entry: SessionRules.Entry): String {
        val b = entry.bindings
        return JSONObject()
            .put("token", entry.token)
            .put("savedUrl", b.savedUrl)
            .put("tailscaleUrl", b.tailscaleUrl ?: JSONObject.NULL)
            .put("tailscaleId", b.tailscaleId ?: JSONObject.NULL)
            .toString()
    }

    private fun decode(text: String): SessionRules.Entry? {
        val o = JSONObject(text)
        val token = o.optString("token", "")
        val saved = o.optString("savedUrl", "")
        if (token.isEmpty() || saved.isEmpty()) return null
        fun optional(name: String): String? = if (o.isNull(name)) null else o.optString(name, "").ifEmpty { null }
        return SessionRules.Entry(token, SessionRules.Bindings(saved, optional("tailscaleUrl"), optional("tailscaleId")))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        return try {
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
