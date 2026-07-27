package com.sauso.nightlight

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Picture-in-Picture for the Android app. The web <video> Picture-in-Picture API
 * (video.requestPictureInPicture) is not supported in Android's WebView, so the web app's
 * PiP button does nothing on Android by itself. Android's equivalent is Activity PiP - the
 * whole app window floats into a small always-on-top window - which only native code can
 * invoke. From the web app (see nightlight/frontend/src/lib/nativeBridge.js):
 *
 *   Capacitor.Plugins.Pip.enter()                  // float the app now (the PiP button)
 *   Capacitor.Plugins.Pip.setAutoEnter({ enabled }) // also float automatically on Home
 *   Capacitor.Plugins.Pip.isSupported()            // whether this device/OS can PiP
 *
 * The actual work lives on MainActivity (which owns the window and the auto-enter flag
 * that onUserLeaveHint reads); this plugin is just the JS-facing surface.
 */
@CapacitorPlugin(name = "Pip")
class PipPlugin : Plugin() {

    companion object {
        // The Activity's onPictureInPictureModeChanged needs a way to reach the live
        // plugin instance so the web app can hide the on-video overlay buttons (mute,
        // settings, fullscreen) while floating - they just waste space in the tiny window.
        @Volatile
        private var instance: PipPlugin? = null

        fun notifyPipModeChanged(isInPip: Boolean) {
            instance?.notifyListeners("pipModeChanged", JSObject().put("isInPip", isInPip))
        }
    }

    override fun load() {
        instance = this
    }

    override fun handleOnDestroy() {
        if (instance === this) instance = null
        super.handleOnDestroy()
    }

    @PluginMethod
    fun isSupported(call: PluginCall) {
        val ret = JSObject()
        ret.put("supported", (activity as? MainActivity)?.isPipSupported() ?: false)
        call.resolve(ret)
    }

    @PluginMethod
    fun enter(call: PluginCall) {
        val ret = JSObject()
        ret.put("entered", (activity as? MainActivity)?.enterPipMode() ?: false)
        call.resolve(ret)
    }

    @PluginMethod
    fun setAutoEnter(call: PluginCall) {
        (activity as? MainActivity)?.autoPipEnabled = call.getBoolean("enabled", false) ?: false
        call.resolve()
    }
}
