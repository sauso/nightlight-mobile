package com.sauso.nightlight

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Is this device a television (Android TV, Google TV, Fire TV)?
 *
 * One APK serves phone and TV (the manifest declares touchscreen, leanback and microphone as not required), so the app
 * has to find out at runtime. The web UI asks through ServerConfig.isTelevision() and shows its TV screen; the native
 * side uses the same answer to skip prompts that make no sense on a TV (BackgroundAudioPlugin).
 *
 * The decision is pure so a JVM test covers it without Android: [uiModeType] is Configuration.uiMode's type
 * (UiModeManager.currentModeType) passed in as a plain Int.
 */
object TvDetect {

    // Configuration.UI_MODE_TYPE_TELEVISION (documented as 4, a stable platform value). Kept as the literal so the
    // decision, and its JVM test, needs no Android class.
    const val UI_MODE_TYPE_TELEVISION = 4

    /**
     * True when the UI mode type is television OR the leanback feature is present. BOTH signals, not one: Android's own
     * guidance is the UI mode, but some Fire TV models and cheap set-top boxes report only the leanback feature, and
     * some report only the television UI mode. Either one is a person on a sofa with a remote, which is what the TV
     * screen is for. A phone reports neither (mode 1 "normal", no leanback). The cost of a wrong "true" is a phone
     * showing the TV screen, and of a wrong "false" a TV showing a touch UI it cannot drive, so the rule errs towards
     * "true".
     */
    fun isTelevision(uiModeType: Int, hasLeanbackFeature: Boolean): Boolean =
        uiModeType == UI_MODE_TYPE_TELEVISION || hasLeanbackFeature

    /** The real device's answer. Glue over [isTelevision]: needs Android, so no JVM test reaches it (TV checks only). */
    fun isTelevision(context: Context): Boolean {
        val mode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ?: 0
        val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return isTelevision(mode, leanback)
    }
}
