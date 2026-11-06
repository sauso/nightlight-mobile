package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may be saved as a server's Tailscale address. It arrives from the server the app has open, so the rule is
 * strict: exactly `https://<name>.ts.net`. Each refusal below is a near miss of the accepted shape.
 */
class EndpointRulesTest {

    @Test
    fun `a plain https ts-net name is accepted, with or without extra labels`() {
        assertTrue(EndpointRules.isTailscaleUrl("https://nightlight-1.example-tailnet.ts.net"))
        assertTrue(EndpointRules.isTailscaleUrl("https://nightlight.tail1234.ts.net"))
        assertTrue(EndpointRules.isTailscaleUrl("https://a.ts.net"))
    }

    @Test
    fun `anything that is not exactly that shape is refused`() {
        val refused = listOf(
            null,
            "",
            "http://nightlight.example.ts.net", // not https
            "https://nightlight.example.ts.net:8443", // a port
            "https://nightlight.example.ts.net/", // a path (even the root one)
            "https://nightlight.example.ts.net/api",
            "https://nightlight.example.ts.net?x=1",
            "https://nightlight.example.ts.net#frag",
            "https://user@nightlight.example.ts.net", // credentials
            "https://nightlight.example.ts.net.evil.example", // a longer name that merely contains ts.net
            "https://nightlight.example.ts.net.", // trailing dot
            "https://nots.net", // ends in ts.net but not .ts.net
            "https://ts.net", // no name before it
            "https://.ts.net",
            "https://-a.example.ts.net", // a label may not start or end with a hyphen
            "https://a-.example.ts.net",
            "https://100.64.0.1", // an address, not a name
            "https://[fd7a:115c:a1e0::1]",
            "https://NIGHTLIGHT.example.ts.net", // the server sends lower case
            "https://nightlight .example.ts.net",
            "https://night_light.example.ts.net",
            "HTTPS://nightlight.example.ts.net",
            " https://nightlight.example.ts.net",
        )
        for (value in refused) assertFalse("accepted: $value", EndpointRules.isTailscaleUrl(value))
    }

    @Test
    fun `a name longer than 255 characters is refused`() {
        val label = "a".repeat(63)
        val tooLong = "https://$label.$label.$label.$label.ts.net" // 8 + 4*64 + 6 = 270
        assertTrue(tooLong.length > 255)
        assertFalse(EndpointRules.isTailscaleUrl(tooLong))
        assertTrue(EndpointRules.isTailscaleUrl("https://$label.$label.ts.net"))
    }

    @Test
    fun `a label longer than 63 characters is refused`() {
        assertFalse(EndpointRules.isTailscaleUrl("https://${"a".repeat(64)}.ts.net"))
        assertTrue(EndpointRules.isTailscaleUrl("https://${"a".repeat(63)}.ts.net"))
    }

    @Test
    fun `each saved server has its own key, so one server's address is never read for another`() {
        assertEquals("tailscale_for:http://192.168.1.50:4000", EndpointRules.key("http://192.168.1.50:4000"))
        assertFalse(EndpointRules.key("http://192.168.1.50:4000") == EndpointRules.key("http://192.168.1.51:4000"))
    }
}
