package com.sauso.nightlight

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds Nightlight servers on the local network for "Look for my Nightlight" (#20): browses `_nightlight._tcp` with
 * Android's own service discovery (NsdManager, design 7.4: no new dependency), resolves each service, reads its TXT
 * `id`, probes every private address it announced (HealthProbe), and reports each VERIFIED server once.
 *
 * It only suggests. It never saves, switches, recreates the activity, or contacts anything but the health check of
 * an announced private address (the #559 principle: nothing changes which server the app trusts without a tap).
 *
 * What the server announces (mdnsAnnounce.js): TXT `v=1` and `id=<uuid>`, SRV host `nightlight-<id8>.local`, and
 * one A record per non-internal IPv4 interface. Measured with the shipped announcer (design 15.1): an Android phone
 * browsing this type saw the staging server almost as soon as it asked, and the entry vanished as soon as the server
 * stopped (the goodbye packets work). A server on Docker's default bridge network is never seen; nothing here can
 * detect that, so the page's help panel says so (www/index.html).
 *
 * Resolving, which differs by Android version:
 *  - API 34+: registerServiceInfoCallback, which reports ALL of a host's addresses (hostAddresses). Up to
 *    [RESOLVE_SLOTS_API34] at once; each callback is unregistered as soon as it has answered, or on timeout, loss or
 *    stop.
 *  - API 24 to 33: resolveService, which reports ONE address, and only one resolve may run at a time (an
 *    overlapping one fails with FAILURE_ALREADY_ACTIVE), so resolves are queued one by one, and a refused one is
 *    retried after DiscoveryState.RETRY_DELAY_MS. Known risk (plan decision 2): a host-network server with several interfaces may
 *    resolve to an address this phone cannot reach (a Docker bridge gateway). Its probe then fails and it is not
 *    listed; the typed address still works. If an older phone shows this, a small mDNS library is the fallback (not
 *    built unless needed). This path cannot be tried on the owner's phone (API 37).
 * TXT is read only AFTER the resolve (plan review, High: Android documents TXT attributes as valid only on a resolved
 * NsdServiceInfo).
 *
 * Threads and the lock. NsdManager calls back on its own thread; probes run on at most [PROBE_THREADS] worker
 * threads; timeouts and retries on one timer thread. All state is in [state] behind [lock]. Every NsdManager request
 * (start, stop, register, unregister, resolve) is made WHILE HOLDING [lock], together with the bookkeeping for it,
 * so a stop() can never fall between "recorded" and "asked for" (code review of #20: a stop in that gap left an
 * orphan discovery for the life of the process, or a leaked service-info registration counting toward NsdManager's
 * per-app request limit). That is deadlock-free because those calls never call back into us on the calling thread:
 * checked in the platform's NsdManager (Android Studio's layoutlib build, `javap -c`): each one only records the
 * listener under NsdManager's own map lock and makes a binder call, and its handler calls listeners through their
 * executor only after releasing that lock. Older Android versions could not be checked here.
 *
 * Every NsdManager call is inside try/catch: on some devices they throw (an unregistered listener, a stopped
 * service). A discovery that fails to start is reported once per search ([Listener.onDiscoveryFailed]), so the page
 * can say so at once instead of "Looking…" for two minutes.
 *
 * Not used: a Wi-Fi MulticastLock (and CHANGE_WIFI_MULTICAST_STATE). NsdManager talks to the platform's own mDNS
 * daemon; the lock is for apps that open their own multicast sockets. That is Android's documentation, not a
 * measurement on old phones: if an older phone finds nothing where an API 34 phone finds the server, add the lock
 * and the permission then (plan decision 2).
 *
 * Known limits (stated, not built): a device that announces many names which never resolve holds each resolve slot
 * for [RESOLVE_TIMEOUT_MS] and can keep a real server's resolve waiting for a while; on a slow network the page's
 * help panel can appear before the server is found (the search keeps listening behind it).
 */
class ServerDiscovery(context: Context, private val probe: HealthProbe = HealthProbe()) {

    interface Listener {
        // These are called WITH the discovery lock held, so events keep the order they happened in (a card is never
        // added after its own removal). An implementation must only hand the event to another thread and return.
        fun onServerFound(generation: Int, found: DiscoveryState.Found)

        fun onServerLost(generation: Int, id: String)

        fun onDiscoveryFailed(generation: Int, message: String)
    }

    companion object {
        private const val TAG = "NightlightDiscovery"

        // The type as the server announces it (mdnsAnnounce.js: type 'nightlight', protocol 'tcp'). NsdManager
        // accepts it with or without a trailing dot (NsdManager.TYPE_REGEX).
        const val SERVICE_TYPE = "_nightlight._tcp"

        // Plan (#20): "a bounded queue (64) on 4 worker threads". Four parallel probes cover one host's addresses
        // (design 7.5 step 5: probe them all at once, keep the first that verifies) without a flood of fakes
        // becoming a flood of sockets.
        private const val PROBE_THREADS = 4

        // API 34+ resolves run in parallel; NsdManager limits how many requests one app may hold
        // (FAILURE_MAX_LIMIT), so stay well under it. Before API 34, one at a time (see the class comment).
        private const val RESOLVE_SLOTS_API34 = 4
        private const val RESOLVE_SLOTS_LEGACY = 1

        // A resolve that never answers must not hold its slot forever: on an older phone that would stop every
        // later server from being resolved. 5 s is a judgement (a LAN answer takes milliseconds), not a measurement.
        private const val RESOLVE_TIMEOUT_MS = 5_000L

        // Stopping a discovery completes asynchronously (onDiscoveryStopped). A new search waits for that, so two
        // discoveries never overlap (plan review, Medium); if the confirmation never comes, it waits this long.
        private const val STOP_CONFIRM_TIMEOUT_MS = 3_000L

        // At most this many deferred re-probes may be waiting on a timer at once (DiscoveryState defers a quick
        // re-announcement to the end of its re-probe window); beyond it a deferral is dropped.
        private const val MAX_DEFER_TIMERS = DiscoveryState.MAX_TRACKED_NAMES

        // Not an NsdManager code: our own timeout, reported through the same path as a failed resolve.
        private const val RESOLVE_TIMED_OUT = -1

        private fun daemonThreads(prefix: String): ThreadFactory {
            val count = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${count.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }

    private val appContext = context.applicationContext
    private val nsd: NsdManager? = try {
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (e: Exception) {
        null
    }

    private val lock = Any()
    private val state = DiscoveryState<NsdServiceInfo>()
    private var listener: Listener? = null
    private var failureReported = false
    private var released = false

    // The discovery for the current search (null when stopped), the one NsdManager is actually running (not yet
    // confirmed stopped), and the current one when it is waiting for the previous one to finish stopping.
    private var currentListener: NsdManager.DiscoveryListener? = null
    private var activeListener: NsdManager.DiscoveryListener? = null
    private var pendingStart: NsdManager.DiscoveryListener? = null
    private var stopConfirmTimer: ScheduledFuture<*>? = null

    // API 34+ service-info callbacks by resolve token. Typed Any so this class still loads on API < 34, where
    // NsdManager.ServiceInfoCallback does not exist; only @RequiresApi(34) code casts them back.
    private val infoCallbacks = HashMap<Int, Pair<String, Any>>()

    // Every timer is kept so it can be cancelled when its work ends (code review of #20: resolve timeouts were never
    // cancelled). Resolve timeouts are bounded by the resolve slots, deferred re-probes by MAX_DEFER_TIMERS.
    private val resolveTimeouts = HashMap<Int, Pair<String, ScheduledFuture<*>>>() // by resolve token: (name, timer)
    private val deferTimers = HashMap<String, ScheduledFuture<*>>() // by service name
    // ONE timer for every waiting retry, set for the earliest due time DiscoveryState reports (second review of #20:
    // a timer per service outlived the waiting entry the cap had dropped, so the bound did not hold). [retryTimerSeq]
    // tells the current timer from one it replaced that was already running.
    private var retryTimer: ScheduledFuture<*>? = null
    private var retryTimerDueAt = Long.MAX_VALUE
    private var retryTimerSeq = 0

    private var activeWorkers = 0

    // The pool's own queue never holds more than PROBE_THREADS runnables: a worker is submitted only while fewer
    // than that are running, and each one drains [state]'s bounded probe queue. Idle threads exit after 30 s.
    private val probePool = ThreadPoolExecutor(
        PROBE_THREADS, PROBE_THREADS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), daemonThreads("nightlight-probe"),
    ).apply { allowCoreThreadTimeOut(true) }

    private val timers = ScheduledThreadPoolExecutor(1, daemonThreads("nightlight-discovery-timer")).apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    // NsdManager (API 34+) runs callbacks on the executor it is given: run them in place on its own thread. They
    // only take [lock] briefly and queue work; nothing here blocks.
    private val inPlace = Executor { it.run() }

    /** False when this phone has no NsdManager (never seen, but getSystemService may return null). */
    val isAvailable: Boolean get() = nsd != null

    fun isCurrent(generation: Int): Boolean = synchronized(lock) { state.isCurrent(generation) }

    /**
     * Whether the phone is on a network where the server could be heard: any connected Wi-Fi or Ethernet network.
     * All networks, not only the default one: with a VPN active (Tailscale, say) the default network is the VPN, and
     * a Wi-Fi without internet may sit behind mobile data as the default while still reaching the server. Fails
     * OPEN (true) if the check itself fails: this only chooses a message, and discovery's rules stand either way.
     * Needs ACCESS_NETWORK_STATE (AndroidManifest.xml).
     */
    fun isOnLocalNetwork(): Boolean {
        val cm = try {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            null
        } ?: return true
        return try {
            // getAllNetworks is deprecated since API 31 in favour of callbacks; a one-off question needs no callback.
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            networks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Start a search and return its generation, or null if discovery is unavailable or this object was released.
     * A second start while one is running is a no-op that returns the running generation; "Search again" is
     * [stop] then [start], which begins a NEW generation. A discovery that fails to start is reported through
     * [Listener.onDiscoveryFailed] (the generation is still returned; the page is told by the event).
     */
    fun start(listener: Listener): Int? {
        val manager = nsd ?: return null
        return synchronized(lock) {
            if (released) return null
            if (state.running) return state.generation
            val gen = state.start()
            this.listener = listener
            failureReported = false
            val discovery = newDiscoveryListener(gen)
            currentListener = discovery
            if (activeListener == null) {
                beginDiscoveryLocked(manager, gen, discovery)
            } else {
                // The previous search is still stopping: begin when it confirms (onStopConfirmed).
                pendingStart = discovery
            }
            gen
        }
    }

    /**
     * Stop the search: no more events, queued resolves, retries, deferred probes and queued probes dropped, every
     * timer cancelled, API 34 callbacks unregistered. Probes already running finish on their own (within
     * HealthProbe's 2 s deadline) and their results are discarded. Safe at any time, from any thread, any number of
     * times.
     */
    fun stop() {
        synchronized(lock) {
            if (!state.running) return
            state.stop()
            listener = null
            val current = currentListener
            currentListener = null
            if (current != null && pendingStart === current) {
                // It never began, so there is nothing to stop.
                pendingStart = null
            } else if (current != null && activeListener === current) {
                try {
                    nsd?.stopServiceDiscovery(current)
                    stopConfirmTimer?.cancel(false)
                    stopConfirmTimer = scheduleTimer(STOP_CONFIRM_TIMEOUT_MS) { onStopConfirmed(current) }
                } catch (e: Exception) {
                    // NsdManager does not know it (it failed to start, or already stopped): nothing is running.
                    activeListener = null
                }
            }
            for (token in infoCallbacks.keys.toList()) endResolveLocked(token)
            for (token in resolveTimeouts.keys.toList()) endResolveLocked(token)
            scheduleRetryLocked() // nothing is waiting any more, so this cancels the retry timer
            deferTimers.values.forEach { it.cancel(false) }
            deferTimers.clear()
        }
    }

    /** Stop, and release the threads. For the plugin's destroy; this object cannot be started again after it. */
    fun release() {
        stop()
        synchronized(lock) {
            released = true
            pendingStart = null
            stopConfirmTimer?.cancel(false)
            stopConfirmTimer = null
            retryTimer?.cancel(false)
            retryTimer = null
        }
        probePool.shutdownNow()
        timers.shutdownNow()
    }

    // Lock held. Ask NsdManager to start; a synchronous refusal is a failed start.
    private fun beginDiscoveryLocked(manager: NsdManager, gen: Int, discovery: NsdManager.DiscoveryListener) {
        activeListener = discovery
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            if (activeListener === discovery) activeListener = null
            failLocked(gen, "Could not start looking for servers: ${e.message}")
        }
    }

    // The discovery NsdManager was running is over (stopped, failed to start, or its stop timed out). If a new
    // search was waiting for it, begin that one now.
    private fun onStopConfirmed(discovery: NsdManager.DiscoveryListener) {
        val manager = nsd ?: return
        synchronized(lock) {
            if (activeListener !== discovery) return
            activeListener = null
            stopConfirmTimer?.cancel(false)
            stopConfirmTimer = null
            val next = pendingStart ?: return
            pendingStart = null
            if (released || !state.running || currentListener !== next) return
            beginDiscoveryLocked(manager, state.generation, next)
        }
    }

    // Lock held. Tell the page once per search that looking could not start.
    private fun failLocked(gen: Int, message: String) {
        Log.w(TAG, message)
        if (!state.isCurrent(gen) || failureReported) return
        failureReported = true
        listener?.onDiscoveryFailed(gen, message)
    }

    private fun newDiscoveryListener(gen: Int): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String?) {}

        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            onStopConfirmed(this)
            synchronized(lock) { failLocked(gen, "Looking for servers failed to start (NsdManager error $errorCode)") }
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            onStopConfirmed(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            onStopConfirmed(this)
        }

        // Nullable parameters on purpose: a platform type would get a Kotlin null check, and a null from an odd
        // device would then throw inside NsdManager's thread instead of being ignored.
        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo != null) found(gen, serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo != null) lost(gen, serviceInfo.serviceName)
        }
    }

    private fun found(gen: Int, info: NsdServiceInfo) {
        synchronized(lock) {
            if (state.onFound(gen, info.serviceName, info)) pumpResolvesLocked()
        }
    }

    private fun lost(gen: Int, name: String?) {
        synchronized(lock) {
            if (!state.isCurrent(gen)) return
            val id = state.onLost(gen, name)
            if (id != null) listener?.onServerLost(gen, id)
            val tokens = infoCallbacks.filterValues { it.first == name }.keys + resolveTimeouts.filterValues { it.first == name }.keys
            for (token in tokens) endResolveLocked(token)
            if (name != null) deferTimers.remove(name)?.cancel(false)
            scheduleRetryLocked() // its waiting retry, if any, is gone
            pumpResolvesLocked() // its resolve slot may have been freed
        }
    }

    // Lock held. Start resolves while slots are free. A request NsdManager refuses on the spot is a failed attempt
    // (not retried), and the loop goes on with the next service.
    private fun pumpResolvesLocked() {
        val manager = nsd ?: return
        val slots = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) RESOLVE_SLOTS_API34 else RESOLVE_SLOTS_LEGACY
        while (true) {
            val job = state.nextResolve(slots) ?: return
            val launched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerInfoCallbackLocked(manager, job)
            } else {
                resolveLegacyLocked(manager, job)
            }
            if (launched) {
                val timer = scheduleTimer(RESOLVE_TIMEOUT_MS) { resolveFailed(job, RESOLVE_TIMED_OUT) }
                if (timer != null) resolveTimeouts[job.token] = Pair(job.name, timer)
            } else {
                state.resolveFailed(job, retry = false)
            }
        }
    }

    // Lock held. Returns false if NsdManager refused the request on the spot.
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerInfoCallbackLocked(manager: NsdManager, job: DiscoveryState.ResolveJob<NsdServiceInfo>): Boolean {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                resolveFailed(job, errorCode)
            }

            // Non-null here, unlike the older listeners: Android declares this parameter @NonNull, so Kotlin
            // refuses a nullable override.
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val hosts = try {
                    serviceInfo.hostAddresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                } catch (e: Exception) {
                    emptyList()
                }
                resolved(job, serviceInfo, hosts)
            }

            override fun onServiceLost() {
                lost(job.generation, job.name)
            }

            override fun onServiceInfoCallbackUnregistered() {}
        }
        // Recorded and requested in one step under the lock: a stop() cannot run between them and miss it.
        infoCallbacks[job.token] = Pair(job.name, callback)
        return try {
            manager.registerServiceInfoCallback(job.payload, inPlace, callback)
            true
        } catch (e: Exception) {
            infoCallbacks.remove(job.token)
            false
        }
    }

    // Lock held. resolveService is deprecated from API 34, which this path never runs on (pumpResolvesLocked).
    // Before API 34 there is no way to cancel a resolve: after a stop or a loss it runs to its end in NsdManager and
    // its answer is discarded (the token no longer matches).
    @Suppress("DEPRECATION")
    private fun resolveLegacyLocked(manager: NsdManager, job: DiscoveryState.ResolveJob<NsdServiceInfo>): Boolean {
        // A new listener object per resolve: NsdManager refuses a listener that is already in use.
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                resolveFailed(job, errorCode)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) {
                    resolveFailed(job, NsdManager.FAILURE_INTERNAL_ERROR)
                    return
                }
                // One address before API 34. getHost is deprecated from API 34 in favour of hostAddresses.
                val host = serviceInfo.host
                val hosts = if (host is Inet4Address) listOfNotNull(host.hostAddress) else emptyList()
                resolved(job, serviceInfo, hosts)
            }
        }
        return try {
            manager.resolveService(job.payload, resolveListener)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun resolved(job: DiscoveryState.ResolveJob<NsdServiceInfo>, info: NsdServiceInfo, hosts: List<String>) {
        val txtId = try {
            DiscoveryRules.txtId(info.attributes)
        } catch (e: Exception) {
            null
        }
        synchronized(lock) {
            endResolveLocked(job.token)
            val tasks = state.onResolved(job, txtId, hosts, info.port, SystemClock.elapsedRealtime())
            if (tasks.isNotEmpty()) pumpProbesLocked() else scheduleDeferredLocked(job.name)
            pumpResolvesLocked()
        }
    }

    private fun resolveFailed(job: DiscoveryState.ResolveJob<NsdServiceInfo>, errorCode: Int) {
        // Retry only "busy" refusals: another resolve still active (the API < 34 rule) or too many requests.
        val retry = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE || errorCode == NsdManager.FAILURE_MAX_LIMIT
        synchronized(lock) {
            endResolveLocked(job.token)
            state.resolveFailed(job, retry, SystemClock.elapsedRealtime())
            scheduleRetryLocked()
            pumpResolvesLocked()
        }
    }

    // Lock held. Keep the ONE retry timer set for the earliest waiting retry, or cancel it when none waits. A retry
    // the bounded waiting list dropped simply has no due time any more, so nothing fires for it.
    private fun scheduleRetryLocked() {
        val due = state.nextRetryDueAt()
        if (due == null) {
            retryTimer?.cancel(false)
            retryTimer = null
            retryTimerDueAt = Long.MAX_VALUE
            return
        }
        if (retryTimer != null && retryTimerDueAt <= due) return
        retryTimer?.cancel(false)
        val seq = ++retryTimerSeq
        retryTimerDueAt = due
        retryTimer = scheduleTimer(maxOf(0L, due - SystemClock.elapsedRealtime())) { runRetries(seq) }
        if (retryTimer == null) retryTimerDueAt = Long.MAX_VALUE
    }

    private fun runRetries(seq: Int) {
        synchronized(lock) {
            if (seq == retryTimerSeq) {
                retryTimer = null
                retryTimerDueAt = Long.MAX_VALUE
            }
            if (state.requeueDueRetries(SystemClock.elapsedRealtime()) > 0) pumpResolvesLocked()
            scheduleRetryLocked()
        }
    }

    // Lock held. A quick re-announcement inside the re-probe window was deferred (DiscoveryState): run it when the
    // window ends. One timer per name, and at most MAX_DEFER_TIMERS in all.
    private fun scheduleDeferredLocked(name: String) {
        val due = state.deferredDueAt(name) ?: return
        if (deferTimers.containsKey(name)) return
        if (deferTimers.size >= MAX_DEFER_TIMERS) {
            state.dropDeferred(name)
            return
        }
        val delay = maxOf(0L, due - SystemClock.elapsedRealtime())
        val timer = scheduleTimer(delay) { runDeferred(name) }
        if (timer != null) deferTimers[name] = timer else state.dropDeferred(name)
    }

    private fun runDeferred(name: String) {
        synchronized(lock) {
            deferTimers.remove(name)
            val tasks = state.runDeferred(name, SystemClock.elapsedRealtime())
            if (tasks.isNotEmpty()) pumpProbesLocked() else scheduleDeferredLocked(name) // not due yet: wait again
        }
    }

    // Lock held. The attempt with this token is over: cancel its timeout and unregister its API 34 callback.
    private fun endResolveLocked(token: Int) {
        resolveTimeouts.remove(token)?.second?.cancel(false)
        val callback = infoCallbacks.remove(token)?.second ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) unregisterInfoCallbackApi34(callback)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterInfoCallbackApi34(callback: Any) {
        val manager = nsd ?: return
        val infoCallback = callback as? NsdManager.ServiceInfoCallback ?: return
        try {
            manager.unregisterServiceInfoCallback(infoCallback)
        } catch (e: Exception) {
            // Already unregistered (it failed to register, or NsdManager dropped it): nothing to release.
        }
    }

    // Lock held.
    private fun pumpProbesLocked() {
        val wanted = minOf(PROBE_THREADS - activeWorkers, state.queuedProbeCount())
        repeat(wanted) {
            activeWorkers++
            try {
                probePool.execute(Runnable { probeWorker() })
            } catch (e: Exception) {
                // The pool is shut down (released): the queued probes are simply not run.
                activeWorkers--
            }
        }
    }

    // Takes probes from the bounded queue until it is empty. "No task" and "one fewer worker" are decided under the
    // same lock, so a task queued at that moment is never left without a worker.
    private fun probeWorker() {
        while (true) {
            val task = synchronized(lock) {
                val next = state.nextProbe()
                if (next == null) activeWorkers--
                next
            } ?: return
            val health = try {
                probe.probe(task.host, task.port, task.txtId)
            } catch (e: Exception) {
                null
            }
            synchronized(lock) {
                val found = state.onProbeDone(task, health)
                if (found != null) listener?.onServerFound(task.generation, found)
            }
        }
    }

    private fun scheduleTimer(delayMs: Long, action: () -> Unit): ScheduledFuture<*>? = try {
        timers.schedule(Runnable { action() }, delayMs, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        // The timer is shut down (released): nothing is waiting any more.
        null
    }
}
