package com.sauso.nightlight

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import com.getcapacitor.BridgeActivity
import com.getcapacitor.CapConfig

class MainActivity : BridgeActivity() {

    // When true, leaving the app (Home button / app switcher) auto-enters Picture-in-
    // Picture. The web app turns this on only while the live camera view is showing (see
    // nativeBridge.js setAutoPictureInPicture), so pressing Home from, say, the Settings
    // page just backgrounds normally instead of floating a settings screen.
    var autoPipEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be registered before super.onCreate so the bridge picks them up.
        registerPlugin(BackgroundAudioPlugin::class.java)
        registerPlugin(ServerConfigPlugin::class.java)
        registerPlugin(PipPlugin::class.java)
        registerPlugin(FirebaseInitPlugin::class.java)

        // Which server to load is decided here at launch (like the Home Assistant
        // app), not baked into capacitor.config.json. No saved address -> config is
        // left untouched, so the bundled first-run setup page (www/) loads. Saved
        // address -> point the bridge at it. errorPath is served from the local
        // asset server regardless of the remote URL (see Bridge.getErrorUrl), so an
        // unreachable server lands on a bundled page with retry/change-server
        // options instead of stranding the app on a WebView error.
        // A launch via a nightlight:// deep link (a tapped Pushover alert) may name the server the
        // alert came from (?server=…). If that's a different, reachable server than the saved one,
        // persist it now — before we read the saved URL below — so a prod alert tapped while the app
        // was last on dev cold-starts straight into prod. (The FCM path does the same switch from
        // JS; see pushNotifications.js. The warm-app case is handled in onNewIntent.)
        handleDeepLinkServer(intent)

        // Only point the bridge at the saved server if it's actually reachable right now. A quick
        // health check (off the main thread, joined with a short timeout — the splash screen is
        // showing) means an offline/stopped server drops us straight to the bundled setup page —
        // a local Capacitor page where the bridge works, so the user can retry or switch servers —
        // instead of stranding the WebView on a long connection timeout (a blank screen), or on the
        // errorPath page where the native bridge isn't reliably injected. If reachable, load it and
        // keep errorPath as a fallback for a server that dies mid-session.
        val savedUrl = ServerConfigPlugin.getSavedUrl(this)
        if (savedUrl != null) {
            val reachable = booleanArrayOf(false)
            Thread { reachable[0] = ServerConfigPlugin.isReachable(savedUrl, 2500) }.apply {
                start()
                join(3000)
            }
            if (reachable[0]) {
                config = CapConfig.Builder(this)
                    .setServerUrl(savedUrl)
                    .create()
            }
            // else: leave config untouched so the bundled setup page (www/index.html) loads —
            // a local Capacitor page with a working native bridge, so Connect / saved-server taps
            // actually work. (The old error.html fallback is gone: Capacitor doesn't inject the
            // bridge into an errorPath page, so its buttons could never work.)
        }

        super.onCreate(savedInstanceState)

        // A cold-start deep link is one-shot, but the activity keeps returning this VIEW intent from
        // getIntent() across recreate(). Without consuming it, switching servers — which clears the
        // saved URL and recreate()s the activity — would re-run onCreate, re-read ?server= from the
        // stale intent, and bounce straight back to the deep link's server (never reaching the setup
        // page). So once we've handled it, replace the launch intent with a neutral one; a later
        // recreate() then starts clean. (Done after super.onCreate so Capacitor still sees the
        // original intent on this first launch.)
        if (intent?.action == Intent.ACTION_VIEW && intent.scheme == "nightlight") {
            setIntent(Intent(Intent.ACTION_MAIN))
        }
    }

    // The app is singleTask, so a deep link tapped while it's already running is delivered here
    // rather than starting a fresh activity. If the link names a different, reachable server than
    // the one currently loaded, switch to it (persist + recreate, which re-runs onCreate and
    // rebuilds the bridge against the new server). A same-server link needs nothing — singleTask has
    // already brought the app to the front on the nursery it was showing.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleDeepLinkServer(intent)) {
            recreate()
        }
    }

    // If the intent is a nightlight:// deep link carrying a ?server=<url> that differs from the
    // saved server and is reachable right now, persist it as the active server and return true.
    // Returns false (changing nothing) for a non-deep-link intent, no server param, the same server
    // we're already on, or an unreachable address — in which case the app just loads/keeps the
    // current server. The reachability check runs off the main thread but is joined with a short
    // timeout: the splash screen is up, and we must decide before onCreate builds the bridge config.
    private fun handleDeepLinkServer(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        if (data.scheme != "nightlight") return false
        val server = data.getQueryParameter("server")?.trim()?.trimEnd('/')
        if (server.isNullOrEmpty()) return false
        if (!server.startsWith("http://") && !server.startsWith("https://")) return false
        if (server == ServerConfigPlugin.getSavedUrl(this)) return false
        val reachable = booleanArrayOf(false)
        Thread { reachable[0] = ServerConfigPlugin.isReachable(server, 2500) }.apply {
            start()
            join(3000)
        }
        if (!reachable[0]) return false
        ServerConfigPlugin.saveUrl(this, server)
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
        PipPlugin.notifyPipModeChanged(isInPictureInPictureMode)
    }
}
