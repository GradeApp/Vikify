package vikify.vpn.subscription

import org.junit.Assert.*
import org.junit.Test

class VlessParserTest {
    private val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val key = "A".repeat(43)
    private fun reality(port: Int = 443) = "vless://$uuid@edge.example:$port?type=tcp&security=reality&sni=www.apple.com&fp=qq&pbk=$key&sid=aabb&spx=%2F&flow=xtls-rprx-vision#Main"
    private fun tls(type: String) = "vless://$uuid@web.example:443?type=$type&security=tls&path=%2Fcdn%2Fmedia&host=web.example&sni=web.example&fp=chrome" +
        (if (type == "xhttp") "&alpn=h2&mode=packet-up" else "") + "#" + type

    @Test fun readsProxyFiveProfileOrderAndTransportParameters() {
        val profiles = VlessParser.parseSubscription(listOf(reality(), reality(8443), reality(8445), tls("xhttp"), tls("ws")).joinToString("\r\n"))
        assertEquals(listOf(443, 8443, 8445, 443, 443), profiles.map { it.port })
        assertEquals(listOf("tcp", "tcp", "tcp", "xhttp", "ws"), profiles.map { it.transport })
        assertEquals("qq", profiles[0].parameters["fp"])
        assertEquals("/", profiles[0].parameters["spx"])
        assertEquals("/cdn/media", profiles[3].parameters["path"])
        assertEquals("packet-up", profiles[3].parameters["mode"])
        assertEquals("h2", profiles[3].parameters["alpn"])
    }

    @Test fun rotationPreservesIdentityButChangesRevision() {
        val before = VlessParser.parse(reality())
        val after = VlessParser.parse(reality().replace(uuid, "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee").replace("sni=www.apple.com", "sni=www.bing.com"))
        assertEquals(before.id, after.id)
        assertNotEquals(before.revision, after.revision)
        assertFalse(before.toString().contains(uuid))
        assertFalse(before.toString().contains(key))
    }

    @Test fun decodesNamesAndIpv6WithoutChangingLiteralPlus() {
        val profile = VlessParser.parse(reality().replace("edge.example", "[2001:db8::1]").replace("#Main", "#%D0%A2%D0%B5%D1%81%D1%82+TLS"))
        assertEquals("2001:db8::1", profile.host)
        assertEquals("Тест+TLS", profile.name)
    }

    @Test fun rejectsUnsafeOrPartiallySupportedResponses() {
        for (body in listOf("", reality() + "\n<html>error</html>", reality() + "\n" + reality(),
            reality().replace("#Main", "&unknown=1#Main"), reality().replace("fp=qq", "fp=qq&fp=chrome"),
            reality().replace("type=tcp", "type=grpc"), tls("ws").replace("security=tls", "security=none"),
            reality().replace("pbk=$key", "pbk=bad"), reality().replace("sid=aabb", "sid=abc"),
            tls("xhttp").replace("mode=packet-up", "mode=unknown"))) {
            val error = assertThrows(SubscriptionException::class.java) { VlessParser.parseSubscription(body) }
            assertEquals(SubscriptionError.FORMAT, error.reason)
            assertFalse(error.toString().contains(uuid))
        }
    }

    @Test fun recognizesSpecializedConfigurationWithoutSilentlyDroppingRouting() {
        assertEquals(SubscriptionError.SPECIALIZED, assertThrows(SubscriptionException::class.java) {
            VlessParser.parseSubscription("{\"route\": {}}")
        }.reason)
    }

    @Test fun subscriptionUrlAllowsProviderPathsAndQueriesButRejectsUnsafeUrls() {
        val valid = "https://fall.example/sub/abcdefghijklmnop"
        assertEquals(valid, SubscriptionUrl.parse(" $valid ").toString())
        for (bad in listOf(valid.replace("https", "http"), valid.replace("fall.example", "secret@fall.example"),
            "$valid#fragment", "https://fall.example:0/other", "https://fall.example/\nother")) {
            assertEquals(SubscriptionError.URL, assertThrows(SubscriptionException::class.java) { SubscriptionUrl.parse(bad) }.reason)
        }
        for (url in listOf("$valid?token=x", "https://other.example/api/v1/client/subscribe?token=abc&format=base64", "$valid/reality"))
            assertEquals(url, SubscriptionUrl.parse(url).toASCIIString())
    }

    @Test fun supportsStandardAndUrlSafeBase64AndLocalInputs() {
        val body = reality() + "\n" + tls("ws")
        for (encoded in listOf(java.util.Base64.getEncoder().encodeToString(body.toByteArray()),
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray()))) {
            assertEquals(VlessParser.parseSubscription(body), VlessParser.parseSubscription(encoded.chunked(60).joinToString("\n")))
            assertTrue(SubscriptionInput.parse(encoded) is SubscriptionInput.Local)
        }
        assertTrue(SubscriptionInput.parse(reality()) is SubscriptionInput.Local)
        assertTrue(SubscriptionInput.parse("https://provider.example/api?token=abc") is SubscriptionInput.Remote)
    }

    @Test fun partialImportHasSafeLineNumbersAndNeedsExplicitAcceptance() {
        val body = reality() + "\ntrojan://secret@other.example:443#Other\n" + tls("ws") + "\n" + reality() + "\ninvalid"
        val parsed = VlessParser.inspectSubscription(body)
        assertEquals(2, parsed.profiles.size)
        assertEquals(listOf(2, 4, 5), parsed.issues.map { it.line })
        assertEquals(listOf(ImportIssueReason.UNSUPPORTED, ImportIssueReason.DUPLICATE, ImportIssueReason.INVALID), parsed.issues.map { it.reason })
        assertFalse(parsed.toString().contains("secret"))
        assertThrows(SubscriptionException::class.java) { VlessParser.parseSubscription(body) }
    }

    @Test fun safeDefaultsAndRawAliasDoNotChangeExistingEndpointIdentity() {
        assertEquals(VlessParser.parse(reality()).id, VlessParser.parse(reality().replace("type=tcp", "type=raw")).id)
        val defaults = VlessParser.parse("vless://$uuid@custom.example:443?type=ws&security=tls&allowInsecure=0#Custom")
        assertEquals("custom.example", defaults.parameters["sni"])
        assertEquals("/", defaults.parameters["path"])
        assertThrows(SubscriptionException::class.java) { VlessParser.parse(defaults.source.replace("allowInsecure=0", "allowInsecure=1")) }
    }

    @Test fun boundsAndInvalidEncodedDataAreRejectedWithoutLeakingCredentials() {
        for (body in listOf("A".repeat(256 * 1024 + 1), "////", java.util.Base64.getEncoder().encodeToString("{\"route\":{}}".toByteArray()))) {
            assertThrows(SubscriptionException::class.java) { VlessParser.inspectSubscription(body) }
        }
        assertThrows(SubscriptionException::class.java) { VlessParser.inspectSubscription((1..501).joinToString("\n") { "#comment" }) }
    }

    @Test fun redirectsAllowHttpsOnlyAndNeverInheritQueryAcrossHosts() {
        val current = SubscriptionUrl.parse("https://first.example/api/list?token=secret")
        assertEquals("https://first.example/new?key=x", SubscriptionRedirect.next(current, "/new?key=x").toString())
        assertEquals("https://second.example/list", SubscriptionRedirect.next(current, "https://second.example/list").toString())
        for (location in listOf(null, "", "http://other.example/list", "https://user:secret@other.example/list", "https://other.example/list#fragment"))
            assertThrows(SubscriptionException::class.java) { SubscriptionRedirect.next(current, location) }
    }
}
