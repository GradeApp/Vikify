package vikify.vpn.diagnostics

import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import vikify.vpn.subscription.SubscriptionSnapshot
import vikify.vpn.subscription.SubscriptionState
import vikify.vpn.subscription.VlessParser
import vikify.vpn.ui.home.ActiveProfile
import vikify.vpn.ui.home.ConnectionPhase
import vikify.vpn.ui.home.ConnectionState

class DiagnosticRunnerTest {
    private val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val secret = "canary-private-token-DO-NOT-EXPORT"
    private val source = "vless://$uuid@private-canary.example:443?type=xhttp&security=tls&sni=private-sni.example&path=%2F$secret#Private-Alice"
    private val profile = VlessParser.parse(source)
    private val store = SubscriptionState(
        snapshot = SubscriptionSnapshot("https://private-provider.example/sub/$secret", source, 7, profile.id),
        profiles = listOf(profile), selectedId = profile.id)
    private val connected = ConnectionState(ConnectionPhase.CONNECTED, ActiveProfile(profile))

    private open inner class FakeNetwork : DiagnosticNetwork {
        var networkState = DiagnosticNetworkState(true, false)
        val calls = mutableListOf<String>()
        var fetchRoute: DiagnosticRoute? = null
        var payload = source
        var failure: DiagnosticFailure? = null
        override fun state() = networkState
        override suspend fun fetch(url: String, route: DiagnosticRoute): DiagnosticPayload {
            calls += "fetch"; fetchRoute = route
            failure?.let { throw it }
            return DiagnosticPayload(payload, 200)
        }
        override suspend fun resolve(host: String): List<InetAddress> {
            calls += "dns"; return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        override suspend fun connect(addresses: List<InetAddress>, port: Int) { calls += "tcp" }
        override suspend fun proxyHttps() { calls += "proxy" }
    }
    private fun runner(network: DiagnosticNetwork) = DiagnosticRunner(network, 25, 33, { 100 })
    private fun DiagnosticReportData.at(stage: DiagnosticStage) = events.single { it.stage == stage }

    @Test fun visibleVpnOwnershipFailsClosedForForeignOrUnknownOwners() {
        assertFalse(hasUnconfirmedVpn(emptyList(), 12345))
        assertFalse(hasUnconfirmedVpn(listOf(12345), 12345))
        assertTrue(hasUnconfirmedVpn(listOf(54321), 12345))
        assertTrue(hasUnconfirmedVpn(listOf(-1), 12345))
        assertTrue(hasUnconfirmedVpn(listOf(null), 12345))
        assertTrue(hasUnconfirmedVpn(listOf(12345, null), 12345))
    }

    @Test fun disconnectedDefaultIsLocalOnlyAndNeverUsesTheNetwork() = runBlocking {
        val network = FakeNetwork()
        val report = runner(network).run(store, ConnectionState())
        assertTrue(network.calls.isEmpty())
        assertEquals(6, report.events.size)
        assertEquals(DiagnosticCode.LOCAL_VALID, report.at(DiagnosticStage.LOCAL_FORMAT).code)
        for (stage in listOf(DiagnosticStage.SUBSCRIPTION, DiagnosticStage.DNS, DiagnosticStage.TCP)) {
            assertEquals(DiagnosticCode.DIRECT_NOT_ALLOWED, report.at(stage).code)
            assertEquals(DiagnosticStatus.NOT_TESTED, report.at(stage).status)
        }
        assertEquals(DiagnosticCode.CONNECT_REQUIRED, report.at(DiagnosticStage.PROXY_HTTPS).code)
        assertEquals(DiagnosticCode.TUN_NOT_VERIFIED, report.at(DiagnosticStage.ANDROID_VPN).code)
        assertEquals(DiagnosticStatus.NOT_TESTED, report.at(DiagnosticStage.ANDROID_VPN).status)
    }

    @Test fun matchingConnectedProfileUsesOnlyExplicitSocksByDefault() = runBlocking {
        val network = FakeNetwork()
        val report = runner(network).run(store, connected)
        assertEquals(listOf("fetch", "proxy"), network.calls)
        assertEquals(DiagnosticRoute.LOCAL_SOCKS, network.fetchRoute)
        assertEquals(DiagnosticStatus.PASS, report.at(DiagnosticStage.PROXY_HTTPS).status)
        assertEquals(DiagnosticStatus.NOT_TESTED, report.at(DiagnosticStage.ANDROID_VPN).status)
        assertEquals(store.snapshot?.body, source)
        assertEquals(profile.id, store.selectedId)
    }

    @Test fun directConsentAllowsSelectedEndpointChecksButNeverStartsVpn() = runBlocking {
        val network = FakeNetwork()
        val report = runner(network).run(store, ConnectionState(), allowDirect = true)
        assertEquals(listOf("fetch", "dns", "tcp"), network.calls)
        assertEquals(DiagnosticRoute.DIRECT, network.fetchRoute)
        assertEquals(DiagnosticStatus.PASS, report.at(DiagnosticStage.DNS).status)
        assertEquals(DiagnosticStatus.PASS, report.at(DiagnosticStage.TCP).status)
        assertEquals(DiagnosticCode.CONNECT_REQUIRED, report.at(DiagnosticStage.PROXY_HTTPS).code)
    }

    @Test fun foreignVpnPreventsEveryNetworkOperationEvenWithDirectConsent() = runBlocking {
        val network = FakeNetwork().apply { networkState = DiagnosticNetworkState(true, true) }
        val report = runner(network).run(store, connected, allowDirect = true)
        assertTrue(network.calls.isEmpty())
        assertEquals(DiagnosticCode.OTHER_VPN_ACTIVE, report.at(DiagnosticStage.SUBSCRIPTION).code)
        assertEquals(DiagnosticCode.OTHER_VPN_ACTIVE, report.at(DiagnosticStage.DNS).code)
        assertEquals(DiagnosticCode.OTHER_VPN_ACTIVE, report.at(DiagnosticStage.PROXY_HTTPS).code)
    }

    @Test fun networkChangeBeforeNextStepPreventsPhysicalRequests() = runBlocking {
        val network = object : FakeNetwork() {
            override suspend fun fetch(url: String, route: DiagnosticRoute): DiagnosticPayload {
                val result = super.fetch(url, route)
                networkState = DiagnosticNetworkState(true, true)
                return result
            }
        }
        val report = runner(network).run(store, connected, allowDirect = true)
        assertEquals(listOf("fetch"), network.calls)
        assertEquals(DiagnosticCode.OTHER_VPN_ACTIVE, report.at(DiagnosticStage.DNS).code)
    }

    @Test fun offlineIsNotMistakenForInvalidCredentials() = runBlocking {
        val network = FakeNetwork().apply { networkState = DiagnosticNetworkState(false, false) }
        val report = runner(network).run(store, ConnectionState(), allowDirect = true)
        assertTrue(network.calls.isEmpty())
        assertEquals(DiagnosticCode.OFFLINE, report.at(DiagnosticStage.SUBSCRIPTION).code)
        assertEquals(DiagnosticAction.CHECK_INTERNET, report.at(DiagnosticStage.SUBSCRIPTION).action)
        assertEquals(DiagnosticCode.OFFLINE, report.at(DiagnosticStage.DNS).code)
        assertEquals(DiagnosticCode.OFFLINE, report.at(DiagnosticStage.TCP).code)
        assertEquals(DiagnosticStatus.NOT_TESTED, report.at(DiagnosticStage.TCP).status)
        assertEquals(DiagnosticAction.CHECK_INTERNET, report.at(DiagnosticStage.TCP).action)
    }

    @Test fun resultsFromDifferentPhysicalNetworksAreNeverCombined() = runBlocking {
        val network = object : FakeNetwork() {
            init { networkState = DiagnosticNetworkState(true, false, 1) }
            override suspend fun resolve(host: String): List<InetAddress> {
                val result = super.resolve(host)
                networkState = DiagnosticNetworkState(true, false, 2)
                return result
            }
        }
        val report = runner(network).run(store, ConnectionState(), allowDirect = true)
        assertEquals(listOf("fetch", "dns"), network.calls)
        assertEquals(DiagnosticCode.NETWORK_CHANGED, report.at(DiagnosticStage.TCP).code)
    }

    @Test fun httpErrorsDoNotRevokeSavedSubscriptionOrInventExpiry() = runBlocking {
        for (status in listOf(401, 403, 404, 410, 429, 503)) {
            val network = FakeNetwork().apply { failure = DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_HTTP_ERROR,
                DiagnosticAction.ASK_PROVIDER, status) }
            val before = store.copy()
            val report = runner(network).run(store, ConnectionState(), allowDirect = true)
            assertEquals(status, report.at(DiagnosticStage.SUBSCRIPTION).httpStatus)
            assertEquals(DiagnosticCode.SUBSCRIPTION_HTTP_ERROR, report.at(DiagnosticStage.SUBSCRIPTION).code)
            assertEquals(before, store)
            assertFalse(DiagnosticReport.toText(report).contains("expired", ignoreCase = true))
            assertFalse(store.snapshot!!.unavailable)
        }
    }

    @Test fun mixedListIsExplainedWithoutExportingItsContents() = runBlocking {
        val network = FakeNetwork().apply { payload = source + "\nvless://invalid-$secret\nss://$secret" }
        val report = runner(network).run(store, ConnectionState(), allowDirect = true)
        val event = report.at(DiagnosticStage.SUBSCRIPTION)
        assertEquals(DiagnosticCode.SUBSCRIPTION_PARTIAL, event.code)
        assertEquals(DiagnosticStatus.WARNING, event.status)
        assertEquals(1, event.acceptedCount)
        assertEquals(2, event.skippedCount)
        assertSafe(report)
    }

    @Test fun malformedPayloadBecomesTypedErrorWithNoBodyInReport() = runBlocking {
        val network = FakeNetwork().apply { payload = "<html>$secret $uuid Private-Alice</html>" }
        val report = runner(network).run(store, ConnectionState(), allowDirect = true)
        assertEquals(DiagnosticCode.SUBSCRIPTION_FORMAT_ERROR, report.at(DiagnosticStage.SUBSCRIPTION).code)
        assertSafe(report)
    }

    @Test fun staleProfileRevisionIsNotTestedThroughAnotherRunningConfiguration() = runBlocking {
        val network = FakeNetwork()
        val stale = connected.copy(profile = connected.profile!!.copy(revision = "old-secret-revision"))
        val report = runner(network).run(store, stale)
        assertTrue(network.calls.isEmpty())
        assertEquals(DiagnosticCode.PROFILE_MISMATCH, report.at(DiagnosticStage.PROXY_HTTPS).code)
        assertEquals(DiagnosticStatus.NOT_TESTED, report.at(DiagnosticStage.PROXY_HTTPS).status)
    }

    @Test fun dnsFailurePreventsTcpAndDoesNotBecomeTunnelSuccess() = runBlocking {
        val network = object : FakeNetwork() {
            override suspend fun resolve(host: String): List<InetAddress> {
                calls += "dns"; throw DiagnosticFailure(DiagnosticCode.DNS_FAILED, DiagnosticAction.CHECK_INTERNET)
            }
        }
        val report = runner(network).run(store, ConnectionState(), allowDirect = true)
        assertFalse("tcp" in network.calls)
        assertEquals(DiagnosticStatus.NOT_TESTED, report.at(DiagnosticStage.TCP).status)
        assertEquals(DiagnosticCode.DNS_FAILED, report.at(DiagnosticStage.TCP).code)
        assertEquals(DiagnosticAction.CHECK_INTERNET, report.at(DiagnosticStage.TCP).action)
        assertEquals(DiagnosticStatus.NOT_TESTED, report.at(DiagnosticStage.ANDROID_VPN).status)
    }

    @Test fun emptyDnsAnswerNeverAttemptsTcpAndPreservesTheDnsFailure() = runBlocking {
        val network = object : FakeNetwork() {
            override suspend fun resolve(host: String): List<InetAddress> {
                calls += "dns"
                return emptyList()
            }
        }
        val report = runner(network).run(store, ConnectionState(), allowDirect = true)
        assertEquals(listOf("fetch", "dns"), network.calls)
        assertEquals(DiagnosticCode.DNS_FAILED, report.at(DiagnosticStage.DNS).code)
        assertEquals(DiagnosticStatus.FAIL, report.at(DiagnosticStage.DNS).status)
        assertEquals(DiagnosticStatus.NOT_TESTED, report.at(DiagnosticStage.TCP).status)
        assertEquals(DiagnosticCode.DNS_FAILED, report.at(DiagnosticStage.TCP).code)
        assertEquals(DiagnosticAction.CHECK_INTERNET, report.at(DiagnosticStage.TCP).action)
        assertEquals(DiagnosticRoute.NONE, report.at(DiagnosticStage.TCP).route)
    }

    @Test fun cancellationPropagatesWithoutLaterStagesAndLeavesInputsUnchanged() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val network = object : FakeNetwork() {
            override suspend fun fetch(url: String, route: DiagnosticRoute): DiagnosticPayload {
                calls += "fetch"; started.complete(Unit); awaitCancellation()
            }
        }
        val events = mutableListOf<DiagnosticEvent>()
        val before = store.copy()
        val job = launch { runner(network).run(store, ConnectionState(), true, events::add) }
        started.await(); job.cancelAndJoin()
        assertEquals(listOf("fetch"), network.calls)
        assertEquals(DiagnosticStatus.CANCELLED, events.last().status)
        assertEquals(DiagnosticStage.SUBSCRIPTION, events.last().stage)
        assertEquals(before, store)
    }

    @Test fun arbitraryExceptionMessagesCanNeverReachEitherReportFormat() = runBlocking {
        for (failure in listOf(RuntimeException("$secret $uuid"), SSLException(secret), SocketTimeoutException(secret))) {
            val network = object : FakeNetwork() {
                override suspend fun fetch(url: String, route: DiagnosticRoute): DiagnosticPayload { throw failure }
            }
            val report = runner(network).run(store, ConnectionState(), true)
            assertEquals(DiagnosticStatus.FAIL, report.at(DiagnosticStage.SUBSCRIPTION).status)
            assertSafe(report)
        }
    }

    private fun assertSafe(report: DiagnosticReportData) {
        for (rendered in listOf(DiagnosticReport.toText(report), DiagnosticReport.toJson(report), report.toString())) {
            for (value in listOf(uuid, secret, profile.id, profile.revision, "Private-Alice", "private-canary.example",
                "private-provider.example", "private-sni.example", "vless://", "https://", "127.0.0.1")) {
                assertFalse("Sensitive or identifying field escaped the allowlist", rendered.contains(value))
            }
        }
    }
}
