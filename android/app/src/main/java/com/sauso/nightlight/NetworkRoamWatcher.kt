package com.sauso.nightlight

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moves the app to the server's other address when the network changes (owner, 2026-11-06). The launch gate used to be the
 * only place that chose between the saved address and the Tailscale one, so Wi-Fi turning to mobile data left the page on a
 * dead home address until the app was restarted (the web app is a single page: there is no main-frame load error for
 * onReceivedError's recreate to catch).
 *
 * What it does, while the activity is started (MainActivity: [start] in onStart, [stop] in onStop):
 *  - listens to the DEFAULT network (registerDefaultNetworkCallback, API 24, the app's minSdk; ACCESS_NETWORK_STATE is
 *    already declared for LocalNetwork). A real change (NetworkEventFilter) schedules a NETWORK_CHANGE check; [start] itself
 *    schedules one FOREGROUND_START check, so an app brought back to the front after roaming in the background re-chooses
 *    too. Each waits RoamRules.SETTLE_MS, and a newer request replaces a waiting one;
 *  - the check, off the main thread: the page the WebView is really showing must be on the saved address or its Tailscale
 *    address (RoamRules.currentAddress), then the launch's own choice runs (MainActivity.chooseAddress, so the two cannot
 *    drift), RoamRules.target says whether to switch, and the shared loop guard (MainActivity.recreateLog) says whether a
 *    recreate is allowed now;
 *  - when nothing answered, it checks again a few times (RoamRules.retryDelayAfter) and then waits for the next event.
 *
 * Every request carries the GENERATION it was made under. [stop] moves the generation on (onStop runs before every recreate,
 * so a recreate moves it too), which makes any check or retry still queued or running dead on arrival: a late probe can never
 * recreate an activity that has stopped or been replaced.
 *
 * Threads: the network callback runs on Android's ConnectivityThread and only posts to [worker]; the scheduling, the waits and
 * the checks run on [worker]; the WebView and the activity are touched only on the main thread. Glue: no JVM test reaches
 * this (the rules it applies are in RoamRules, tested in RoamRulesTest); the phone checklist in the PR covers it.
 */
class NetworkRoamWatcher(private val activity: MainActivity) {

    companion object {
        private const val TAG = "NightlightRoam"

        // How long a check waits for the main thread to read the page's URL or the Picture-in-Picture state. The same bound,
        // and the same reasoning, as ServerConfigPlugin's PAGE_CHECK_TIMEOUT_MS: the main thread answers in microseconds
        // unless it is busy, and an answer that does not come counts as "not known" (which never switches anything).
        private const val MAIN_THREAD_TIMEOUT_MS = 1_000L

        // The transports NetworkEventFilter compares. Wi-Fi to mobile data is the case this is for; Ethernet and VPN are
        // included because a VPN coming up or its underlying network changing (Tailscale itself) also changes what answers.
        private const val WIFI = 1
        private const val CELLULAR = 2
        private const val ETHERNET = 4
        private const val VPN = 8

        private fun transportsOf(caps: NetworkCapabilities): Int {
            var bits = 0
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) bits = bits or WIFI
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) bits = bits or CELLULAR
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) bits = bits or ETHERNET
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) bits = bits or VPN
            return bits
        }

        // ONE thread for every watcher's waits and checks, for the life of the process (like ServerConfigPlugin's
        // sessionExecutor), so starting and stopping the app does not make and drop threads. A check blocks it for up to
        // about 4 s (the main-thread read and the 3 s choice), which only delays the next one. A daemon thread.
        private val worker: Handler by lazy {
            val thread = HandlerThread("nightlight-roam")
            thread.isDaemon = true
            thread.start()
            Handler(thread.looper)
        }
    }

    private val generation = AtomicInteger()

    // What this watcher's waiting checks are posted under, so [stop] and a newer request remove exactly this watcher's own.
    private val token = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // The application context's service: a ConnectivityManager taken from an activity context can keep the activity alive.
    private val connectivity: ConnectivityManager? = try {
        activity.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (e: Exception) {
        null
    }

    // The registered callback, or null. Main thread only (start and stop).
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Called from onStart. Registers once; a second call before [stop] does nothing. */
    fun start() {
        if (callback != null) return
        val gen = generation.incrementAndGet()
        val cm = connectivity
        if (cm != null) {
            // Read just before registering, so the filter can tell the registration's echo from a real change.
            val atRegistration: Network? = try { cm.activeNetwork } catch (e: Exception) { null }
            val filter = NetworkEventFilter<Network>(atRegistration)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (filter.onAvailable(network)) networkChanged(gen)
                }

                override fun onLost(network: Network) {
                    if (filter.onLost()) networkChanged(gen)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (filter.onTransports(transportsOf(networkCapabilities))) networkChanged(gen)
                }
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
                callback = cb
            } catch (e: Exception) {
                // A SecurityException (no permission) or too many callbacks for this app. The app works as before: the start
                // check below still runs, and the next start tries again.
                Log.w(TAG, "Could not watch the network: ${e.javaClass.simpleName}")
            }
        }
        worker.post {
            startPending = true
            schedule(gen, RoamRules.Trigger.FOREGROUND_START, 0, RoamRules.SETTLE_MS)
        }
    }

    /** Called from onStop (and onDestroy, in case). Safe to call twice. */
    fun stop() {
        generation.incrementAndGet()
        worker.removeCallbacksAndMessages(token)
        val cb = callback ?: return
        callback = null
        try {
            connectivity?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // IllegalArgumentException: it was not registered (already unregistered). Nothing to undo.
        }
    }

    // On the ConnectivityThread: touch nothing here, hand it to the worker.
    private fun networkChanged(gen: Int) {
        // A start check still waiting keeps its trigger: waking the phone usually brings a network event with it (Wi-Fi coming
        // back), and letting that replace the start check would turn it into a network-change check, which may never move the
        // app back home, so the person would stay on Tailscale until the next time they opened the app.
        worker.post { schedule(gen, if (startPending) RoamRules.Trigger.FOREGROUND_START else RoamRules.Trigger.NETWORK_CHANGE, 0, RoamRules.SETTLE_MS) }
    }

    // True from start() until the first check runs; read and written on the worker thread only.
    private var startPending = false

    // On the worker. Replaces whatever check or retry of this watcher is waiting: a flapping network is one check, and a new
    // network starts the retries from the beginning.
    private fun schedule(gen: Int, trigger: RoamRules.Trigger, attempt: Int, delayMs: Long) {
        if (generation.get() != gen) return
        worker.removeCallbacksAndMessages(token)
        worker.postAtTime(Runnable { evaluate(gen, trigger, attempt) }, token, SystemClock.uptimeMillis() + delayMs)
    }

    // On the worker. See the class comment for the order of the checks.
    private fun evaluate(gen: Int, trigger: RoamRules.Trigger, attempt: Int) {
        if (generation.get() != gen) return
        startPending = false
        val context = activity.applicationContext
        // Without a saved address and its Tailscale address there is nothing to move between.
        val saved = ServerConfigPlugin.getSavedUrl(context) ?: return
        val tailscale = ServerConfigPlugin.getTailscaleUrl(context, saved) ?: return
        // The page on screen must be one of the two (not the bundled setup or error page), or nothing is probed at all.
        val current = RoamRules.currentAddress(onMainThread { activity.shownPageUrl() }, saved, tailscale) ?: return
        val chosen = MainActivity.chooseAddress(context, saved, tailscale)
        if (generation.get() != gen) return
        // Not known (the main thread did not answer in time) counts as in Picture-in-Picture: that only ever holds back a
        // fail-back, never a fail-over.
        val inPip = onMainThread { activity.isInPictureInPictureMode } ?: true
        val target = RoamRules.target(trigger, saved, tailscale, current, chosen, inPip)
        if (target == null) {
            if (chosen == null) {
                val wait = RoamRules.retryDelayAfter(attempt)
                if (wait != null) schedule(gen, trigger, attempt + 1, wait)
            }
            return
        }
        val which = if (target == tailscale) "the Tailscale address" else "the saved address"
        if (!MainActivity.recreateLog.mayRoam(MainActivity.recreateClockMs())) {
            // No retry: the next network event or the next start checks again.
            Log.i(TAG, "Would move to $which ($trigger), held back by the reload limit")
            return
        }
        activity.runOnUiThread {
            if (generation.get() != gen || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            // The page may have moved while the probes ran: only switch away from the page the decision was about.
            if (RoamRules.currentAddress(activity.shownPageUrl(), saved, tailscale) != current) return@runOnUiThread
            // Asked again HERE: the load-error recreate also runs on the main thread, so this check and the note below cannot
            // interleave with it.
            val now = MainActivity.recreateClockMs()
            if (!MainActivity.recreateLog.mayRoam(now)) return@runOnUiThread
            MainActivity.recreateLog.noteRoam(now)
            Log.i(TAG, "Moving to $which ($trigger)")
            // recreate() re-runs onCreate's launch gate, which makes the same choice and points the bridge at it. The saved
            // address is never rewritten: only the address the bridge opens differs.
            activity.recreate()
        }
    }

    // Reads something that may only be read on the main thread, from the worker: the same shape as ServerConfigPlugin's
    // webViewUrlOnMainThread. Null when there is no answer in time or anything fails.
    private fun <T> onMainThread(read: () -> T): T? {
        val task = FutureTask(Callable { read() })
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else mainHandler.post(task)
            task.get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
    }
}
