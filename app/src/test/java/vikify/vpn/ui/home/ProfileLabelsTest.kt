package vikify.vpn.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vikify.vpn.R
import vikify.vpn.subscription.SubscriptionSnapshot
import vikify.vpn.subscription.SubscriptionState
import vikify.vpn.subscription.VlessParser

class ProfileLabelsTest {
    private val line = "vless://aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee@edge.example:443" +
        "?type=xhttp&security=tls&sni=edge.example&path=%2Ftest#VLESS-XHTTP-TLS"
    private val profile = VlessParser.parse(line)
    private fun storeAt(host: String) = SubscriptionState(
        SubscriptionSnapshot("https://$host/sub/abcdefghijklmnop", line, 0, profile.id),
        listOf(profile), profile.id
    )

    @Test fun knownLabelPreservesTheOriginalProfileAndSelection() {
        val store = storeAt("fall.94043.net")
        assertEquals(R.string.profile_alternative_xhttp, store.profileLabel(profile))
        assertEquals("VLESS-XHTTP-TLS", store.selectedProfile?.name)
        assertEquals(profile.id, store.selectedId)
    }

    @Test fun otherSubscriptionsAndProviderDefinedNamesKeepTheirTitles() {
        assertNull(storeAt("fall.94043.net.example").profileLabel(profile))
        assertNull(SubscriptionState().profileLabel(profile))
        assertNull(storeAt("fall.94043.net").profileLabel(profile.copy(name = "Рабочий сервер")))
    }

    @Test fun addedServersKeepTheSelectedXhttpIdentityAndOriginalDetails() {
        fun reality(name: String, port: Int) = VlessParser.parse(
            "vless://aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee@edge.example:$port" +
                "?type=tcp&security=reality&sni=edge.example&pbk=${"a".repeat(43)}#$name"
        )
        val original = listOf(
            reality("VLESS-REALITY-443-MAIN", 443),
            reality("VLESS-REALITY-8443-RESERVE", 8443),
            reality("VLESS-REALITY-8445-RESERVE", 8445),
            profile,
            VlessParser.parse(line.replace("type=xhttp", "type=ws")
                .replace("VLESS-XHTTP-TLS", "VLESS-WS-TLS-FALLBACK"))
        )
        val auto = reality("Авто", 9443)
        val reserve = reality("WEB-RESERVE", 9444)
        val expanded = listOf(auto, original[0], reserve) + original.drop(1)
        val restored = VlessParser.parseSubscription(expanded.joinToString("\n") { it.source })
        val before = storeAt("fall.94043.net").copy(profiles = original)
        val after = before.copy(profiles = restored)

        assertEquals(7, after.profiles.size)
        assertEquals(before.selectedProfile, after.selectedProfile)
        assertEquals(original.map { it.id }, restored.filter { it.id in original.map { p -> p.id } }.map { it.id })
        assertEquals(original.map { before.profileLabel(it) }, original.map { after.profileLabel(it) })
        assertEquals(R.string.profile_automatic, after.profileLabel(restored[0]))
        assertEquals(R.string.profile_backup_server, after.profileLabel(restored[2]))
        assertEquals(listOf("Авто", "WEB-RESERVE"), listOf(restored[0].name, restored[2].name))
        assertEquals(listOf(9443, 9444), listOf(restored[0].port, restored[2].port))
        assertEquals("VLESS · REALITY", restored[2].protocolLabel)
    }

    @Test fun optionalSecondEdgeHasItsOwnLabelOnlyForThisProvider() {
        val secondEdge = profile.copy(name = "EDGE-2")
        assertEquals(R.string.profile_secondary_server, storeAt("fall.94043.net").profileLabel(secondEdge))
        assertNull(storeAt("another.example").profileLabel(secondEdge))
    }
}
