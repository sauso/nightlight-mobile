package com.sauso.nightlight

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebViewClient
import com.getcapacitor.CapConfig

class MainActivity : BridgeActivity() {

    // When true, leaving the app (Home button / app switcher) auto-enters Picture-in-
    // Picture. The web app turns this on only while the live camera view is showing (see
    // nativeBridge.js setAutoPictureInPicture), so pressing Home from, say, the Settings
    // page just backgrounds normally instead of floating a settings screen.
    var autoPipEnabled = false

    // Re-chooses the address when the network changes (NetworkRoamWatcher). Made in onCreate, started in onStart, stopped in
    // onStop: it only runs while the activity is started.
    private var roamWatcher: NetworkRoamWatcher? = null

    companion object {
        // Every recreate() this app makes for its own reasons: a main-frame load error (below, which falls back to the setup
        // page) and a move to the server's other address (NetworkRoamWatcher). It lives in the companion so it SURVIVES the
        // recreate it records, and ONE log serves both so they cannot add up to a recreate loop (Codex review of the roaming
        // plan): the load-error path keeps its 8 s debounce, now measured from any recreate; a roam needs 20 s since any
        // recreate and at most 3 roams in 5 minutes (RecreateLog, tested in RoamRulesTest).
        val recreateLog = RecreateLog()

        // The clock recreateLog is read with, by both callers. Time since boot rather than the wall clock (which the old
        // debounce used): it never jumps, so a clock set back cannot hold the recreates off, or set forward let them repeat.
        fun recreateClockMs(): Long = SystemClock.elapsedRealtime()

        // The one shape a server request from a link may have (#559): http(s), a host or bracketed IPv6
        // literal, an optional port. Mirrors REQUEST_ORIGIN_SHAPE in the web app's nativeBridge.js.
        private val REQUEST_ORIGIN = Regex("^https?://(?:[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+\\])(?::\\d{1,5})?$")

        /**
         * Which of the server's addresses to open: [tailscale] (when one is saved) if it answers with the id it was saved for,
         * else [saved] if it answers, else null. Tailscale first, the saved address as the fallback (owner decision
         * 2026-11-09): see EndpointChooser for why and for the exact rule. With no Tailscale address saved, only [saved] is
         * probed and nothing waits for a second address, as before.
         * The launch gate (onCreate) and the network watcher (NetworkRoamWatcher) both call THIS, so the address the app
         * moves to when the network changes is exactly the one a fresh launch would open. Blocks for up to
         * EndpointChooser.DEADLINE_MS: never call it on the main thread except from the launch gate, which has always waited
         * here (the splash screen is showing).
         *
         * Both are probed together under one 3 s deadline (EndpointChooser), so a launch waits once, not twice.
         */
        fun chooseAddress(context: Context, saved: String, tailscale: String?): String? {
            // The Tailscale address only counts if the server answering there still has the id it was saved for, so an address
            // saved for one server can never open another (a saved address handed to a different machine, say). The rule is
            // EndpointRules.addressAnswers, shared with the network watcher's second look so the two cannot drift.
            val wantedId = ServerConfigPlugin.getTailscaleId(context, saved)
            val probe = { url: String ->
                EndpointRules.addressAnswers(
                    url,
                    tailscale,
                    wantedId,
                    fetchId = { EndpointProbe.fetchHealth(it, 2500)?.id },
                    reachable = { ServerConfigPlugin.isReachable(it, 2500) },
                )
            }
            // How long a saved answer waits for Tailscale: 1 s, or a quarter second when the phone positively has no VPN, so a
            // Tailscale that is switched off costs a launch at most that (EndpointRules.tailscaleGraceMs). VpnPresent fails
            // open ("present", the full second): the reading can only shorten the wait, never open a wrong address.
            val grace = EndpointRules.tailscaleGraceMs(VpnPresent.isOn(context))
            return EndpointChooser(probe, tailscaleGraceMs = grace).choose(saved, tailscale)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be registered before super.onCreate so the bridge picks them up.
        registerPlugin(BackgroundAudioPlugin::class.java)
        registerPlugin(ServerConfigPlugin::class.java)
        registerPlugin(PipPlugin::class.java)
        registerPlugin(ImmersivePlugin::class.java)
        registerPlugin(FirebaseInitPlugin::class.java)
        registerPlugin(DownloadPlugin::class.java)

        // Which server to load is decided here at launch (like the Home Assistant
        // app), not baked into capacitor.config.json. No saved address -> config is
        // left untouched, so the bundled first-run setup page (www/) loads. Saved
        // address -> point the bridge at it. errorPath is served from the local
        // asset server regardless of the remote URL (see Bridge.getErrorUrl), so an
        // unreachable server lands on a bundled page with retry/change-server
        // options instead of stranding the app on a WebView error.
        // A launch via a nightlight:// deep link (a tapped Pushover alert) may name the server the alert
        // came from (?server=…). That is only ever a REQUEST (#559): it is held for the web app, which asks
        // the person and only offers servers already saved on this phone. Nothing is saved or switched
        // here, and nothing contacts the named address. (The FCM path goes through the same web-side
        // check; see pushNotifications.js. The warm-app case is handled in onNewIntent.)
        recordDeepLinkServer(intent)

        // Only point the bridge at the saved server if it's actually reachable right now. A quick
        // health check (off the main thread, joined with a short timeout — the splash screen is
        // showing) means an offline/stopped server drops us straight to the bundled setup page —
        // a local Capacitor page where the bridge works, so the user can retry or switch servers —
        // instead of stranding the WebView on a long connection timeout (a blank screen). If reachable,
        // load it; a server that then dies MID-session is caught by the errorPath page (see below).
        //
        // A server can have a second address: the Tailscale one it told this phone about (ServerConfig.addEndpoint). Tailscale
        // first, the saved address is the fallback (owner decision 2026-11-09, reversing the local-first rule of 2026-11-06: on a
        // hotspot the plain-http saved address still answered through the tailnet, so the secure address, which two-way talk
        // needs, was never used). At home the phone now goes through the server's Tailscale node when Tailscale is on, and falls
        // back to the saved address when it does not answer, so home use does not depend on it. The choice itself is
        // chooseAddress (companion), shared with NetworkRoamWatcher, which makes the same choice again when the network changes
        // and recreates the activity to land here if RoamRules allows the move.
        // The SAVED address is never rewritten by this: only the address the bridge opens differs.
        val savedUrl = ServerConfigPlugin.getSavedUrl(this)
        if (savedUrl != null) {
            val chosen = chooseAddress(this, savedUrl, ServerConfigPlugin.getTailscaleUrl(this, savedUrl))
            if (chosen != null) {
                config = CapConfig.Builder(this)
                    .setServerUrl(chosen)
                    .create()
            }
            // else: leave config untouched so the bundled setup page (www/index.html) loads — a local
            // Capacitor page with a working native bridge, so Connect / saved-server taps actually work.
            // (We prefer this over the errorPath page at COLD START because the setup page also lists
            // saved servers.)
        }

        super.onCreate(savedInstanceState)

        // Capacitor's server.errorPath only catches load failures for the BUNDLED local app — NOT for a
        // remote server.url (like an HTTPS domain). So a saved remote server that goes unreachable while
        // the app is open (network dropped, or the WebView reloads on resume with no connection) would
        // strand the WebView on the system "webpage not available" page, recoverable only by force-quitting.
        // Wrap the bridge's WebView client: on a main-frame load error, re-run onCreate via recreate(),
        // whose reachability gate (above) drops an unreachable server to the bundled setup page — a LOCAL
        // page where the native bridge works and the saved server is one tap away to retry. Debounced via the
        // process-lived recreateLog (shared with the network watcher's recreates) so a reachable-but-flaky load can't
        // recreate-loop; the setup page is a local load, so it never re-triggers this itself.
        bridge.webView.webViewClient = object : BridgeWebViewClient(bridge) {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val now = recreateClockMs()
                    if (recreateLog.mayErrorRecreate(now)) {
                        recreateLog.noteRecreate(now)
                        view.post { recreate() }
                        return
                    }
                }
                super.onReceivedError(view, request, error)
            }
        }

        roamWatcher = NetworkRoamWatcher(this)
    }

    // The network watcher runs only while the activity is started: it needs the page on screen, and a check while the app is
    // in the background could recreate an activity nobody is looking at. Starting it also checks the address once (the
    // FOREGROUND_START check), which is how an app that roamed while in the background, or came home, re-chooses when it is
    // shown again. Not started without a saved server: the bundled first-run page has no address to move between.
    override fun onStart() {
        super.onStart()
        if (ServerConfigPlugin.getSavedUrl(this) != null) roamWatcher?.start()
    }

    // Stopped BEFORE the activity is: anything still queued or running is dead on arrival from here on. onStop also runs before
    // every recreate(), so a replaced activity's watcher can never recreate its successor.
    override fun onStop() {
        roamWatcher?.stop()
        super.onStop()
    }

    // In case onStop did not run first. stop() is safe to call twice.
    override fun onDestroy() {
        roamWatcher?.stop()
        super.onDestroy()
    }

    // The URL of the page the WebView is really showing (not the bridge's configured server, which stays the remote address
    // while a bundled error page is on screen). Main thread only: NetworkRoamWatcher reads it through the main thread.
    fun shownPageUrl(): String? = bridge?.webView?.url

    // Hardware back / the OS edge back-gesture is handled by the @capacitor/app plugin, which
    // dispatches a `backButton` event to the web app (see useHardwareBack in the frontend). That's
    // the reliable path: Android's WebView doesn't track the single-page hash-router's history, so a
    // native webView.canGoBack()/goBack() can't walk our in-app screens — but react-router's own
    // history can, in JS. No onBackPressed override here on purpose.

    // The single delivery point for launch intents. Capacitor's BridgeActivity.load() (invoked from
    // super.onCreate above) calls this with getIntent(), and it's also called by the OS for a deep
    // link/notification tapped while the app is already running (singleTask). super.onNewIntent hands
    // the intent to the Capacitor bridge, which is where the push plugin fires
    // pushNotificationActionPerformed for an FCM tap.
    //
    // The catch: getIntent() keeps returning the *original* launch intent across every recreate(). So
    // a one-shot intent — a Pushover nightlight:// deep link (?server=) or an FCM notification tap
    // (google.message_id) — would be re-delivered on each recreate(), re-applying its server. That's
    // exactly what fights a manual "change server": clearing the saved URL and recreate()ing re-runs
    // load() -> onNewIntent(getIntent()), which re-fires the original server and loops the app back.
    // So once the bridge has delivered a consumable intent, we replace the stored launch intent with
    // a neutral one; later recreate()s then start clean. (Neutralising happens AFTER super.onNewIntent,
    // so the genuine first delivery is unaffected.)
    //
    // A deep link naming a server no longer switches or recreates anything (#559): it is held as a
    // request, and the web app is told with a window event so it can ask while it is already open. A
    // cold start fires the event before the page exists, so the page also asks once when it loads.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val recorded = recordDeepLinkServer(intent)
        if (isConsumableLaunchIntent(intent)) {
            setIntent(Intent(Intent.ACTION_MAIN))
        }
        if (recorded) bridge?.triggerWindowJSEvent("nightlightServerRequest")
    }

    // A launch intent that should fire once and then be forgotten: a Pushover nightlight:// deep link,
    // or an FCM notification tap (Capacitor keys these off the google.message_id extra).
    private fun isConsumableLaunchIntent(intent: Intent): Boolean {
        if (intent.action == Intent.ACTION_VIEW && intent.scheme == "nightlight") return true
        return intent.extras?.containsKey("google.message_id") == true
    }

    // If the intent is a nightlight:// deep link carrying a ?server=<origin>, hold it as a pending request
    // for the web app (ServerConfig.takePending) and return true. Returns false for anything else.
    //
    // This used to save the named server as the active one if it answered a health check, with no question
    // asked (#559). Now it only HOLDS the request: it never saves, never switches, never recreates, and
    // contacts nothing (the old health probe made this phone fetch an address that came from outside the
    // app, on the main thread). The web app asks, and switches only to a server already saved on the phone.
    //
    // Only a plain origin is held: http(s), a host or bracketed IPv6 literal, an optional port, nothing
    // else (no credentials, path, query or fragment), at most 255 characters. The web side checks it
    // again with the same pattern; this keeps junk out of the slot.
    //
    // Newest link wins (review of #559): a nightlight:// link that carries no usable server clears what an
    // earlier link left waiting, so the page never asks about a request the person has already moved past.
    // A launch from the Recents screen is NOT a new link: after the process was killed, Android hands the
    // original launch intent back with FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, and recording it again would ask
    // about a request from an earlier session.
    private fun recordDeepLinkServer(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        if (data.scheme != "nightlight") return false
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        // An opaque URI (`nightlight:foo?server=…`, no `//`) makes getQueryParameter THROW, which used to kill
        // the app from a single tapped link, background listening included. It carries no request.
        val server = if (data.isOpaque) null else data.getQueryParameter("server")?.trim()?.trimEnd('/')
        if (server.isNullOrEmpty() || server.length > 255 || !REQUEST_ORIGIN.matches(server)) {
            ServerConfigPlugin.clearPending()
            return false
        }
        ServerConfigPlugin.setPending(server)
        return true
    }

    // PiP needs API 26+ (the params-based enter) and hardware/OS support - some cheap or
    // heavily-customised devices report no PiP feature at all.
    fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    // Float the whole app window. Activity PiP can only show what the window is currently
    // rendering (the WebView), so whatever camera view is on screen is what floats - it
    // can't isolate a single <video> the way the browser's element PiP does. Returns
    // whether PiP actually started.
    fun enterPipMode(): Boolean {
        if (!isPipSupported()) return false
        return try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } catch (e: Exception) {
            // e.g. the activity isn't in a state that allows entering PiP right now.
            false
        }
    }

    // Home / Recents while watching -> float automatically (opt-in via autoPipEnabled).
    override fun onUserLeaveHint() {
        if (autoPipEnabled) enterPipMode()
        super.onUserLeaveHint()
    }

    // Tell the web app when we enter/leave PiP so it can hide the on-video overlay buttons
    // (mute/settings/fullscreen) that otherwise clutter the tiny floating window.
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ImmersivePlugin.onPipModeChanged(this, isInPictureInPictureMode)
        PipPlugin.notifyPipModeChanged(isInPictureInPictureMode)
    }
}
