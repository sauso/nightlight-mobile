package com.sauso.nightlight

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
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

        // Which server to load is decided here at launch (like the Home Assistant
        // app), not baked into capacitor.config.json. No saved address -> config is
        // left untouched, so the bundled first-run setup page (www/) loads. Saved
        // address -> point the bridge at it. errorPath is served from the local
        // asset server regardless of the remote URL (see Bridge.getErrorUrl), so an
        // unreachable server lands on a bundled page with retry/change-server
        // options instead of stranding the app on a WebView error.
        val savedUrl = ServerConfigPlugin.getSavedUrl(this)
        if (savedUrl != null) {
            config = CapConfig.Builder(this)
                .setServerUrl(savedUrl)
                .setErrorPath("error.html")
                .create()
        }

        super.onCreate(savedInstanceState)
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
}
