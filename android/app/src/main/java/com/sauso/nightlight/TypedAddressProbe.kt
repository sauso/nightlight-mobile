package com.sauso.nightlight

/** Issue #22: one scheme decision for typed save, so the first accepted address is the one persisted. */
object TypedAddressProbe {
    fun candidates(raw: String): List<String>? {
        val bare = raw.trimEnd('/')
        return if (bare.contains("://")) {
            if (!bare.startsWith("https://") && !bare.startsWith("http://")) null else listOf(bare)
        } else {
            // The existing setup rule tries TLS first for a bare address, then plain HTTP on home networks.
            listOf("https://$bare", "http://$bare")
        }
    }

    fun firstAccepted(candidates: List<String>, check: (String) -> String?): Pair<String, String>? {
        for (url in candidates) {
            // Review of #50: one malformed or failed TLS candidate must not prevent the HTTP fallback.
            val body = try { check(url) } catch (e: Exception) { null }
            if (body != null) return url to body
        }
        return null
    }
}
