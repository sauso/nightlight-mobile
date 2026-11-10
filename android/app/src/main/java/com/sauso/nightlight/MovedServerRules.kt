package com.sauso.nightlight

/**
 * Pure decisions for finding the same server after its saved LAN address stops answering. The native caller owns
 * strict health probes, discovery, the prompt and the single writes queue; these rules make their safety decisions
 * testable without an Android device.
 */
object MovedServerRules {

    // Judgements, not measurements: avoid repeated background work, keep a declined address quiet for a week,
    // bound the remembered candidates, and end a search or probe that cannot answer promptly.
    const val CHECK_INTERVAL_MS = 5L * 60 * 1000
    const val DECLINE_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_DECLINES = 8
    const val SEARCH_BOUND_MS = 10_000L
    const val PROBE_TIMEOUT_MS = 2_000L

    const val PROMPT_TITLE = "Your server has a new address"
    const val NOT_NOW_BUTTON = "Not now"
    const val USE_NEW_ADDRESS_BUTTON = "Use new address"

    sealed class Probe {
        data class Answered(val id: String?) : Probe()
        object NoAnswer : Probe()
    }

    sealed class Step {
        object Skip : Step()
        data class Learn(val id: String) : Step()
        object Stop : Step()
        data class Search(val wantedId: String) : Step()
    }

    /**
     * A typed hostname, HTTPS URL or public address is outside this feature. The identity is the stored id, or the
     * Tailscale pair's stored id when there is none. A different answer starts a search for that identity and never
     * learns the answer as a replacement, even when the pair is the only identity available.
     */
    fun decide(savedUrl: String?, storedId: String?, pairId: String?, probe: Probe): Step {
        if (!EndpointRules.idCheckedAddress(savedUrl)) return Step.Skip
        return when (probe) {
            is Probe.Answered -> {
                val id = probe.id ?: return Step.Stop
                if (!DiscoveryRules.isPlausibleId(id)) return Step.Stop
                val expected = storedId ?: pairId
                when {
                    storedId == null && (pairId == null || id == pairId) -> Step.Learn(id)
                    id == expected -> Step.Stop
                    else -> Step.Search(expected!!)
                }
            }
            Probe.NoAnswer -> {
                val wanted = storedId ?: pairId
                if (wanted == null) Step.Stop else Step.Search(wanted)
            }
        }
    }

    /** Discovery's card may be used only for the exact id, at a different private LAN address. */
    fun acceptCandidate(wantedId: String, savedUrl: String, foundId: String, foundUrl: String): Boolean {
        if (!DiscoveryRules.matches(DiscoveryRules.Health(foundId, null), wantedId) || foundUrl == savedUrl) return false
        val found = DiscoveryRules.parseFoundUrl(foundUrl) ?: return false
        return DiscoveryRules.isPrivateIpv4(found.first)
    }

    /**
     * Plain text for a native alert. Names and the short code help someone recognise an address; neither proves
     * ownership, so the message asks them to compare the address with their server or router before accepting.
     */
    fun promptMessage(oldUrl: String, newUrl: String, offeredName: String?, storedName: String?, fingerprint: String): String {
        val name = offeredName?.takeIf { it.isNotEmpty() } ?: DiscoveryRules.DEFAULT_NAME
        val nameDifference = if (!storedName.isNullOrEmpty() && storedName != offeredName) {
            "\nThe name is different from the one you saved (\"$storedName\")."
        } else ""
        return "Nightlight found it again on your Wi-Fi.\n\n" +
            "Old address: $oldUrl\nNew address: $newUrl\nName: $name\nCode: $fingerprint" +
            nameDifference + "\n\nOnly use it if you recognise this address (check your server or router)."
    }

    sealed class MoveStep {
        data class ClearNew(val url: String) : MoveStep()
        data class AddKnown(val url: String) : MoveStep()
        data class SetName(val url: String, val name: String) : MoveStep()
        data class SetServerId(val url: String, val id: String) : MoveStep()
        data class CarryPair(val url: String, val pairUrl: String, val pairId: String) : MoveStep()
        data class SetSavedUrl(val url: String) : MoveStep()
        data class RemoveOld(val url: String) : MoveStep()
    }

    /**
     * Clear a reused destination before writing its new identity, so its prior server's pair, decline and sign-in
     * cannot follow the move. Prepare all new-address data before the saved URL changes; only then remove the old
     * address. This order leaves a usable address if a write is interrupted.
     */
    fun movePlan(
        oldUrl: String,
        newUrl: String,
        id: String,
        name: String?,
        knownServers: Collection<String>,
        hasNewUrlLeftovers: Boolean,
        pairUrl: String?,
        pairId: String?,
    ): List<MoveStep> {
        val steps = ArrayList<MoveStep>()
        if (newUrl in knownServers || hasNewUrlLeftovers) steps.add(MoveStep.ClearNew(newUrl))
        // ClearNew removes a remembered list entry, so the destination must always be added again.
        steps.add(MoveStep.AddKnown(newUrl))
        if (name != null && name.isNotEmpty()) steps.add(MoveStep.SetName(newUrl, name))
        steps.add(MoveStep.SetServerId(newUrl, id))
        if (pairUrl != null && pairId != null && pairId == id) steps.add(MoveStep.CarryPair(newUrl, pairUrl, pairId))
        steps.add(MoveStep.SetSavedUrl(newUrl))
        steps.add(MoveStep.RemoveOld(oldUrl))
        return steps
    }

    object MoveGuard {
        /**
         * Re-read both values on the writes queue at the tap. When no id was stored, the caller supplies the
         * freshly verified pair id as [currentStoredId]; a missing or changed id aborts the move.
         */
        fun stillValid(currentSavedUrl: String?, currentStoredId: String?, expectedOldUrl: String, expectedId: String): Boolean =
            currentSavedUrl == expectedOldUrl && currentStoredId == expectedId
    }

    object Throttle {
        /** Only the saved address refused by this launch can bypass its next routine throttle. */
        fun skipOnceFor(refusedUrl: String?, savedUrl: String?): Boolean =
            refusedUrl != null && savedUrl != null && refusedUrl == savedUrl

        /** A refused launch gets one check now; routine foreground checks keep the five-minute process throttle. */
        fun mayRun(nowMs: Long, lastRunMs: Long?, skipOnce: Boolean = false): Boolean =
            skipOnce || lastRunMs == null || nowMs - lastRunMs >= CHECK_INTERVAL_MS
    }

    object Declines {
        /** A future-dated decline must not extend a refusal after the phone's wall clock moves backwards. */
        private fun isLive(declinedAtMs: Long, nowMs: Long): Boolean {
            val age = nowMs - declinedAtMs
            return age >= 0L && age < DECLINE_LIFETIME_MS
        }

        fun isDeclined(map: Map<String, Long>, candidateUrl: String, nowMs: Long): Boolean =
            map[candidateUrl]?.let { isLive(it, nowMs) } ?: false

        /** Prune expired entries, update this candidate, then keep the eight most recent. */
        fun record(map: Map<String, Long>, candidateUrl: String, nowMs: Long): Map<String, Long> {
            val live = map.filterValues { isLive(it, nowMs) }.toMutableMap()
            live[candidateUrl] = nowMs
            return live.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(MAX_DECLINES).associate { it.key to it.value }
        }

        /**
         * One canonical private URL and wall-clock millisecond value per line, sorted by URL for stable storage.
         * Reject the whole saved value on any bad line; partial data must not silently keep a stale refusal.
         */
        fun encode(map: Map<String, Long>): String = buildString {
            append("v1")
            for ((url, atMs) in map.toSortedMap()) {
                append('\n').append(url).append('\t').append(atMs)
            }
        }

        fun decode(text: String?): Map<String, Long> {
            if (text == null) return emptyMap()
            val lines = text.split('\n')
            if (lines.firstOrNull() != "v1" || lines.size > MAX_DECLINES + 1) return emptyMap()
            val result = LinkedHashMap<String, Long>()
            for (line in lines.drop(1)) {
                val parts = line.split('\t')
                if (parts.size != 2) return emptyMap()
                val found = DiscoveryRules.parseFoundUrl(parts[0]) ?: return emptyMap()
                if (!DiscoveryRules.isPrivateIpv4(found.first)) return emptyMap()
                val atMs = parts[1].toLongOrNull() ?: return emptyMap()
                if (atMs < 0L || result.put(parts[0], atMs) != null) return emptyMap()
            }
            return result
        }
    }
}
