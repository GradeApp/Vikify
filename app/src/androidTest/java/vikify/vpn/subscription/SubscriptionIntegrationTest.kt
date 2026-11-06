package vikify.vpn.subscription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import vikify.vpn.core.SecurePrefs

@RunWith(AndroidJUnit4::class)
class SubscriptionIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = "https://subscription.example/sub/abcdefghijklmnop"
    private val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private fun reality(port: Int = 443) = "vless://$uuid@edge.example:$port?type=tcp&security=reality&sni=www.apple.com&fp=qq&pbk=${"A".repeat(43)}&sid=aabb&spx=%2F&flow=xtls-rprx-vision#REALITY-$port"
    private fun tls(type: String) = "vless://$uuid@web.example:443?type=$type&security=tls&path=%2Fcdn%2Fmedia&host=web.example&sni=web.example&fp=chrome" +
        (if (type == "xhttp") "&alpn=h2&mode=packet-up" else "") + "#" + type
    private fun allProfiles() = listOf(reality(), reality(8443), reality(8445), tls("xhttp"), tls("ws")).joinToString("\n")

    @Before fun reset() { SecurePrefs(context).removeAccess() }
    @After fun cleanup() { SecurePrefs(context).removeAccess(); SubscriptionRefreshJob.cancel(context) }

    @Test fun generatedXrayConfigPreservesAllFiveProxyTransports() {
        for (profile in VlessParser.parseSubscription(allProfiles())) {
            val config = JSONObject(XrayConfigBuilder.build(profile))
            assertEquals("127.0.0.1", config.getJSONArray("inbounds").getJSONObject(0).getString("listen"))
            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            val server = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            assertEquals(profile.port, server.getInt("port"))
            assertEquals(uuid, server.getJSONArray("users").getJSONObject(0).getString("id"))
            val stream = outbound.getJSONObject("streamSettings")
            assertEquals(profile.transport, stream.getString("network"))
            when (profile.transport) {
                "tcp" -> {
                    assertEquals("qq", stream.getJSONObject("realitySettings").getString("fingerprint"))
                    assertEquals("/", stream.getJSONObject("realitySettings").getString("spiderX"))
                }
                "xhttp" -> {
                    assertEquals("packet-up", stream.getJSONObject("xhttpSettings").getString("mode"))
                    assertEquals("/cdn/media", stream.getJSONObject("xhttpSettings").getString("path"))
                    assertEquals("h2", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
                }
                "ws" -> assertEquals("web.example", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
            }
        }
    }

    @Test fun failedReplacementKeepsStoredSubscriptionAndSelection() = runBlocking {
        val repository = SubscriptionRepository(context) { allProfiles() }
        repository.importSubscription(url)
        val selection = repository.state.value.profiles[3].id
        repository.select(selection)
        for (reason in listOf(SubscriptionError.NETWORK, SubscriptionError.TLS)) {
            val failed = SubscriptionRepository(context) { throw SubscriptionException(reason) }
            try { failed.importSubscription(url.replace("abcdefghijklmnop", "qrstuvwxyzabcdef")); fail() }
            catch (error: SubscriptionException) { assertEquals(reason, error.reason) }
        }
        val restored = SubscriptionRepository(context).state.value
        assertEquals(url, restored.snapshot?.url)
        assertEquals(selection, restored.selectedId)
        assertTrue(restored.canConnect)
    }

    @Test fun revokedLinkDisablesNewConnectionsAcrossRestartUntilRefreshSucceeds() = runBlocking {
        SubscriptionRepository(context) { allProfiles() }.importSubscription(url)
        val repository = SubscriptionRepository(context) { throw SubscriptionException(SubscriptionError.UNAVAILABLE) }
        try { repository.refresh(); fail() } catch (_: SubscriptionException) { }
        assertFalse(repository.state.value.canConnect)
        assertEquals(5, repository.state.value.profiles.size)
        assertFalse(SubscriptionRepository(context).state.value.canConnect)
        val renewed = SubscriptionRepository(context) { allProfiles() }
        renewed.refresh()
        assertTrue(renewed.state.value.canConnect)
    }

    @Test fun updatePreservesSelectedEndpointAndStoresRotatedCredentials() = runBlocking {
        var response = allProfiles()
        val repository = SubscriptionRepository(context) { response }
        repository.importSubscription(url)
        val before = repository.state.value.profiles[3]
        repository.select(before.id)
        response = response.replace(uuid, "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        repository.refresh()
        assertEquals(before.id, repository.state.value.selectedId)
        assertNotEquals(before.revision, repository.state.value.selectedProfile?.revision)
        assertEquals(repository.state.value, SubscriptionRepository(context).state.value)
    }

    @Test fun cancelledImportCannotOverwriteWorkingAccess() = runBlocking {
        SubscriptionRepository(context) { allProfiles() }.importSubscription(url)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val repository = SubscriptionRepository(context) { entered.complete(Unit); response.await() }
        val importing = launch { repository.importSubscription(url.replace("abcdefghijklmnop", "qrstuvwxyzabcdef")) }
        entered.await()
        importing.cancelAndJoin()
        assertEquals(url, SubscriptionRepository(context).state.value.snapshot?.url)
    }

    @Test fun selectionRemainsResponsiveDuringRefreshAndSurvivesItsResult() = runBlocking {
        SubscriptionRepository(context) { allProfiles() }.importSubscription(url)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val repository = SubscriptionRepository(context) { entered.complete(Unit); response.await() }
        val selected = repository.state.value.profiles[3].id
        val refreshing = launch { repository.refresh() }
        try {
            entered.await()
            // The response remains gated until after the mutation, so a held
            // mutex still fails. Allow slow emulator keystore/storage work.
            withTimeout(10_000) { repository.select(selected) }
            assertEquals(selected, repository.state.value.selectedId)
            response.complete(allProfiles().replace(uuid, "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
            refreshing.join()
            assertEquals(selected, repository.state.value.selectedId)
            assertEquals("bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee", repository.state.value.selectedProfile?.uuid)
        } finally { refreshing.cancelAndJoin() }
    }

    @Test fun removedAccessCannotBeRestoredByAnOlderRefresh() = runBlocking {
        SubscriptionRepository(context) { allProfiles() }.importSubscription(url)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val repository = SubscriptionRepository(context) { entered.complete(Unit); response.await() }
        val refreshing = launch { repository.refresh() }
        try {
            entered.await()
            withTimeout(10_000) { repository.remove() }
            response.complete(allProfiles()); refreshing.join()
            assertTrue(repository.state.value.profiles.isEmpty())
            assertTrue(SubscriptionRepository(context).state.value.profiles.isEmpty())
        } finally { refreshing.cancelAndJoin() }
    }

    @Test fun lateRevocationCannotDisableAReplacementSubscription() = runBlocking {
        SubscriptionRepository(context) { allProfiles() }.importSubscription(url)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val repository = SubscriptionRepository(context) { entered.complete(Unit); response.await() }
        val refreshing = launch { repository.refresh() }
        try {
            entered.await()
            withTimeout(10_000) { repository.importSubscription(tls("xhttp")) }
            response.completeExceptionally(SubscriptionException(SubscriptionError.UNAVAILABLE)); refreshing.join()
            assertTrue(repository.state.value.canConnect)
            assertTrue(repository.state.value.snapshot!!.local)
            assertEquals(1, repository.state.value.profiles.size)
            assertNull(repository.state.value.error)
        } finally { refreshing.cancelAndJoin() }
    }

    @Test fun legacyUuidSurvivesUntilConfirmedRemoval() = runBlocking {
        SecurePrefs(context).setUuid(uuid)
        val interruptedConfig = java.io.File(context.filesDir, "xray_config.json")
        interruptedConfig.writeText("interrupted session test data")
        val repository = SubscriptionRepository(context)
        assertEquals(4, repository.state.value.profiles.size)
        assertTrue(repository.state.value.canConnect)
        repository.remove()
        assertFalse(interruptedConfig.exists())
        assertTrue(SubscriptionRepository(context).state.value.profiles.isEmpty())
        assertEquals("", SecurePrefs(context).getUuid(""))
    }

    @Test fun localImportPersistsWithoutSchedulingOrMakingNetworkRequests() = runBlocking {
        var calls = 0
        val repository = SubscriptionRepository(context) { calls++; allProfiles() }
        repository.importSubscription(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(allProfiles().toByteArray()))
        assertTrue(repository.state.value.snapshot!!.local)
        assertEquals("", repository.state.value.snapshot!!.url)
        val selected = repository.state.value.profiles[3].id
        repository.select(selected)
        repository.refresh()
        assertEquals(0, calls)
        assertEquals(repository.state.value, SubscriptionRepository(context).state.value)
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        assertFalse(scheduler.allPendingJobs.any { it.service.className == SubscriptionRefreshJob::class.java.name })
    }

    @Test fun mixedImportRequiresConfirmationAndRefreshCannotDiscardSelectedProfile() = runBlocking {
        var response = allProfiles()
        val repository = SubscriptionRepository(context) { response }
        repository.importSubscription(url)
        repository.select(repository.state.value.profiles[3].id)
        val original = repository.state.value
        response = allProfiles() + "\ntrojan://example@other.example:443#Other"
        val prepared = repository.prepareImport("https://other.example/api/list?token=example")
        assertEquals(original, repository.state.value)
        assertEquals(1, prepared.parsed.issues.size)
        repository.commitImport(prepared)
        assertEquals(original.selectedId, repository.state.value.selectedId)
        assertEquals(1, repository.state.value.snapshot!!.skippedProfiles)
        assertEquals(repository.state.value, SubscriptionRepository(context).state.value)
        response = reality() + "\ninvalid-profile"
        try { repository.refresh(); fail() } catch (e: SubscriptionException) { assertEquals(SubscriptionError.PARTIAL, e.reason) }
        assertEquals(original.selectedId, repository.state.value.selectedId)
        assertEquals(5, repository.state.value.profiles.size)
    }
}
