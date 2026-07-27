package com.sauso.nightlight

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * JS-facing plugin. From the web app:
 *
 *   Capacitor.Plugins.BackgroundAudio.start({ label: "Nursery" })
 *   Capacitor.Plugins.BackgroundAudio.stop()
 *   Capacitor.Plugins.BackgroundAudio.addListener("stopped", ...)
 *
 * start() while already running just retitles the notification.
 */
@CapacitorPlugin(
    name = "BackgroundAudio",
    permissions = [
        Permission(
            strings = [Manifest.permission.POST_NOTIFICATIONS],
            alias = "notifications"
        )
    ]
)
class BackgroundAudioPlugin : Plugin() {

    companion object {
        // The service needs a way to reach whichever plugin instance is live so
        // "Stop" on the notification can be reflected in the web UI.
        @Volatile
        private var instance: BackgroundAudioPlugin? = null

        fun notifyStopped() {
            instance?.notifyListeners("stopped", JSObject())
        }

        // Fired when Pause/Resume is tapped on the notification, so the web app can
        // mute/unmute the stream audio to match.
        fun notifyPauseChanged(paused: Boolean) {
            instance?.notifyListeners("pauseChanged", JSObject().put("paused", paused))
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
    fun start(call: PluginCall) {
        // Android 13+ needs runtime consent to show the foreground-service
        // notification. The service starts either way (denying just hides the
        // notification into the FGS task manager), but asking once is polite
        // and gives the person the Stop button.
        if (Build.VERSION.SDK_INT >= 33 &&
            getPermissionState("notifications") != com.getcapacitor.PermissionState.GRANTED &&
            !call.getBoolean("skipPermissionPrompt", false)!!
        ) {
            requestPermissionForAlias("notifications", call, "onNotificationPermission")
            return
        }
        startService(call)
    }

    @PermissionCallback
    private fun onNotificationPermission(call: PluginCall) {
        // Granted or denied, we still start - background audio works regardless.
        startService(call)
    }

    private fun startService(call: PluginCall) {
        val label = call.getString("label") ?: "camera"
        val intent = Intent(context, AudioService::class.java).apply {
            action = AudioService.ACTION_START
            putExtra(AudioService.EXTRA_LABEL, label)
        }
        ContextCompat.startForegroundService(context, intent)
        maybeRequestBatteryExemption()
        call.resolve()
    }

    // Doze (screen off, device still for ~15-30 min) ignores wake locks and cuts
    // network for apps that aren't exempt from battery optimization - which is
    // precisely how an active background-listening session dies half an hour into
    // the night despite the foreground service. Exempt apps keep both. Asked at
    // most once via the system's own consent dialog; declining is respected and
    // never re-prompted (it can still be granted later in Settings > Apps >
    // Nightlight > Battery > Unrestricted).
    private fun maybeRequestBatteryExemption() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        val prefs = context.getSharedPreferences("nightlight_battery", Context.MODE_PRIVATE)
        if (prefs.getBoolean("exemption_asked", false)) return
        prefs.edit().putBoolean("exemption_asked", true).apply()
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Some OEM builds don't ship this settings screen - background listening
            // still works, just without the Doze exemption.
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        context.stopService(Intent(context, AudioService::class.java))
        call.resolve()
    }
}
