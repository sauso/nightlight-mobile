package com.sauso.nightlight

import android.app.Activity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Android system-bar control for fullscreen camera tiles. The edge-to-edge WebView
 * leaves the status and navigation bars over the video, where they cover its buttons.
 * From the web app:
 *
 *   Capacitor.Plugins.Immersive.setEnabled({ enabled: true })  // enter fullscreen
 *   Capacitor.Plugins.Immersive.setEnabled({ enabled: false }) // leave fullscreen
 */
@CapacitorPlugin(name = "Immersive")
class ImmersivePlugin : Plugin() {

    companion object {
        // What the web app last asked for. Kept apart from what is currently applied because
        // Picture-in-Picture must show the bars while the app floats, yet a tile that is still
        // fullscreen when the person expands the PiP window needs them hidden again - the web
        // app only calls setEnabled on a fullscreen CHANGE, so nothing else would re-hide them
        // (review of the first version of this plugin).
        @Volatile
        var wanted = false
            private set

        // Must run on the UI thread.
        fun apply(activity: Activity, hide: Boolean) {
            val window = activity.window
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (hide) {
                // The camera controls need clear edges, but a swipe must still reveal system navigation.
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        // Called from MainActivity.onPictureInPictureModeChanged. Entering PiP shows the bars
        // (the web exit-fullscreen handler would otherwise run after the window already shrank);
        // leaving it puts them back if the web app still wants them hidden.
        fun onPipModeChanged(activity: Activity, isInPip: Boolean) {
            apply(activity, hide = !isInPip && wanted)
        }
    }

    @PluginMethod
    fun setEnabled(call: PluginCall) {
        val hostActivity = activity ?: run {
            call.resolve()
            return
        }
        val enabled = call.getBoolean("enabled", false) ?: false
        wanted = enabled
        hostActivity.runOnUiThread {
            apply(hostActivity, hide = enabled)
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        // A disappearing WebView must not leave the next activity view without system navigation.
        wanted = false
        val hostActivity = activity
        hostActivity?.runOnUiThread { apply(hostActivity, hide = false) }
        super.handleOnDestroy()
    }
}
