package com.sauso.nightlight

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Runtime Firebase initialization for push notifications.
 *
 * Nightlight is self-hosted, so the distributed APK is generic - it has NO google-services.json
 * baked in. Instead each install uses its own Firebase project: the web layer fetches the FCM
 * client config from its OWN server (GET /api/push/config, populated from the admin's
 * google-services.json) and calls, before registering for push:
 *
 *   Capacitor.Plugins.FirebaseInit.initialize({ appId, apiKey, projectId, senderId })
 *
 * This initializes the default FirebaseApp with those options so @capacitor/push-notifications can
 * register against the admin's project. Idempotent - safe to call on every launch.
 */
@CapacitorPlugin(name = "FirebaseInit")
class FirebaseInitPlugin : Plugin() {

    @PluginMethod
    fun initialize(call: PluginCall) {
        // Already initialized this process (or a baked google-services.json auto-initialized it) —
        // nothing to do.
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            call.resolve(JSObject().put("initialized", true))
            return
        }

        val appId = call.getString("appId")
        val apiKey = call.getString("apiKey")
        val projectId = call.getString("projectId")
        val senderId = call.getString("senderId")
        if (appId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            call.reject("appId and apiKey are required")
            return
        }

        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .build()
            FirebaseApp.initializeApp(context, options)
            call.resolve(JSObject().put("initialized", true))
        } catch (e: Exception) {
            call.reject("Firebase init failed: ${e.message}")
        }
    }
}
