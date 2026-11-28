package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TvDetect.isTelevision (the decision behind ServerConfig.isTelevision() and the skipped prompts on a TV). Every
 * combination of the two signals is its own assertion with a message, so a flipped operator names itself.
 *
 * Not reachable here (Android glue, TV checks only): reading UiModeManager and PackageManager from a real device
 * (TvDetect.isTelevision(Context)) and the plugin method that hands the answer to the page.
 */
class TvDetectTest {

    // Configuration.UI_MODE_TYPE_NORMAL (a phone or tablet) and _DESK / _CAR / _WATCH, to show only 4 counts.
    private val normal = 1
    private val desk = 2
    private val car = 3
    private val television = 4
    private val watch = 6

    @Test
    fun televisionModeWithLeanbackIsATv() {
        assertTrue("a normal Android TV reports both", TvDetect.isTelevision(television, true))
    }

    @Test
    fun televisionModeAloneIsATv() {
        assertTrue("some boxes report only the UI mode", TvDetect.isTelevision(television, false))
    }

    @Test
    fun leanbackAloneIsATv() {
        assertTrue("some Fire TV models report only the leanback feature", TvDetect.isTelevision(normal, true))
    }

    @Test
    fun aPhoneIsNotATv() {
        // The hostile case: the most common device must never be shown the TV screen.
        assertFalse("mode 1, no leanback is a phone or tablet", TvDetect.isTelevision(normal, false))
    }

    @Test
    fun otherUiModesWithoutLeanbackAreNotATv() {
        for (mode in listOf(0, desk, car, watch, -1, 5, 7)) {
            assertFalse("mode $mode without leanback is not a TV", TvDetect.isTelevision(mode, false))
        }
    }

    @Test
    fun theTelevisionConstantIsFour() {
        // Pinned because the platform value is what UiModeManager.currentModeType reports on a TV; a typo here would make
        // the UI-mode half of the rule dead while every other test still passed.
        assertEquals(4, TvDetect.UI_MODE_TYPE_TELEVISION)
    }
}
