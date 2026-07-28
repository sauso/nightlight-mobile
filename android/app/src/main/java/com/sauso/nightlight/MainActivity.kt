package com.sauso.nightlight

import android.app.PictureInPictureParams
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

    // Whether we're currently in PiP. The WebView-scale recompute below must only run once the
    // window is back to full size, never against the tiny PiP window.
    private var inPip = false

    // Set while we're deliberately resizing the WebView to force a scale recompute, so the
    // layout watcher ignores the resulting layout changes and doesn't reschedule itself forever.
    private var recomputing = false

    // Force Chromium's WebView to recompute its page scale for the real full-window size, fixing
    // the whole-app stuck-zoom after leaving PiP. A useWideViewPort toggle alone wasn't enough
    // once the PiP window had been tapped (the touch commits the tiny-window scale), so we also
    // apply a genuine 1px width change and restore it - a real onSizeChanged is what reliably
    // makes the renderer recompute the scale from scratch.
    private val recomputeWebViewScale = Runnable {
        val webView = bridge?.webView ?: return@Runnable
        recomputing = true
        val settings = webView.settings
        val wide = settings.useWideViewPort
        settings.useWideViewPort = !wide
        settings.useWideViewPort = wide
        val params = webView.layoutParams
        val fullWidth = params.width
        params.width = webView.width - 1
        webView.layoutParams = params
        webView.post {
            params.width = fullWidth
            webView.layoutParams = params
            webView.requestLayout()
            webView.invalidate()
            // Let the restore's layout pass flush before re-arming the watcher.
            webView.postDelayed({ recomputing = false }, 150)
        }
    }

    // Debounced: run the recompute only once the layout has stopped changing for a beat. This is
    // what makes rapid PiP enter/exit (several quick button presses) safe - each resize just
    // resets the timer, so the recompute fires once against the final settled size instead of
    // against an intermediate size mid-transition (which is when the zoom still slipped through).
    private fun scheduleWebViewScaleRecompute() {
        bridge?.webView?.let { webView ->
            webView.removeCallbacks(recomputeWebViewScale)
            webView.postDelayed(recomputeWebViewScale, 250)
        }
    }

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

        // Watch for the window resizing back to full after PiP and re-assert the WebView page
        // scale (see recomputeWebViewScale). A width change while not in PiP is the signal;
        // debounced so any burst of resizes settles into a single recompute at the final size.
        bridge?.webView?.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (!inPip && !recomputing && (right - left) != (oldRight - oldLeft)) {
                scheduleWebViewScaleRecompute()
            }
        }
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
        inPip = isInPictureInPictureMode
        PipPlugin.notifyPipModeChanged(isInPictureInPictureMode)

        // Leaving PiP: also nudge a (debounced) recompute directly, in case the resize back to
        // full already completed before this callback and so produces no further layout change
        // for the watcher above to react to. The layout watcher handles the (common) case where
        // the resize arrives afterwards.
        if (!isInPictureInPictureMode) {
            scheduleWebViewScaleRecompute()
        }
    }
}
