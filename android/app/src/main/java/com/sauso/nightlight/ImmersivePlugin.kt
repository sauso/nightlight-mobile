package com.sauso.nightlight

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

    @PluginMethod
    fun setEnabled(call: PluginCall) {
        val hostActivity = activity ?: run {
            call.resolve()
            return
        }
        val enabled = call.getBoolean("enabled", false) ?: false
        hostActivity.runOnUiThread {
            val window = hostActivity.window
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (enabled) {
                // The camera controls need clear edges, but a swipe must still reveal system navigation.
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        // A disappearing WebView must not leave the next activity view without system navigation.
        val hostActivity = activity
        hostActivity?.runOnUiThread {
            val window = hostActivity.window
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        super.handleOnDestroy()
    }
}
