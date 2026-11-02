package com.sauso.nightlight

import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The rules that decide whether something found on the network may be offered as a Nightlight server
 * ("Look for my Nightlight", #20; Connect & Discovery design sections 7.5 and 7.6).
 *
 * Pure Kotlin with no Android imports, so the JVM unit tests run exactly this code. org.json is Android's own JSON
 * API; the unit tests put the real org.json library on their classpath (android/app/build.gradle says why), because
 * the android.jar used by local tests only has stubs.
 *
 * Everything that reaches these functions came from SOME device on the local network: an mDNS announcement can name
 * any address and carry any TXT record, and anything can answer a health check. So every function here fails closed
 * (null or false) on anything it does not recognise.
 *
 * What this is NOT: authentication. The id and the name are public (/api/health serves them to anyone), so a device
 * on the LAN can announce another host it controls and answer correctly. The defences are the person's tap (nothing
 * is saved or switched without one: the #559 principle), the id check again at the moment of saving (`save`'s
 * expectId), and for stronger assurance the typed address.
 */
object DiscoveryRules {

    /** A health reply that passed [parseHealth]: the server's id, and its display name if it sent a usable one. */
    data class Health(val id: String, val name: String?)

    /** Shown when a server sends no usable name. The server's own default is the same word (serverIdentity.js). */
    const val DEFAULT_NAME = "Nightlight"

    // A display name comes from an unauthenticated reply, so it is cut to a length a card can show. 64 is a
    // judgement, not a measurement: well past any real server name, short enough that a 10 000-character name
    // cannot push the Connect button off the screen.
    const val MAX_NAME_CHARS = 64

    // The fingerprint is the same 8 characters the server puts in its announced instance name
    // (`Nightlight-<first 8 of the id>`, mdnsAnnounce.js publishOptions), so a person can compare the two.
    const val FINGERPRINT_CHARS = 8

    // The server's id is `crypto.randomUUID()` (db.js), and the TXT `id` is that same value. Anything that is not
    // UUID-shaped did not come from a Nightlight server and is ignored before any network request is made.
    private val UUID_SHAPE = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /**
     * The four octets of a strict dotted-quad IPv4 literal, or null. Strict on purpose: exactly four parts, each one
     * to three ASCII digits, no leading zero (so `010.0.0.1` and `192.168.001.001`, which some parsers read as
     * octal or as something else entirely, are refused), no sign, no whitespace, each 0 to 255. A hostname, an IPv6
     * literal (including the IPv4-mapped `::ffff:` form) and `192.168.1.1.evil.example` are all refused, which also
     * means the probe never needs a DNS lookup (HealthProbe builds the address from these octets).
     */
    fun parseIpv4(host: String?): IntArray? {
        if (host.isNullOrEmpty()) return null
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            // '0'..'9' and not Char.isDigit(): isDigit also accepts other scripts' digits (Arabic-Indic and so on).
            if (!part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    /**
     * True only for an IPv4 literal in the three RFC 1918 private ranges: 10/8, 172.16/12, 192.168/16.
     *
     * Why so narrow (design 7.5 step 1): the announced address is attacker-influenced, and the phone must not be
     * talked into contacting an arbitrary host, itself, or an address that needs an interface scope. So loopback,
     * link-local (169.254/16), 0.0.0.0, 255.255.255.255, multicast, every public address, all IPv6 and 100.64/10
     * are refused. 100.64/10 is carrier-grade NAT space, which includes Tailscale's addresses: a tailnet
     * address is not "on this Wi-Fi", and the Tailscale route is designed separately. IPv6 and link-local are a
     * stated v1 limit (design section 17).
     */
    fun isPrivateIpv4(host: String?): Boolean {
        val o = parseIpv4(host) ?: return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168)
    }

    /**
     * Parse a `/api/health` reply. Accepts only a JSON object with `ok` exactly `true` (the boolean, not the string),
     * `app` exactly `"nightlight"` and a non-empty string `id` (serverIdentity.js healthBody). `name` is optional:
     * absent or JSON null means "no name" (the card shows [DEFAULT_NAME]); a string is cleaned ([cleanName]); any
     * other type means this is not a reply we understand, so the whole body is refused. Unknown extra fields are
     * allowed, so a newer server that adds one is still recognised. Anything else (an array, `null`, wrong types,
     * the fields nested one level down, truncated JSON) returns null.
     *
     * An older server answers `{ "ok": true }` only, with no app or id, and is therefore not offered: discovery
     * needs the server release that announces itself (#938), and the typed address still works for an older one.
     */
    fun parseHealth(body: String?): Health? {
        if (body.isNullOrEmpty()) return null
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            // org.json parses nested arrays recursively; a body is at most 4 KB (HealthProbe), but `save` reads
            // a reply of any size, and a deep `[[[[…` must refuse, not crash the app.
            return null
        }
        if (obj.opt("ok") != true) return null
        if (obj.opt("app") != "nightlight") return null
        val id = obj.opt("id") as? String ?: return null
        if (id.isEmpty()) return null
        val rawName = obj.opt("name")
        val name = when {
            rawName == null || rawName == JSONObject.NULL -> null
            rawName is String -> cleanName(rawName)
            else -> return null
        }
        return Health(id, name)
    }

    /**
     * True when a verified health reply belongs to the server whose announcement (TXT `id`) led us to it. Exact
     * string equality: the server sends the same value in both places, so any difference means a different server
     * (design 7.5 step 2), and a txtId that is not UUID-shaped never matches.
     */
    fun matches(health: Health?, txtId: String?): Boolean {
        if (health == null || !isPlausibleId(txtId)) return false
        return health.id == txtId
    }

    /**
     * The check `ServerConfig.save` makes when it is given `expectId` (a card tapped on the setup page): the body
     * must be a Nightlight health reply and its id must be the one the card was verified with. Without this, `save`
     * accepts any 200 reply containing "ok" (plan review, High), so an address that changed hands between the
     * search and the tap (a DHCP lease moving to another device, say) could be saved. Design 7.5 step 6.
     */
    fun healthMatchesExpected(body: String?, expectId: String?): Boolean = matches(parseHealth(body), expectId)

    /**
     * A short, human-comparable form of an id: lower-cased, ASCII letters and digits only, the first
     * [FINGERPRINT_CHARS] of those. The same derivation as the server's instance name (mdnsAnnounce.js:
     * `toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 8)`), so `Nightlight-4f1c2b9e` and `ID 4f1c2b9e` agree.
     * Not proof of ownership (design 7.5): it helps a person who compares it with what the server shows.
     */
    fun fingerprint(id: String?): String {
        if (id == null) return ""
        val out = StringBuilder()
        for (c in id.lowercase(Locale.ROOT)) {
            if (c in 'a'..'z' || c in '0'..'9') {
                out.append(c)
                if (out.length == FINGERPRINT_CHARS) break
            }
        }
        return out.toString()
    }

    /** True for a UUID-shaped string, the only form a server id takes (see [UUID_SHAPE]). */
    fun isPlausibleId(id: String?): Boolean = id != null && UUID_SHAPE.matches(id)

    /**
     * The server id from an announcement's TXT record (NsdServiceInfo.getAttributes), or null if this announcement
     * should be ignored. Read only from a RESOLVED service: Android documents TXT attributes as valid only after a
     * resolve (plan review, High), which ServerDiscovery respects.
     *
     * Rules: keys are matched without regard to case (RFC 6763 section 6.4). `v` is the record format version
     * (mdnsAnnounce.js sends `v=1`): absent is read as 1, `1` is accepted, anything else is a format this app does
     * not know and the announcement is ignored rather than guessed at. Exactly one `id` key, whose value is
     * UUID-shaped; zero, two (`id` and `ID` with different values), an empty value or a key without a value all
     * mean null.
     */
    fun txtId(attributes: Map<String, ByteArray?>?): String? {
        if (attributes == null) return null
        var versions = 0
        var version: ByteArray? = null
        val ids = ArrayList<ByteArray?>()
        for ((key, value) in attributes) {
            if (key.equals("v", ignoreCase = true)) {
                versions++
                version = value
            } else if (key.equals("id", ignoreCase = true)) {
                ids.add(value)
            }
        }
        if (versions > 1) return null
        if (versions == 1 && (version == null || String(version, StandardCharsets.UTF_8) != "1")) return null
        if (ids.size != 1) return null
        val raw = ids[0] ?: return null
        val id = String(raw, StandardCharsets.UTF_8)
        return if (isPlausibleId(id)) id else null
    }

    /**
     * Whether the page that is calling the plugin is the app's own bundled setup page (www/index.html), the only
     * page allowed to start or stop a search (plan decision 3a: once a server is loaded, that server's web app can
     * reach ServerConfig too, and a LAN scan and its results must not be available to it).
     *
     * A Capacitor plugin call does not say which page sent it (Capacitor's MessageHandler drops the message's
     * origin), so two things are checked, and both must hold:
     *  1. no server URL is configured for this bridge. MainActivity sets one only when it loads a saved server;
     *     the bundled page loads only when there is none. This is a fact about the whole bridge, so it cannot be
     *     faked by anything a page does.
     *  2. the WebView's current main-frame URL is on the bundled origin, `<scheme>://<hostname>` from the
     *     Capacitor config (`https://localhost` by default), with no port and no user info. Compared against
     *     the config's scheme and hostname, NOT Bridge.getLocalUrl(): with a server URL set, Capacitor rewrites
     *     its "local URL" to the server's own origin.
     * [pageUrl] is WebView.getUrl(), which ServerConfigPlugin reads on the main thread.
     */
    fun isBundledPage(pageUrl: String?, configuredServerUrl: String?, localScheme: String?, localHost: String?): Boolean {
        if (!configuredServerUrl.isNullOrEmpty()) return false
        if (pageUrl.isNullOrEmpty() || localScheme.isNullOrEmpty() || localHost.isNullOrEmpty()) return false
        val uri = try {
            URI(pageUrl)
        } catch (e: Exception) {
            return false
        }
        // rawAuthority, not host: it still carries any user info or port, so `https://user@localhost/` and
        // `https://localhost:8443/` do not pass as the bundled origin.
        return uri.scheme.equals(localScheme, ignoreCase = true) &&
            uri.rawAuthority.equals(localHost, ignoreCase = true)
    }

    /**
     * The address a found server is offered and saved under: explicit `http://ip:port`. Not the typed-address
     * https-then-http logic: a certificate cannot match a bare IP (design 7.5 step 3), so https would only fail.
     */
    fun serverUrl(host: String, port: Int): String = "http://$host:$port"

    /**
     * A display name made safe to show: control characters, invisible format characters (including the
     * right-to-left override, which can make a name read backwards) and line or paragraph separators removed,
     * whitespace trimmed, then cut to [MAX_NAME_CHARS] code points (never half a surrogate pair). Empty after
     * cleaning means no name. The page also sets it with textContent, never as HTML.
     */
    private fun cleanName(raw: String): String? {
        val kept = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (!isHiddenCodePoint(cp)) kept.appendCodePoint(cp)
        }
        val trimmed = kept.toString().trim()
        val capped = if (trimmed.codePointCount(0, trimmed.length) > MAX_NAME_CHARS) {
            trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_NAME_CHARS))
        } else {
            trimmed
        }
        return capped.trim().ifEmpty { null }
    }

    private fun isHiddenCodePoint(cp: Int): Boolean {
        if (Character.isISOControl(cp)) return true
        val type = Character.getType(cp)
        return type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            type == Character.SURROGATE.toInt()
    }
}
