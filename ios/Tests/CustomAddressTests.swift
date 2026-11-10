import Foundation

// The server announces only canonical origins. These rows mirror its CUSTOM_URL parser and Android's EndpointRulesTest.
func runCustomAddressTests() {
    let home = "http://192.168.1.50:4000"
    let custom = "https://nightlight-dev.example.com"
    let tailscale = "https://nightlight.example.ts.net"
    let id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"

    test("custom address: canonical HTTPS DNS origins are accepted") {
        let label63 = String(repeating: "a", count: 63)
        let exactly255 = "https://" + label63 + "." + label63 + "." + label63 + "." + String(repeating: "b", count: 51) + ".com"
        checkEqual(exactly255.utf16.count, 255, "length fixture")
        check(EndpointRules.isCustomUrl(exactly255), "the 255-character bound was refused")
        for url in [custom, "https://example.com:8443", "https://xn--bcher-kva.example", "https://\(label63).example.com"] {
            check(EndpointRules.isCustomUrl(url), "refused " + url)
            check(EndpointRules.isSecureAddress(url), "secure slot refused " + url)
            checkEqual(EndpointRules.validatedSlot(url), url)
            checkEqual(EndpointRules.addressKind(url), "custom")
        }
        check(!EndpointRules.isCustomUrl(tailscale), "Tailscale was classified as custom")
        check(EndpointRules.isSecureAddress(tailscale), "Tailscale stopped being a secure slot")
        checkEqual(EndpointRules.addressKind(tailscale), "tailscale")
        check(EndpointRules.isCustomUrl("https://example.com:1"), "lowest port")
        check(EndpointRules.isCustomUrl("https://example.com:65535"), "highest port")
    }

    test("custom address: parser refusals match the server") {
        let label64 = String(repeating: "a", count: 64)
        let tooLong = "https://" + String(repeating: "a", count: 63) + "." + String(repeating: "b", count: 63) +
            "." + String(repeating: "c", count: 63) + "." + String(repeating: "d", count: 52) + ".com"
        checkEqual(tooLong.utf16.count, 256, "length fixture")
        let refused: [String?] = [nil, "", "HTTPS://example.com", "https://EXAMPLE.com", "https://example.com/",
            "https://example.com/path", "https://example.com?x=1", "https://example.com#f", "https://u@example.com",
            "https://example.com:0", "https://example.com:65536", "https://example.com:0443", "https://example.com:443",
            "https://127.0.0.1", "https://[::1]", "https://127.1", "https://0x7f.0.0.1", "https://1.2.3.0x4",
            "https://example.123", "https://localhost", "https://x.local", tailscale, "https://ts.net",
            "https://example", "https://example.com.", "https://ex..com", "https://ex_ample.com",
            "https://-ex.com", "https://ex-.com", "https://\(label64).com", tooLong,
            "http://example.com", "https://example.com\n"]
        for url in refused {
            check(!EndpointRules.isCustomUrl(url), "accepted " + (url ?? "<nil>"))
        }
        checkEqual(EndpointRules.validatedSlot("https://example.com/"), nil)
        checkEqual(EndpointRules.addressKind(nil), nil)
    }

    test("custom address: page and reconnect use the validated secure slot") {
        checkEqual(EndpointRules.checkPage(url: custom, primary: home, savedTailscale: nil, page: home, shown: home + "/"), .ok)
        checkEqual(EndpointRules.checkPage(url: custom, primary: home, savedTailscale: custom, page: custom, shown: custom + "/#/live"), .ok)
        checkEqual(EndpointRules.checkPage(url: custom, primary: home, savedTailscale: custom, page: custom,
                                           shown: "https://nightlight-dev.example.com.attacker.test/"), .notThisPage)
        check(EndpointRules.canReconnectViaTailscale(knownServers: [home], url: home, tailscaleUrl: custom, wantedId: id, liveId: id),
              "remembered custom slot with matching id was refused")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: [home], url: home, tailscaleUrl: custom, wantedId: id, liveId: "other"),
              "another server's id was accepted")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: [], url: home, tailscaleUrl: custom, wantedId: id, liveId: id),
              "an unknown server was reconnected")
    }

    test("custom address: launch gets the full grace without a VPN") {
        checkEqual(EndpointRules.tailscaleGraceMs(vpnPresent: false, slotUrl: custom), 1_000)
        checkEqual(EndpointRules.tailscaleGraceMs(vpnPresent: true, slotUrl: custom), 1_000)
        checkEqual(EndpointRules.tailscaleGraceMs(vpnPresent: false, slotUrl: tailscale), 250)
        checkEqual(EndpointRules.tailscaleGraceMs(vpnPresent: true, slotUrl: tailscale), 1_000)
    }

    test("custom address: prompt names the full host and uses kind-specific wording") {
        checkEqual(PromptGate.promptBody(savedAddress: home, candidate: custom),
                   "Your server at \(home) offered this secure address:\n\nnightlight-dev.example.com\n\n" +
                   "This is your own address for this server. Continue only if you set up this address yourself.")
        checkEqual(PromptGate.promptBody(savedAddress: home, candidate: tailscale),
                   "Your server at \(home) offered this secure address:\n\nnightlight.example.ts.net\n\n" +
                   "Only use it if you just turned on the secure address in your server's Settings \u{2192} Remote Access and this name matches.")
        check(PromptGate.promptBody(savedAddress: home, candidate: "https://example.com:8443").contains("example.com:8443"),
              "the non-default port was hidden")
    }
}
