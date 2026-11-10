package com.sauso.nightlight

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Which Nightlight server this app talks to, chosen at runtime rather than baked
 * into the APK (the same first-run flow as the Home Assistant app).
 *
 * From the web side (both the bundled setup/error pages and the remote app):
 *
 *   Capacitor.Plugins.ServerConfig.get()            -> { url, tailscale, supportsCustomUrl: true, kind }
 *   Capacitor.Plugins.ServerConfig.addEndpoint({ url })   -> { added }; saves the server's secure address, asking the
 *                                                            person first when it is new and the page is not https (see below)
 *   Capacitor.Plugins.ServerConfig.removeEndpoint()       -> { removed }
 *   Capacitor.Plugins.ServerConfig.save({ url })    -> validates + persists, rejects if unreachable
 *   Capacitor.Plugins.ServerConfig.save({ url, expectId })
 *                                                   -> the same, and refuses unless the server there
 *                                                      reports exactly that id (a found server, #20)
 *   Capacitor.Plugins.ServerConfig.clear()
 *   Capacitor.Plugins.ServerConfig.restart()        -> rebuilds the activity/bridge so the
 *                                                      current saved choice takes effect
 *
 * From the bundled setup page ONLY (www/index.html; any other page is refused, see callerIsBundledPage):
 *
 *   Capacitor.Plugins.ServerConfig.startDiscovery() -> { started, onWifi }; looks for servers on the LAN
 *   Capacitor.Plugins.ServerConfig.stopDiscovery()
 *   event serverFound { id, name, url, fingerprint } -> a server that answered its health check
 *   event serverLost  { id }                         -> its announcement went away
 *   event discoveryFailed { }                        -> looking could not start on this phone
 *
 * MainActivity reads the saved value in onCreate and points the Capacitor bridge at
 * it; no saved value means the bundled first-run setup page (www/) loads instead.
 */
@CapacitorPlugin(name = "ServerConfig")
class ServerConfigPlugin : Plugin() {

    companion object {
        private const val PREFS = "nightlight_server"
        private const val KEY_URL = "server_url"
        // Every server that has ever passed validation, most recently used first -
        // lets the setup screen offer previous servers as one-tap choices instead
        // of making the person retype an address they've already proven works.
        private const val KEY_KNOWN = "known_servers"
        // The display name each remembered server sent (its public /api/health `name`), as one JSON object { address: name }, so the
        // setup page can list "Name" with the address under it (owner, 2026-11-06). Only for remembered servers (EndpointRules).
        private const val KEY_NAMES = "server_names"

        fun getSavedUrl(context: Context): String? {
            val url = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)
            return if (url.isNullOrBlank()) null else url
        }

        // The server's secure address that goes with [primary], or null (see EndpointRules). The legacy key and getter name
        // stay because existing installs already store Tailscale addresses there; the slot can now also hold custom HTTPS.
        fun getTailscaleUrl(context: Context, primary: String): String? {
            val url = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EndpointRules.key(primary), null)
            // Re-checked on read: only a validated secure address ever reaches launch, roaming, reconnect or the page.
            return EndpointRules.validatedSlot(url)
        }

        // The server id the saved Tailscale address was saved for (see EndpointRules.idKey), or null.
        fun getTailscaleId(context: Context, primary: String): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EndpointRules.idKey(primary), null)

        private fun serverIdKey(url: String) = "server_id:$url"
        private fun movedDeclinesKey(url: String) = "moved_declines:$url"

        fun getServerId(context: Context, url: String): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(serverIdKey(url), null)

        // A pair's stored id still identifies a private IPv4 saved address before the first direct id learn. Other URL
        // forms cannot be read by HealthProbe and must keep their legacy launch and save behaviour.
        fun expectedId(context: Context, savedUrl: String): String? =
            EndpointRules.expectedIdForAddress(savedUrl, getServerId(context, savedUrl), getTailscaleId(context, savedUrl))

        // The compare and write stay together on the one executor. An answer from a different device at the old
        // address must never replace the identity previously learned there.
        fun setServerIdIfAbsent(context: Context, url: String, id: String) {
            sessionExecutor.execute {
                val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (getSavedUrl(context) == url && p.getString(serverIdKey(url), null) == null)
                    p.edit().putString(serverIdKey(url), id).apply()
            }
        }

        fun getStoredName(context: Context, url: String): String? = try {
            JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAMES, "{}"))
                .optString(url).takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }

        fun getMovedDeclines(context: Context, oldUrl: String): Map<String, Long> =
            MovedServerRules.Declines.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(movedDeclinesKey(oldUrl), null))

        fun recordMovedDecline(context: Context, oldUrl: String, candidateUrl: String) {
            sessionExecutor.execute {
                if (getSavedUrl(context) != oldUrl) return@execute
                val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val old = MovedServerRules.Declines.decode(p.getString(movedDeclinesKey(oldUrl), null))
                val updated = MovedServerRules.Declines.record(old, candidateUrl, System.currentTimeMillis())
                p.edit().putString(movedDeclinesKey(oldUrl), MovedServerRules.Declines.encode(updated)).apply()
            }
        }

        private fun removeAddressData(p: SharedPreferences, url: String) {
            val list = knownServersOf(p)
            list.remove(url)
            val names = try { JSONObject(p.getString(KEY_NAMES, "{}")) } catch (e: Exception) { JSONObject() }
            names.remove(url)
            val edit = p.edit().putString(KEY_KNOWN, JSONArray(list).toString()).putString(KEY_NAMES, names.toString())
                .remove(EndpointRules.key(url)).remove(EndpointRules.idKey(url))
                .remove(EndpointRules.declinedKey(url)).remove(EndpointRules.declinedAtKey(url))
                .remove(serverIdKey(url)).remove(movedDeclinesKey(url))
            // Forget and the saved choice belong in one preferences edit, so a later launch cannot see an active URL
            // whose remembered id has already gone. Move cleanup sees the new active URL and leaves it alone.
            if (EndpointRules.forgetClearsActive(p.getString(KEY_URL, null), url)) edit.remove(KEY_URL)
            edit.apply()
        }

        private fun removeAddress(context: Context, url: String) {
            removeAddressData(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), url)
            SessionVault(PrefsKeyValueStore(context.getSharedPreferences(SessionVault.PREFS_FILE, Context.MODE_PRIVATE)),
                KeystoreSessionCipher()).clear(url)
        }

        private fun hasMoveDestinationLeftovers(context: Context, p: SharedPreferences, url: String): Boolean {
            val names = try { JSONObject(p.getString(KEY_NAMES, "{}")) } catch (e: Exception) { JSONObject() }
            val session = context.getSharedPreferences(SessionVault.PREFS_FILE, Context.MODE_PRIVATE)
            return names.has(url) || session.contains("s:$url") ||
                listOf(EndpointRules.key(url), EndpointRules.idKey(url), EndpointRules.declinedKey(url),
                    EndpointRules.declinedAtKey(url), serverIdKey(url), movedDeclinesKey(url)).any { p.contains(it) }
        }

        private fun clearMoveDestination(context: Context, p: SharedPreferences, url: String): Boolean {
            return try {
                val session = context.getSharedPreferences(SessionVault.PREFS_FILE, Context.MODE_PRIVATE)
                // A reused URL must not expose the previous server's encrypted login. Abort before the saved-URL flip
                // if that slot exists but cannot be removed; SessionVault.clear returns false for an absent slot too.
                if (session.contains("s:$url")) {
                    val vault = SessionVault(PrefsKeyValueStore(session), KeystoreSessionCipher())
                    if (!vault.clear(url) || session.contains("s:$url")) return false
                }
                removeAddressData(p, url)
                true
            } catch (e: Exception) {
                false
            }
        }

        // Called only after the new address answered with the wanted id. One executor task checks the current
        // choice, writes every new-address field, flips the choice, and finally removes the old address's data.
        fun moveServer(context: Context, oldUrl: String, newUrl: String, id: String, name: String?,
            pairUrlAtCheck: String?, mayCommit: () -> Boolean, onMoved: (Boolean) -> Unit) {
            sessionExecutor.execute {
                val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val stored = getServerId(context, oldUrl)
                val pairUrl = getTailscaleUrl(context, oldUrl)
                val pairId = getTailscaleId(context, oldUrl)
                // The pair need not answer now; its stored id is the identity, provided the pair has not changed since the check.
                val guardId = stored ?: if (pairUrl == pairUrlAtCheck) pairId else null
                if (!mayCommit() || !MovedServerRules.MoveGuard.stillValid(getSavedUrl(context), guardId, oldUrl, id)) {
                    onMoved(false)
                    return@execute
                }
                val plan = MovedServerRules.movePlan(oldUrl, newUrl, id, name, knownServersOf(p),
                    hasMoveDestinationLeftovers(context, p, newUrl), pairUrl, pairId)
                for (step in plan) when (step) {
                    is MovedServerRules.MoveStep.ClearNew -> {
                        if (!clearMoveDestination(context, p, step.url)) {
                            onMoved(false)
                            return@execute
                        }
                    }
                    is MovedServerRules.MoveStep.AddKnown -> {
                        val list = knownServersOf(p)
                        list.remove(step.url)
                        list.add(0, step.url)
                        p.edit().putString(KEY_KNOWN, JSONArray(list).toString()).apply()
                    }
                    is MovedServerRules.MoveStep.SetName -> {
                        val names = try { JSONObject(p.getString(KEY_NAMES, "{}")) } catch (e: Exception) { JSONObject() }
                        names.put(step.url, step.name)
                        p.edit().putString(KEY_NAMES, names.toString()).apply()
                    }
                    is MovedServerRules.MoveStep.SetServerId -> p.edit().putString(serverIdKey(step.url), step.id).apply()
                    is MovedServerRules.MoveStep.CarryPair -> p.edit()
                        .putString(EndpointRules.key(step.url), step.pairUrl)
                        .putString(EndpointRules.idKey(step.url), step.pairId).apply()
                    is MovedServerRules.MoveStep.SetSavedUrl -> p.edit().putString(KEY_URL, step.url).apply()
                    is MovedServerRules.MoveStep.RemoveOld -> removeAddress(context, step.url)
                }
                onMoved(true)
            }
        }

        private fun knownServersOf(prefs: SharedPreferences): MutableList<String> {
            val list = mutableListOf<String>()
            try {
                val arr = JSONArray(prefs.getString(KEY_KNOWN, "[]"))
                for (i in 0 until arr.length()) list.add(arr.getString(i))
            } catch (e: Exception) {
                // Corrupt prefs entry - treat as empty.
            }
            return list
        }

        // A server named by a tapped nightlight:// link, waiting for the web app to ask the person (#559).
        // This replaces the old saveUrl, which saved a link's server as the ACTIVE one (and added it to the
        // known list) with no question; nothing in native code switches server on its own any more. ONE slot,
        // replaced by a newer link, and it expires after two minutes so a request nobody answered cannot
        // turn up in a later session. In memory only: a request does not survive the app being killed.
        private const val PENDING_TTL_MS = 2 * 60 * 1000L
        private var pendingServer: String? = null
        private var pendingAtMs = 0L

        fun setPending(origin: String) {
            synchronized(this) {
                pendingServer = origin
                pendingAtMs = SystemClock.elapsedRealtime()
            }
        }

        // A newer link that names no usable server cancels whatever an earlier link left waiting.
        fun clearPending() {
            synchronized(this) { pendingServer = null }
        }

        // Hand the held request over exactly once: it is cleared whether or not it was still fresh.
        fun takePendingServer(): String? {
            synchronized(this) {
                val server = pendingServer
                val fresh = server != null && SystemClock.elapsedRealtime() - pendingAtMs <= PENDING_TTL_MS
                pendingServer = null
                return if (fresh) server else null
            }
        }

        private const val TAG = "ServerConfig"

        // ONE first-in-first-out thread for every session save, clear and read (SessionVault is not thread safe, and a save must
        // never overtake the clear that followed it). Process-wide so a recreated activity shares it. A daemon thread.
        private val sessionExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nightlight-session").apply { isDaemon = true } }

        // ---- "Ask once" before a secure address learned from a plain-http page is saved (Codex code review of PR #37, High;
        // the owner chose a native prompt, 2026-11-09; the plan's addendum and its review) ----

        // One prompt at a time and at most one per 10 minutes, for the whole process (PromptGate), so a recreated activity
        // cannot reset either.
        private val promptGate = PromptGate()

        // The prompt on screen, if any, so cancelPending can end it. Set before it is shown, cleared when it ends.
        @Volatile
        private var pendingPrompt: SecureAddressPrompt? = null

        /**
         * Ends a prompt that is still showing as "not answered": the call is refused, the gate released, and NO decline is
         * stored. MainActivity.onDestroy calls it (before the window goes), so a rebuild (a roam, Change server) cannot leave
         * the web app waiting for an answer that will never come (the addendum review, 2026-11-09). Main thread.
         */
        fun cancelPending() {
            pendingPrompt?.finish(PromptGate.Outcome.CANCELLED)
        }


        // The plan's wording (#20, decision 3): plain, and it says what to do next.
        private const val NOT_THE_FOUND_SERVER = "That address is not the server we found. Search again."
        private const val NOT_ON_THIS_PAGE = "Looking for servers is only available on the setup screen."
        private const val ADDRESS_TAKEN = "That address now belongs to a different server. Look for your Nightlight, or type its new address."

        // How long the plugin thread waits for the main thread to read the WebView's URL (callerIsBundledPage).
        // The main thread answers in microseconds unless it is busy; if it is busy for a whole second the call is
        // refused (fails closed) and the page simply hides the search.
        private const val PAGE_CHECK_TIMEOUT_MS = 1_000L

        // Quick liveness check used at launch (see MainActivity) to decide whether to point the
        // app at the saved server or drop to the bundled setup screen. Must be called OFF the main
        // thread — HttpURLConnection can sit the full timeout on an unreachable host.
        fun isReachable(url: String, timeoutMs: Int): Boolean {
            return try {
                val conn = URL("$url/api/health").openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                val ok = conn.responseCode == 200
                conn.disconnect()
                ok
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // "Look for my Nightlight" (#20). Made on the first startDiscovery, so a phone that never searches never touches
    // NsdManager. Volatile: plugin methods run on Capacitor's plugin thread, the lifecycle hooks below on the main one.
    @Volatile
    private var discovery: ServerDiscovery? = null

    // Events reach the page through notifyListeners, which must run on Capacitor's plugin thread: the listener lists
    // it walks are plain collections that addListener and removeListener (plugin methods) change on that thread.
    // The generation is checked again THERE, after any stop or start the page asked for has run, so a result from a
    // search the page has already ended is never delivered (plan review: the stop state machine). notifyListeners
    // is called without retainUntilConsumed: an event nobody is listening for is dropped, never replayed to a later
    // listener (that would be a card from an old search), which is why the page adds its listeners first.
    private val discoveryEvents = object : ServerDiscovery.Listener {
        override fun onServerFound(generation: Int, found: DiscoveryState.Found) {
            val data = JSObject()
            data.put("id", found.id)
            data.put("name", found.name)
            data.put("url", found.url)
            data.put("fingerprint", found.fingerprint)
            deliver(generation, "serverFound", data)
        }

        override fun onServerLost(generation: Int, id: String) {
            deliver(generation, "serverLost", JSObject().put("id", id))
        }

        // Looking could not start (NsdManager refused, or failed asynchronously after startDiscovery had already
        // resolved { started: true }). Code review of #20: logging alone left the page saying "Looking…" for two
        // minutes. The page shows its help at once and offers Search again. No details are sent: the message is
        // for the log, not the person.
        override fun onDiscoveryFailed(generation: Int, message: String) {
            Log.w(TAG, message)
            deliver(generation, "discoveryFailed", JSObject())
        }
    }

    private fun deliver(generation: Int, event: String, data: JSObject) {
        val b = bridge ?: return
        b.execute {
            if (discovery?.isCurrent(generation) == true) notifyListeners(event, data)
        }
    }

    /**
     * Whether the page calling us is the bundled setup page (plan decision 3a). See DiscoveryRules.isBundledPage
     * for the rule. WebView.getUrl must be called on the main thread, and plugin methods run on Capacitor's plugin
     * thread, so the check is handed to the main thread and awaited here; that keeps start and stop in the order
     * the page sent them (both run on this one thread). Fails closed: no bridge, no answer in time, or any error
     * means "not the bundled page".
     */
    private fun callerIsBundledPage(): Boolean {
        val b = bridge ?: return false
        val check = FutureTask(Callable {
            DiscoveryRules.isBundledPage(b.webView?.url, b.serverUrl, b.scheme, b.host)
        })
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) check.run() else Handler(Looper.getMainLooper()).post(check)
            check.get(PAGE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }

    private fun knownServers(): MutableList<String> = knownServersOf(prefs())

    private fun storedNames(): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(prefs().getString(KEY_NAMES, "{}"))
            for (key in obj.keys()) obj.optString(key, "").let { if (it.isNotBlank()) out[key] = it }
        } catch (e: Exception) {
            // Corrupt prefs entry - treat as no names (the list falls back to addresses).
        }
        return out
    }

    // Remember the name a server sent. Called after rememberServer(url), so the url is in the saved list; on the session executor
    // like the other writes (a read-modify-write of one JSON value).
    private fun rememberName(url: String, name: String?) {
        if (!EndpointRules.mayRememberName(knownServers(), url, name)) return
        val names = storedNames()
        names[url] = name!!
        prefs().edit().putString(KEY_NAMES, namesJson(names)).apply()
    }

    // A loop, not JSONObject(map): the Map constructor's key type does not accept a Kotlin Map<String, String> without a cast.
    private fun namesJson(names: Map<String, String>): String {
        val obj = JSONObject()
        for ((url, name) in names) obj.put(url, name)
        return obj.toString()
    }

    private fun rememberServer(url: String) {
        val list = knownServers()
        list.remove(url)
        list.add(0, url)
        prefs().edit().putString(KEY_KNOWN, JSONArray(list).toString()).apply()
    }

    // `url` is the saved (primary) address; legacy `tailscale` is the secure slot of either kind, or null.
    // Which one the page was loaded from is NOT said here: the page knows its own origin.
    @PluginMethod
    fun get(call: PluginCall) {
        val ret = JSObject()
        val saved = getSavedUrl(context)
        ret.put("url", saved)
        val slot = saved?.let { getTailscaleUrl(context, it) }
        ret.put("tailscale", slot)
        ret.put("supportsCustomUrl", true)
        ret.put("kind", EndpointRules.addressKind(slot))
        call.resolve(ret)
    }

    // The server a tapped link asked for, if any (#559): { server: "<origin>" | null }. Returns it once and
    // clears it. The web app asks the person before anything changes; this method only reports.
    @PluginMethod
    fun takePending(call: PluginCall) {
        val ret = JSObject()
        ret.put("server", takePendingServer())
        call.resolve(ret)
    }

    // Previously used servers plus which one is currently active, for the setup
    // screen's one-tap list.
    @PluginMethod
    fun list(call: PluginCall) {
        val ret = JSObject()
        val known = knownServers()
        ret.put("servers", JSArray(known))
        // Added alongside `servers` (an older page ignores it): the names the servers sent, for the setup page to show above the
        // address. Only remembered servers' names, so a forgotten server's name is never listed.
        val names = JSObject()
        for ((url, name) in EndpointRules.namesForKnown(storedNames(), known)) names.put(url, name)
        ret.put("names", names)
        val active = getSavedUrl(context)
        ret.put("active", active)
        ret.put("tailscale", active?.let { getTailscaleUrl(context, it) })
        call.resolve(ret)
    }

    // Drop a server from the remembered list and clear the active choice when it is that server. Its Tailscale address
    // goes with it: it was only ever meaningful for that server.
    // ONLY from the bundled setup page (callerIsBundledPage, as startDiscovery and stopDiscovery): its ✕ is the one caller. Codex
    // code review of the PR #37 prompt delta, 2026-11-09: forget clears the "ask once" decline, so a hostile page at the plain-http
    // saved address could otherwise forget the active server and offer the same address again at once, defeating the 7-day quiet
    // period. Refused with the existing "Not available on this page" (EndpointRules.refusal), no new sentence.
    @PluginMethod
    fun forget(call: PluginCall) {
        if (!callerIsBundledPage()) {
            call.reject(refusal(EndpointRules.AddCheck.NOT_THIS_PAGE))
            return
        }
        val url = call.getString("url")
        if (url != null) {
            // The saved login for that address goes too (it was only ever meaningful for that server). The list is read INSIDE the
            // executor, so a save() queued just before this one is not lost (Opus review).
            sessionExecutor.execute {
                removeAddress(context, url)
                call.resolve()
            }
            return
        }
        call.resolve()
    }

    // The Tailscale address saved for the saved server, with the server id it was saved for.
    private fun forgetTailscale(edit: SharedPreferences.Editor, primary: String): SharedPreferences.Editor =
        edit.remove(EndpointRules.key(primary)).remove(EndpointRules.idKey(primary))

    // The sentences are EndpointRules.refusal (pure, pinned by a JVM test, byte-identical to iOS).
    private fun refusal(check: EndpointRules.AddCheck): String = EndpointRules.refusal(check)

    // addEndpoint({ url }): the server on screen has said "you can also reach me at <url>" (the web app read it from
    // GET /api/remote-access/address while signed in). Saved only when ALL of these hold, each fails closed
    // (the decisions are EndpointRules.checkPage and checkIdentity, unit-tested on the JVM):
    //   - the url is a Tailscale or custom HTTPS origin: no path, credentials or IP;
    //   - the page is the saved server or the Tailscale address already saved for it (bridge.serverUrl), so the bundled setup
    //     page and anything else are refused;
    //   - the page's server and the url answer /api/health with the SAME id (EndpointProbe): an address that merely looks
    //     right is not saved for a server that is not this one. The id is public, so this is a claim, not proof (see
    //     checkIdentity); it is saved with the address and re-checked each time the address is opened;
    //   - the page the WebView is really SHOWING has the origin the bridge was built for (SessionRules.sameOrigin, the twin of
    //     iOS's isTrustedPage), read after the identity check and again just before the write;
    //   - a NEW or CHANGED address offered while the shown page is not https is confirmed by the person first (the "ask once"
    //     prompt: EndpointRules.needsConfirmation, PromptGate, SecureAddressPrompt; Codex code review of PR #37 rated the planted
    //     address High twice, because under Tailscale first a saved secure address opens ahead of the saved one, at home too).
    // Resolves { added }: true when this is new or different, false when it was already saved (so the app tells the
    // person once). Nothing is switched or restarted here: the address is used the next time the app starts. While the prompt
    // shows, the call waits for the answer (at most PromptGate.ANSWER_TIMEOUT_MS).
    @PluginMethod
    fun addEndpoint(call: PluginCall) {
        val url = call.getString("url")?.trim()?.trimEnd('/')
        val primary = getSavedUrl(context)
        val page = bridge?.serverUrl
        val first = EndpointRules.checkPage(url, primary, primary?.let { getTailscaleUrl(context, it) }, page)
        if (first != EndpointRules.AddCheck.OK || url == null || primary == null || page == null) {
            call.reject(refusal(if (first == EndpointRules.AddCheck.OK) EndpointRules.AddCheck.NOT_THIS_PAGE else first))
            return
        }
        Thread {
            val reference = EndpointProbe.fetchHealth(page, 4000)
            val candidate = EndpointProbe.fetchHealth(url, 5000)
            val second = EndpointRules.checkIdentity(reference, candidate)
            if (second != EndpointRules.AddCheck.OK || candidate == null) {
                call.reject(refusal(if (second == EndpointRules.AddCheck.OK) EndpointRules.AddCheck.UNREACHABLE_ADDRESS else second))
                return@Thread
            }
            // Whether to ask is decided on the page actually SHOWN, not on the bridge's configured address (the addendum review,
            // 2026-11-09), and that page must have the bridge's origin: otherwise a hostile http page could call this and then
            // send the WebView to an https page (the bundled https://localhost one, say) so that the call looked as if it came from
            // https. A page that cannot be read fails this too (refused, fails closed).
            val shown = webViewUrlOnMainThread()
            if (!SessionRules.sameOrigin(shown, page)) {
                call.reject(refusal(EndpointRules.AddCheck.NOT_THIS_PAGE))
                return@Thread
            }
            if (!EndpointRules.needsConfirmation(shown, getTailscaleUrl(context, primary), url)) {
                writeEndpoint(call, primary, url, candidate.id, reference?.name, page, accepted = false)
                return@Thread
            }
            val (declinedCandidate, declinedAtWallMs) = storedDecline(primary)
            val decision = promptGate.reserve(url, declinedCandidate, declinedAtWallMs, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            // Not asked: a decline still in force is NOT_ACCEPTED, a busy gate NOT_CONFIRMED (PromptGate.refusalWhenNotAsked, the
            // same mapping and the same sentences as the iPhone app).
            if (decision == PromptGate.Decision.ASK) {
                askThenWrite(call, primary, url, candidate.id, reference?.name, page)
            } else {
                call.reject(refusal(PromptGate.refusalWhenNotAsked(decision)))
            }
        }.start()
    }

    // The decline stored for [primary]: the declined address and when it was declined (wall clock), or nulls. An entry that cannot
    // be read counts as none, which ASKS: asking is the safe default.
    private fun storedDecline(primary: String): Pair<String?, Long?> = try {
        val p = prefs()
        val at = p.getLong(EndpointRules.declinedAtKey(primary), -1L)
        Pair(p.getString(EndpointRules.declinedKey(primary), null), if (at >= 0L) at else null)
    } catch (e: Exception) {
        Pair(null, null)
    }

    // The gate said ASK (and holds the one prompt slot): show the prompt and act on how it ends, exactly once (SecureAddressPrompt).
    // Only "Use it" saves; only "Don't use it" stores a decline; every other ending refuses and stores nothing (PromptGate.effectOf).
    private fun askThenWrite(call: PluginCall, primary: String, url: String, id: String, name: String?, page: String) {
        // The cooldown starts when the prompt is actually SHOWN (as on the iPhone): one that could not be shown does not hold
        // back the next start's question.
        val prompt = SecureAddressPrompt(onShown = { promptGate.shown(SystemClock.elapsedRealtime()) }) { outcome ->
            // Cleared before the slot is released, so it can never clear a LATER prompt (none can be reserved before release).
            pendingPrompt = null
            promptGate.release()
            val effect = PromptGate.effectOf(outcome)
            when (effect) {
                PromptGate.Effect.SAVE -> writeEndpoint(call, primary, url, id, name, page, accepted = true)
                PromptGate.Effect.REMEMBER_DECLINE_AND_REFUSE -> sessionExecutor.execute {
                    // Replaces any earlier decline: one per saved server. Wall clock, so the 7 days survive a restart.
                    prefs().edit()
                        .putString(EndpointRules.declinedKey(primary), url)
                        .putLong(EndpointRules.declinedAtKey(primary), System.currentTimeMillis())
                        .apply()
                    call.reject(refusal(PromptGate.refusalAfter(effect)))
                }
                PromptGate.Effect.REFUSE -> call.reject(refusal(PromptGate.refusalAfter(effect)))
            }
        }
        try {
            pendingPrompt = prompt
            prompt.show(activity, PromptGate.promptBody(primary, url))
        } catch (e: Exception) {
            // Never leave the slot held or the web app waiting: refused, nothing stored.
            prompt.finish(PromptGate.Outcome.FAILED)
        }
    }

    // The write itself. On the session executor, like every write that changes what a saved session is bound to (the saved
    // address, the Tailscale address and its id): getSession reads them there too, so a token cannot be delivered under bindings
    // that change between its last check and its answer (Codex review of the code). Just before writing, the saved server must
    // still be [primary] and the page on screen must still have the origin the offer came from ([page]): a page that moved, or a
    // server changed, while the identity check or the prompt ran saves nothing (fails closed; the addendum review, 2026-11-09).
    // [accepted]: the person tapped "Use it", which also clears any stored decline.
    private fun writeEndpoint(call: PluginCall, primary: String, url: String, id: String, name: String?, page: String, accepted: Boolean) {
        sessionExecutor.execute {
            // ONE read of the page shown now (bounded: webViewUrlOnMainThread waits at most PAGE_CHECK_TIMEOUT_MS; unreadable is
            // null, which fails both checks below), used for the origin check and for the confirmation decision.
            val shownNow = webViewUrlOnMainThread()
            if (getSavedUrl(context) != primary || !SessionRules.sameOrigin(shownNow, page)) {
                call.reject(refusal(EndpointRules.AddCheck.NOT_THIS_PAGE))
                return@execute
            }
            val previous = getTailscaleUrl(context, primary)
            // The confirmation decision, made again here on the page shown NOW and the secure address saved NOW (Codex code review
            // of the PR #37 prompt delta: the first decision was made seconds earlier, before the identity probes).
            if (!EndpointRules.mayWriteAddress(accepted, EndpointRules.needsConfirmation(shownNow, previous, url))) {
                call.reject(refusal(EndpointRules.AddCheck.NOT_CONFIRMED))
                return@execute
            }
            val edit = prefs().edit().putString(EndpointRules.key(primary), url).putString(EndpointRules.idKey(primary), id)
            if (accepted) edit.remove(EndpointRules.declinedKey(primary)).remove(EndpointRules.declinedAtKey(primary))
            edit.apply()
            // The saved server's name, learned for free here: this runs at every start while signed in, so servers saved before
            // names were kept get theirs without any action (the page's server and the saved address are the same server).
            rememberName(primary, name)
            call.resolve(JSObject().put("added", previous != url))
        }
    }

    // removeEndpoint(): the server has said it no longer offers a secure address.
    // Same page rule as addEndpoint. Resolves { removed }.
    @PluginMethod
    fun removeEndpoint(call: PluginCall) {
        val primary = getSavedUrl(context)
        val saved = primary?.let { getTailscaleUrl(context, it) }
        if (primary == null || !EndpointRules.isActivePage(primary, saved, bridge?.serverUrl)) {
            call.reject(refusal(EndpointRules.AddCheck.NOT_THIS_PAGE))
            return
        }
        sessionExecutor.execute {
            if (saved != null) {
                forgetTailscale(prefs().edit(), primary).apply()
                // The saved session was stamped with that address. It could never be handed out without it, but if the server offers the same
                // address again later it would match again: a sign-out in between that could not reach the server would be undone
                // (agy review of the code). So it goes with the address.
                sessionVault.clear(primary)
            }
            call.resolve(JSObject().put("removed", saved != null))
        }
    }

    // ---- One sign-in across the two addresses (owner, 2026-11-06; plan phase D) ----
    //
    // The session token is also kept encrypted in app-private storage (SessionVault, an Android Keystore key), one entry per
    // SAVED address, so the page on the server's Tailscale address can sign in without asking again. The rules are in
    // SessionRules (pure, JVM-tested); this is the glue. Three things the review of the plan fixed that the glue must keep:
    //  - a server's id is PUBLIC and proves nothing, so a session is handed ONLY to the page that IS the saved Tailscale
    //    address, never to the saved address or any other page, and only while everything the entry was stamped with still
    //    matches (SessionRules.checkDelivery);
    //  - the page cannot choose an id or an address: the bindings come from what the app itself saved;
    //  - save, clear and key creation run on ONE first-in-first-out thread (sessionExecutor), so they cannot reorder or race.
    // Every call is also refused unless the page the WebView is really showing has the origin the bridge was built for
    // (SessionRules.mayAct, mayClear, isTailscalePage): the old-WebView fallback bridge can be called by any frame.

    private val sessionVault: SessionVault by lazy {
        SessionVault(PrefsKeyValueStore(context.getSharedPreferences(SessionVault.PREFS_FILE, Context.MODE_PRIVATE)), KeystoreSessionCipher())
    }

    // The saved address and the Tailscale address and id saved for it, right now; null when nothing is saved.
    private fun currentBindings(): SessionRules.Bindings? {
        val primary = getSavedUrl(context) ?: return null
        val tailscale = getTailscaleUrl(context, primary)
        return SessionRules.Bindings(primary, tailscale, if (tailscale != null) getTailscaleId(context, primary) else null)
    }

    // The URL of the page the WebView is really showing. It can only be read on the main thread (the same shape as
    // callerIsBundledPage); null when there is no bridge, no answer in time, or any error, which every rule treats as "not the
    // server's page".
    private fun webViewUrlOnMainThread(): String? {
        val b = bridge ?: return null
        val read = FutureTask(Callable { b.webView?.url })
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) read.run() else Handler(Looper.getMainLooper()).post(read)
            read.get(PAGE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
    }

    // saveSession({ token }): the web app signed in (or confirmed its sign-in) and asks the app to keep the token. From the
    // saved address or its Tailscale address only, and only from the page the WebView is really showing (SessionRules.mayAct:
    // the origin check is in the rules, so a JVM test covers it); the token must look like a session token; the entry is
    // stamped with the addresses and id in force NOW (a first sign-in comes before the Tailscale address is learned, so the
    // web app saves again after it has: an entry with no Tailscale binding is never handed out). Resolves { saved }.
    @PluginMethod
    fun saveSession(call: PluginCall) {
        val token = call.getString("token")
        val current = currentBindings()
        if (current == null || !SessionRules.mayAct(current, bridge?.serverUrl, webViewUrlOnMainThread())) {
            call.reject("Not available on this page")
            return
        }
        if (!SessionRules.isPlausibleToken(token)) {
            call.reject("Not a session token")
            return
        }
        // `tailscale` is the address this page's own server just offered (the web app read it while signed in). The stamp keeps the
        // Tailscale binding only when it equals the one saved, so an address planted by another page never gets a token
        // (SessionRules.stampFor; Opus review of the code).
        val claimed = call.getString("tailscale")
        sessionExecutor.execute {
            // The bindings are read here, on the executor, so the stamp is the state at the moment of the write.
            val now = currentBindings()
            if (now == null) {
                call.resolve(JSObject().put("saved", false))
            } else {
                call.resolve(JSObject().put("saved", sessionVault.save(SessionRules.Entry(token!!, SessionRules.stampFor(now, claimed)))))
            }
        }
    }

    // getSession(): the page asks for a saved session. Resolves { token } with the token, or { token: null } for EVERY refusal
    // (the page learns nothing about why). Only the page that is the saved Tailscale address, and is really showing it, can
    // get one (SessionRules.isTailscalePage): its own live /api/health id is read first (off the executor: a slow network must
    // not hold up saves and clears), then the entry is read and checked against the bindings as they are at that moment.
    @PluginMethod
    fun getSession(call: PluginCall) {
        val none = JSObject().put("token", JSONObject.NULL)
        val current = currentBindings()
        val page = bridge?.serverUrl
        if (page == null || !SessionRules.isTailscalePage(current, page, webViewUrlOnMainThread())) {
            call.resolve(none)
            return
        }
        Thread {
            val liveId = EndpointProbe.fetchHealth(page, 3000)?.id
            sessionExecutor.execute {
                val now = currentBindings()
                val entry = if (now != null) sessionVault.load(now.savedUrl) else null
                val verdict = SessionRules.checkDelivery(page, now, entry, liveId)
                if (verdict == SessionRules.Delivery.OK && entry != null) {
                    call.resolve(JSObject().put("token", entry.token))
                } else {
                    call.resolve(none)
                }
            }
        }.start()
    }

    // clearSession({ token? }): the web app signed out, or the server rejected a token. Needs no network. With a token, only
    // if it is the one stored (a rejection of an old token must not delete a newer login). Allowed from any page that is really
    // the server's (SessionRules.mayClear), including a Tailscale page whose address was removed or replaced while it was
    // open: a sign-out there must still delete the entry. Resolves { cleared }.
    @PluginMethod
    fun clearSession(call: PluginCall) {
        val current = currentBindings()
        if (current == null || !SessionRules.mayClear(current, bridge?.serverUrl, webViewUrlOnMainThread())) {
            call.reject("Not available on this page")
            return
        }
        val token = call.getString("token")
        sessionExecutor.execute { call.resolve(JSObject().put("cleared", sessionVault.clear(current.savedUrl, token))) }
    }

    // Confirms the address actually hosts a reachable Nightlight server (its
    // unauthenticated /api/health endpoint) before persisting anything - a typo'd
    // address that saved anyway would boot the app onto a dead URL every launch,
    // with only the error page as a way back.
    //
    // Scheme handling: an explicit https:// or http:// is respected as typed. A bare
    // address (domain, IP, IP:port) tries https first, then falls back to http -
    // self-hosted servers are commonly exposed plain-http on the LAN with no reverse
    // proxy in front (e.g. 192.168.1.50:4000), and the wrong-scheme attempt fails
    // fast (TLS handshake against a plaintext port doesn't sit out the timeout).
    //
    // expectId (optional, #20): set when the setup page connects to a server that "Look for my Nightlight" found.
    // The server at that address must then answer as a Nightlight whose id is exactly that one, or nothing is saved.
    // Why (plan review, High): the check below accepts any 200 reply containing "ok", and the address a card shows
    // may have changed hands since it was verified (a DHCP lease moving to another device). Design 7.5 step 6:
    // re-check at the moment of use. That check is HealthProbe.checkUrl, the same one discovery used, NOT the
    // HttpURLConnection below (code review of #20): HttpURLConnection follows redirects by default, so a redirect to
    // the real server could pass the id check while the ORIGINAL address was saved, and it would store any
    // Set-Cookie the answer carried through Capacitor's process-wide cookie handler. HealthProbe follows no redirect
    // (the 200 must come from that address itself), sends and stores no cookies, uses no proxy, and accepts only the
    // exact `http://<private IPv4>:<port>` form discovery produces. Without expectId (a typed address, a saved-server
    // tap) the old reachability path remains, but a private IPv4 URL with a stored id or pair id refuses a different
    // strict id before saving. Other URL forms keep the old save flow. Present but not a string is refused, not ignored,
    // so the found-server check cannot silently skip.
    @PluginMethod
    fun save(call: PluginCall) {
        val raw = call.getString("url")?.trim().orEmpty()
        if (raw.isEmpty()) {
            call.reject("Enter your server address")
            return
        }
        val rawExpectId = call.data.opt("expectId")
        if (rawExpectId != null && rawExpectId !is String) {
            call.reject(NOT_THE_FOUND_SERVER)
            return
        }
        val expectId = rawExpectId as String?
        if (expectId != null) {
            saveFoundServer(call, raw, expectId)
            return
        }
        val bare = raw.trimEnd('/')
        val candidates = if (bare.contains("://")) {
            if (!bare.startsWith("https://") && !bare.startsWith("http://")) {
                call.reject("The address must start with https:// or http://")
                return
            }
            listOf(bare)
        } else {
            listOf("https://$bare", "http://$bare")
        }

        // Plain thread rather than blocking a Capacitor thread: HttpURLConnection
        // can sit the full timeout on an unreachable host.
        Thread {
            for (url in candidates) {
                try {
                    val conn = URL("$url/api/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (code == 200 && body.contains("\"ok\"")) {
                        // A loose 200 can come from an address that changed hands. When we already know its id, check this exact
                        // private LAN URL with the cookie-free, no-redirect socket before any save or pair drop.
                        val expectedAtProbe = expectedId(context, url)
                        val liveId = if (expectedAtProbe != null) HealthProbe(totalDeadlineMs = 3000).fetchUrl(url)?.id else null
                        // The name the server sent in the same reply (null when it sent none, or an older server with no id).
                        val name = DiscoveryRules.parseHealth(body)?.name
                        // On the session executor: the saved address is part of what a saved session is bound to (see addEndpoint).
                        sessionExecutor.execute {
                            val expectedNow = expectedId(context, url)
                            if (EndpointRules.identityVerdict(expectedNow, liveId) == EndpointRules.IdentityVerdict.DIFFERENT ||
                                (expectedAtProbe == null && expectedNow != null)) {
                                // The second case closes a concurrent first learn between the loose reply and this queued write.
                                call.reject(ADDRESS_TAKEN)
                                return@execute
                            }
                            val edit = prefs().edit().putString(KEY_URL, url)
                            dropTailscaleIfAnotherServer(edit, url, liveId)
                            edit.apply()
                            rememberServer(url)
                            rememberName(url, name)
                            call.resolve(JSObject().put("url", url))
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    // Fall through to the next candidate scheme, if any.
                }
            }
            // None answered. A REMEMBERED server that has a Tailscale address can still be reached through it: this is how "Change
            // server" gets back in from cellular, where the home address cannot answer (owner, 2026-11-06). The saved address stays
            // the one the person picked; the launch gate then opens the Tailscale address (it is tried first anyway since the owner
            // decision of 2026-11-09, and here the saved one is not answering).
            // ONLY from the bundled setup page, where Change server lands: any page can call save, and this path lets a call switch the
            // active server to a remembered one it could not reach before, so a page on the current server must not have it (Codex
            // review). Such a page could already switch to a remembered server that answers; this does not widen that.
            val known = knownServers()
            val remembered = candidates.firstOrNull { it in known }
            val tailscale = remembered?.let { getTailscaleUrl(context, it) }
            if (remembered != null && tailscale != null && callerIsBundledPage()) {
                val live = EndpointProbe.fetchHealth(tailscale, 5000)
                val liveId = live?.id
                if (EndpointRules.canReconnectViaTailscale(known, remembered, tailscale, getTailscaleId(context, remembered), liveId)) {
                    sessionExecutor.execute {
                        prefs().edit().putString(KEY_URL, remembered).apply()
                        rememberServer(remembered)
                        rememberName(remembered, live?.name)
                        // Keep the legacy result value for older web pages; it names the slot, not its kind.
                        call.resolve(JSObject().put("url", remembered).put("via", "tailscale"))
                    }
                    return@Thread
                }
                call.reject("Couldn't reach a Nightlight server at that address, or through its secure address")
                return@Thread
            }
            call.reject("Couldn't reach a Nightlight server at that address")
        }.start()
    }

    // A legacy pair may still need dropping when the saved address answers with a different id. Callers first reject that
    // case when an expected id exists; this remains as the pair cleanup for any save that can proceed. The bound login goes
    // with the pair. Runs on the session executor with the editor the caller is about to apply.
    private fun dropTailscaleIfAnotherServer(edit: SharedPreferences.Editor, url: String, liveId: String?) {
        if (!EndpointRules.pairStillValid(getTailscaleId(context, url), liveId)) {
            forgetTailscale(edit, url)
            sessionVault.clear(url)
        }
    }

    // save({ url, expectId }): see the comment on save. Saved under the URL exactly as discovery reported it.
    // Unreachable gets the same words as a typed address that does not answer; any other failure (another server,
    // a redirect, not a Nightlight, not a found-server address at all) gets "not the server we found".
    private fun saveFoundServer(call: PluginCall, url: String, expectId: String) {
        Thread {
            // A card can point at a remembered address. Check its saved identity before the card's own id check, so a
            // different readable server gets the same refusal as a typed save, with no preference or list mutation.
            val expectedAtProbe = expectedId(context, url)
            if (expectedAtProbe != null && EndpointRules.identityVerdict(expectedAtProbe,
                    HealthProbe().fetchUrl(url)?.id) == EndpointRules.IdentityVerdict.DIFFERENT) {
                call.reject(ADDRESS_TAKEN)
                return@Thread
            }
            val result = HealthProbe().checkUrl(url, expectId)
            when (result.outcome) {
                HealthProbe.Outcome.VERIFIED -> sessionExecutor.execute {
                    // A found card may target a remembered URL. Its id must also agree with that URL's saved identity.
                    if (EndpointRules.identityVerdict(expectedId(context, url), expectId) == EndpointRules.IdentityVerdict.DIFFERENT) {
                        call.reject(ADDRESS_TAKEN)
                        return@execute
                    }
                    // expectId is the id the server at this address just proved to have, so it is the live id for the pair check.
                    val edit = prefs().edit().putString(KEY_URL, url)
                    dropTailscaleIfAnotherServer(edit, url, expectId)
                    edit.apply()
                    rememberServer(url)
                    rememberName(url, result.health?.name)
                    // A found card uses the strict no-redirect probe and proves this exact address's id.
                    if (getServerId(context, url) == null) prefs().edit().putString(serverIdKey(url), expectId).apply()
                    call.resolve(JSObject().put("url", url))
                }
                HealthProbe.Outcome.CONNECT_FAILED, HealthProbe.Outcome.TIMED_OUT ->
                    call.reject("Couldn't reach a Nightlight server at that address")
                else -> call.reject(NOT_THE_FOUND_SERVER)
            }
        }.start()
    }

    // Clears only the ACTIVE choice, deliberately keeping the remembered list -
    // "Change server" should land on a screen offering your other servers, not a
    // blank slate (use forget() to actually drop one from the list).
    @PluginMethod
    fun clear(call: PluginCall) {
        // The Tailscale address is KEPT (owner, 2026-11-06). It used to go with the server being left, so it could not wait around
        // for whatever was saved at that address next; but then Change server, used while on the Tailscale address (away from
        // home), threw away the only way back in: the setup page lists the saved HOME address, which cannot answer on cellular.
        // The launch gate checks a known id before opening the home address. On save, a readable different id refuses the
        // address before any pair can be dropped; a silent address keeps the pair so reconnect still works away from home.
        // forget(url), the explicit "forget this server", still removes it.
        sessionExecutor.execute {
            // Read inside the executor: a save() queued just before this one has landed by now, so the right address is cleared.
            val leaving = getSavedUrl(context)
            prefs().edit().remove(KEY_URL).apply()
            // The saved login goes: "Change server" leaves that server, and the next sign-in saves it again. (Resolved only after
            // both are done, so the restart the page asks for next starts from a clean state.)
            if (leaving != null) sessionVault.clear(leaving)
            call.resolve()
        }
    }

    // Recreating the activity rebuilds the Capacitor bridge from scratch, which is
    // what re-runs MainActivity's saved-server check - the supported way to change
    // a bridge's server URL, which is otherwise fixed for the bridge's lifetime.
    @PluginMethod
    fun restart(call: PluginCall) {
        call.resolve()
        activity?.runOnUiThread {
            AudioService.stopForServerChange(activity)
            activity.recreate()
        }
    }

    // "Look for my Nightlight" (#20). Starts a search and resolves { started, onWifi } at once; found servers
    // arrive later as serverFound events, and only after they answered the health check (ServerDiscovery). It only
    // SUGGESTS: nothing is saved or switched until the person taps a card, which goes through save({ url, expectId })
    // and restart() like any other connect (the #559 principle).
    //
    // With no Wi-Fi or Ethernet (mobile data only) nothing is started and it resolves { started: false,
    // onWifi: false }, so the page can say so (design section 10): the server cannot be heard from there.
    //
    // Refused on any page but the bundled setup page (plan decision 3a, callerIsBundledPage), and when this phone
    // has no service discovery. The page hides the whole section when the call is refused, which is also what
    // happens on iOS, where the method does not exist yet.
    @PluginMethod
    fun startDiscovery(call: PluginCall) {
        if (!callerIsBundledPage()) {
            call.reject(NOT_ON_THIS_PAGE)
            return
        }
        val d = discovery ?: ServerDiscovery(context).also { discovery = it }
        if (!d.isAvailable) {
            call.reject("Looking for servers isn't available on this phone.")
            return
        }
        val ret = JSObject()
        val onWifi = d.isOnLocalNetwork()
        ret.put("onWifi", onWifi)
        if (!onWifi) {
            d.stop()
            ret.put("started", false)
            call.resolve(ret)
            return
        }
        if (d.start(discoveryEvents) == null) {
            call.reject("Couldn't start looking for servers.")
            return
        }
        ret.put("started", true)
        call.resolve(ret)
    }

    // Gated like startDiscovery: a search is the bundled page's alone, start to finish.
    @PluginMethod
    fun stopDiscovery(call: PluginCall) {
        if (!callerIsBundledPage()) {
            call.reject(NOT_ON_THIS_PAGE)
            return
        }
        discovery?.stop()
        call.resolve()
    }

    // Capacitor calls this when the WebView starts loading ANY page (BridgeWebViewClient.onPageStarted ->
    // Bridge.reset), dropping every listener. With nobody left to tell, a search has no reason to go on: this makes
    // "leaving the page stops discovery" hold even if the page never got to call stopDiscovery (a reload, a crash
    // in its script). The page's own pagehide handler is the first line; this is the second.
    override fun removeAllListeners() {
        super.removeAllListeners()
        onPluginThread { it.stop() }
    }

    // The app went to the background: stop looking, so nothing keeps probing the network while nobody can see the
    // results. The page shows "Search again" when it is visible again.
    override fun handleOnStop() {
        onPluginThread { it.stop() }
        super.handleOnStop()
    }

    // Released on the plugin thread too, after anything already queued there. Bridge.onDestroy calls this and then
    // quitSafely() on that thread, which still runs every message already due, so the release does run.
    override fun handleOnDestroy() {
        val d = discovery
        discovery = null
        if (d != null) onPluginThread(d) { it.release() }
        super.handleOnDestroy()
    }

    // The three lifecycle callers above run on the MAIN thread, and stop()/release() take ServerDiscovery's lock,
    // which the plugin thread holds across NsdManager binder calls inside start() (second review of #20: a slow
    // binder call there would stall the main thread, an ANR risk). So the work is handed to Capacitor's plugin
    // thread: Bridge.execute posts to the same single "CapacitorPlugins" HandlerThread that runs every plugin call
    // (Bridge.java: callPluginMethod and execute both post to taskHandler), which never blocks the main thread and
    // keeps these stops in order with the page's own startDiscovery/stopDiscovery calls. Without a bridge (never
    // expected) a short-lived thread does it. Glue: no JVM test reaches this.
    private fun onPluginThread(target: ServerDiscovery? = discovery, action: (ServerDiscovery) -> Unit) {
        val d = target ?: return
        val b = bridge
        if (b != null) {
            b.execute { action(d) }
        } else {
            Thread({ action(d) }, "nightlight-discovery-lifecycle").start()
        }
    }
}
