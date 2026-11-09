import Foundation

// MovedServerRules.swift: pure decisions for finding the SAME saved server at a new local address. Discovery and a health
// reply identify a candidate, but neither proves ownership: the person must recognise the address and confirm it. The glue
// probes again at the tap and executes movePlan on its single writes queue. Foundation only so swiftc tests these rules.
enum MovedServerRules {
    // These are judgements, not measurements: a check at most every five minutes avoids a probe on every foreground event;
    // ten seconds bounds discovery, and two seconds bounds the one strict health probe.
    static let CHECK_INTERVAL_MS: Int64 = 5 * 60_000
    static let SEARCH_BOUND_MS: Int = 10_000
    static let PROBE_TIMEOUT_MS: Int = 2_000

    // A refusal is specific to one candidate address. Seven days gives the person time to fix a changed router lease, while
    // eight entries bound per-server storage even if the network keeps offering different addresses. Judgements, not measures.
    static let DECLINE_LIFETIME_MS: Int64 = 7 * 24 * 60 * 60_000
    static let MAX_DECLINES: Int = 8

    static let title = "Your server has a new address"
    static let notNowButton = "Not now"
    static let useNewAddressButton = "Use new address"

    enum Probe {
        case answered(id: String?)
        case noAnswer
    }

    enum Step: Equatable {
        case skip
        case learn(String)
        case stop
        case search(String)
    }

    /// A typed hostname, HTTPS URL or public IP must never be moved by local discovery. An answer without a usable id cannot
    /// establish identity. The stored address id wins over a stored pair id; a different occupant triggers a search for it
    /// and must never replace it, even when the pair is the only known identity.
    static func decide(savedUrl: String?, storedId: String?, pairId: String?, probe: Probe) -> Step {
        guard let saved = DiscoveryRules.parseFoundUrl(savedUrl), EndpointRules.isPrivateIpv4(saved.host) else {
            return .skip
        }
        let expected = storedId ?? pairId
        switch probe {
        case .answered(let id):
            guard let id = id, EndpointProbe.isPlausibleId(id) else { return .stop }
            if let wanted = expected, id != wanted { return .search(wanted) }
            return storedId == nil ? .learn(id) : .stop
        case .noAnswer:
            guard let wanted = expected else { return .stop }
            return .search(wanted)
        }
    }

    /// Discovery has already checked its announcement against a strict health reply. Still require the exact wanted UUID,
    /// a different canonical URL, and a private IPv4 literal before the glue may offer a move.
    static func acceptCandidate(wantedId: String, savedUrl: String, foundId: String, foundUrl: String) -> Bool {
        guard DiscoveryRules.matches(ServerHealth(id: foundId, name: nil), wantedId),
              let found = DiscoveryRules.parseFoundUrl(foundUrl), EndpointRules.isPrivateIpv4(found.host) else {
            return false
        }
        return foundUrl != savedUrl
    }

    /// Names and the short code are public display hints, not proof. The platform presents this as plain text; a rename
    /// does not block a move, but it is called out so the person can decide whether the address looks familiar.
    static func promptMessage(oldUrl: String, newUrl: String, offeredName: String?, storedName: String?, fingerprint: String) -> String {
        let name = offeredName.flatMap { $0.isEmpty ? nil : $0 } ?? DiscoveryRules.DEFAULT_NAME
        var lines = [
            "Nightlight found it again on your Wi-Fi.",
            "Saved address: \(oldUrl)",
            "New address: \(newUrl)",
            "Name: \(name)",
            "Code: \(fingerprint)"
        ]
        if let storedName = storedName, !storedName.isEmpty, storedName != offeredName {
            lines.append("The name is different from the one you saved (\"\(storedName)\").")
        }
        lines.append("Only use it if you recognise this address (check your server or router).")
        return lines.joined(separator: "\n")
    }

    enum MoveStep: Equatable {
        case clearNew(String)
        case addKnown(String)
        case setName(String, String)
        case setServerId(String, String)
        case carryPair(String, String, String)
        case setSavedUrl(String)
        case removeOld(String)
    }

    /// An address reused by another remembered server must lose all of its old per-address data before the new entry is
    /// written. Flip the saved URL after the new entry is ready; carry a pair only when it names the same server id.
    static func movePlan(oldUrl: String, newUrl: String, id: String, name: String?, knownServers: [String],
                         hasNewUrlLeftovers: Bool, pairUrl: String?, pairId: String?) -> [MoveStep] {
        var steps: [MoveStep] = []
        let clearNew = knownServers.contains(newUrl) || hasNewUrlLeftovers
        if clearNew { steps.append(.clearNew(newUrl)) }
        // Clearing a remembered destination removes its list entry, so it must be added again.
        steps.append(.addKnown(newUrl))
        if let name = name, !name.isEmpty { steps.append(.setName(newUrl, name)) }
        steps.append(.setServerId(newUrl, id))
        if let pairUrl = pairUrl, pairId == id { steps.append(.carryPair(newUrl, pairUrl, id)) }
        steps.append(.setSavedUrl(newUrl))
        steps.append(.removeOld(oldUrl))
        return steps
    }

    enum MoveGuard {
        /// The glue re-reads these values on the writes queue at the tap. If the id came from a verified Tailscale pair
        /// because none was stored, it passes that effective id as currentStoredId. A switch while the prompt was open aborts.
        static func stillValid(currentSavedUrl: String?, currentStoredId: String?, expectedOldUrl: String, expectedId: String) -> Bool {
            return currentSavedUrl == expectedOldUrl && currentStoredId == expectedId
        }
    }

    enum Throttle {
        /// A process runs one check at most every five minutes. Exactly five minutes is allowed; a missing first run is too.
        static func mayRun(nowMs: Int64, lastRunMs: Int64?, skipOnce: Bool = false) -> Bool {
            // A fresh launch refusal is stronger evidence than the routine foreground cadence; the caller consumes it once.
            if skipOnce { return true }
            guard let last = lastRunMs else { return true }
            let (elapsed, overflow) = nowMs.subtractingReportingOverflow(last)
            return !overflow && elapsed >= CHECK_INTERVAL_MS
        }
    }

    enum Declines {
        typealias Map = [String: Int64]

        private static func isFresh(_ time: Int64, nowMs: Int64) -> Bool {
            if time > nowMs { return false }
            let (age, overflow) = nowMs.subtractingReportingOverflow(time)
            return !overflow && age < DECLINE_LIFETIME_MS
        }

        /// Expiry is per candidate. A timestamp from the future is not a refusal on this clock.
        static func isDeclined(_ map: Map, candidateUrl: String, nowMs: Int64) -> Bool {
            guard let time = map[candidateUrl] else { return false }
            return isFresh(time, nowMs: nowMs)
        }

        /// Prune expired entries and retain the newest eight; URL order breaks equal-time ties for deterministic storage.
        static func record(_ map: Map, candidateUrl: String, nowMs: Int64) -> Map {
            var fresh = map.filter { isFresh($0.value, nowMs: nowMs) }
            fresh[candidateUrl] = nowMs
            let newest = fresh.sorted { left, right in
                if left.value != right.value { return left.value > right.value }
                return left.key < right.key
            }.prefix(MAX_DECLINES)
            return Dictionary(uniqueKeysWithValues: newest.map { ($0.key, $0.value) })
        }

        /// JSON sorted by URL is stable across dictionary iteration orders; unreadable storage fails closed to no declines.
        static func encode(_ map: Map) -> String {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            guard let data = try? encoder.encode(map), let text = String(data: data, encoding: .utf8) else { return "{}" }
            return text
        }

        static func decode(_ text: String) -> Map {
            guard let data = text.data(using: .utf8), let map = try? JSONDecoder().decode(Map.self, from: data) else {
                return [:]
            }
            return map
        }
    }
}
