package com.sauso.nightlight

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
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

    // Tell the web app when we enter/leave PiP so it can hide the on-video overlay buttons
    // (mute/settings/fullscreen) that otherwise clutter the tiny floating window.
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipPlugin.notifyPipModeChanged(isInPictureInPictureMode)

        // Leaving PiP: Chromium's WebView sometimes keeps the page scale it computed for the
        // tiny PiP window even after the window returns to full size, leaving the whole UI
        // zoomed in until the app is restarted. Toggling useWideViewPort forces it to recompute
        // its layout viewport (and page scale) for the current window size - but ONLY if we do
        // it AFTER the window has actually grown back to full. The window resizes asynchronously
        // with variable timing, so a fixed delay hit it only sometimes (the "random" zoom). So
        // instead we recompute in reaction to the real resize: once immediately (covers the case
        // where it already grew), and again on the next layout where the WebView gets wider
        // (covers the case where it grows a moment later), then stop listening.
        if (!isInPictureInPictureMode) {
            bridge?.webView?.let { webView ->
                fun forceScaleRecompute() {
                    val settings = webView.settings
                    val wide = settings.useWideViewPort
                    settings.useWideViewPort = !wide
                    settings.useWideViewPort = wide
                    webView.requestLayout()
                    webView.invalidate()
                }
                webView.post { forceScaleRecompute() }
                val listener = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        if ((right - left) > (oldRight - oldLeft)) {
                            v.removeOnLayoutChangeListener(this)
                            forceScaleRecompute()
                        }
                    }
                }
                webView.addOnLayoutChangeListener(listener)
                // Safety net: if no growth layout ever arrives, stop listening so we don't leak.
                webView.postDelayed({ webView.removeOnLayoutChangeListener(listener) }, 3000)
            }
        }
    }
}
