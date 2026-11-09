import Foundation

/// Rules for offering a server announced on the local network. An announcement and a health reply can both be
/// forged, so these checks fail closed. Discovery is not authentication: a person's tap and the id check again
/// when saving are still required; typing the address is the stronger path.
enum DiscoveryRules {
    /// An unusable display name falls back to the same word the server uses by default.
    static let DEFAULT_NAME = "Nightlight"

    // The server's instance name contains the first eight ASCII letters and digits of its id, so a person can
    // compare the card with that name. This is a visual hint, not proof of ownership.
    static let FINGERPRINT_CHARS = 8

    /// Exact equality: the health reply must identify the same UUID-shaped server as the TXT announcement.
    static func matches(_ health: ServerHealth?, _ txtId: String?) -> Bool {
        guard let health = health, EndpointProbe.isPlausibleId(txtId) else { return false }
        return health.id == txtId
    }

    /// Same derivation as the server's instance name: lower-case, ASCII a-z0-9 only, first eight.
    static func fingerprint(_ id: String?) -> String {
        guard let id = id else { return "" }
        var result = ""
        for scalar in id.lowercased().unicodeScalars {
            let value = scalar.value
            if (value >= 97 && value <= 122) || (value >= 48 && value <= 57) {
                result.unicodeScalars.append(scalar)
                if result.unicodeScalars.count == FINGERPRINT_CHARS { break }
            }
        }
        return result
    }

    /// The TXT id, or nil for a record format or id we cannot trust enough to probe. TXT keys are case-insensitive
    /// (RFC 6763); two differently cased id or version keys are ambiguous and refused. `Data?` preserves a TXT key
    /// with no value; Part B must check how NWTXTRecord exposes that distinction (unsure until the glue is built).
    /// The dictionary represents distinct spellings of a key, as Android's attribute map does.
    static func txtId(from attributes: [String: Data?]?) -> String? {
        guard let attributes = attributes else { return nil }
        var versions = 0
        var version: Data? = nil
        var ids: [Data?] = []
        for (key, value) in attributes {
            if key.lowercased() == "v" {
                versions += 1
                version = value
            } else if key.lowercased() == "id" {
                ids.append(value)
            }
        }
        if versions > 1 { return nil }
        if versions == 1 {
            guard let value = version, String(data: value, encoding: .utf8) == "1" else { return nil }
        }
        guard ids.count == 1, let raw = ids[0], let id = String(data: raw, encoding: .utf8),
              EndpointProbe.isPlausibleId(id) else { return nil }
        return id
    }

    /// A found server is offered as explicit HTTP to its literal address. This server's certificate does not
    /// match a bare IP, so the typed address's HTTPS-then-HTTP logic would only delay a found server.
    static func serverUrl(_ host: String, _ port: Int) -> String {
        return "http://\(host):\(port)"
    }

    /// NWBrowser may omit the final dot, while NetService expects a fully qualified Bonjour type.
    /// Keep the browser's original coordinates for state keys; normalize only at the resolve boundary.
    static func netServiceType(_ type: String) -> String {
        return type.hasSuffix(".") ? type : type + "."
    }

    /// NetService expects a dotted domain; an empty browser domain means the local Bonjour domain.
    static func netServiceDomain(_ domain: String) -> String {
        if domain.isEmpty { return "local." }
        return domain.hasSuffix(".") ? domain : domain + "."
    }

    /// Accept only the canonical URL that `serverUrl` makes: strict IPv4, port 1...65535 without leading zero,
    /// and no path, credentials, query or fragment. Whether the address may be contacted is the probe's policy.
    static func parseFoundUrl(_ url: String?) -> (host: String, port: Int)? {
        guard let url = url, url.hasPrefix("http://") else { return nil }
        let rest = url.dropFirst("http://".count)
        guard let colon = rest.lastIndex(of: ":"), colon != rest.startIndex else { return nil }
        let host = String(rest[..<colon])
        let portText = String(rest[rest.index(after: colon)...])
        guard EndpointRules.parseIpv4(host) != nil else { return nil }
        let bytes = Array(portText.utf8)
        guard !bytes.isEmpty, bytes.count <= 5, bytes.allSatisfy({ $0 >= 48 && $0 <= 57 }),
              let port = Int(portText), port >= 1, port <= 65535 else { return nil }
        return serverUrl(host, port) == url ? (host, port) : nil
    }
}
