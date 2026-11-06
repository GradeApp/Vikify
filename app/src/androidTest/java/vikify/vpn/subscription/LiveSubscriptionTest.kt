package vikify.vpn.subscription

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import vikify.vpn.ui.MainActivity
import vikify.vpn.core.VpnConsent
import vikify.vpn.ui.home.ConnectionPhase
import vikify.vpn.ui.home.VpnStatus
import vikify.vpn.vpn.VikifyVpnService
import java.io.File

/** Opt-in only. The URL is streamed into app-private storage; never an APK/CLI argument. */
@RunWith(AndroidJUnit4::class)
class LiveSubscriptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun note(message: String) { instrumentation.sendStatus(0, android.os.Bundle().apply {
        putString("stream", "LIVE: $message\n")
    }) }

    @Test fun allLiveProfilesAndLifecycle() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val credentialsFile = File(context.filesDir, "qa-subscription.json")
        val credentials = JSONObject(credentialsFile.readText())
        credentialsFile.delete()
        val repository = context.subscriptions
        val failures = mutableListOf<String>()
        val priorConsent = VpnConsent.accepted(context)
        try {
            // This transport-only fixture starts the private service directly; UI consent is tested separately.
            assertTrue(VpnConsent.set(context, true))
            // Public root only: diagnostics cannot contain the private subscription URL.
            val preflight = java.net.URL("https://fall.94043.net/").openConnection() as javax.net.ssl.HttpsURLConnection
            // Report the actual peer even when validation fails, while retaining
            // the platform's certificate validation and HTTPS hostname verifier.
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            val platformTrust = factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
            val reportingTrust = object : javax.net.ssl.X509TrustManager {
                override fun getAcceptedIssuers() = platformTrust.acceptedIssuers
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
                    platformTrust.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    note("subscription host TLS issuer: ${chain.firstOrNull()?.issuerX500Principal?.name}")
                    platformTrust.checkServerTrusted(chain, authType)
                }
            }
            preflight.sslSocketFactory = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(reportingTrust), null)
            }.socketFactory
            try {
                preflight.connectTimeout = 10_000
                preflight.readTimeout = 10_000
                note("subscription host TLS: HTTP ${preflight.responseCode}")
            } catch (e: Exception) {
                note("subscription host TLS: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally { preflight.disconnect() }
            repository.importSubscription(credentials.getString("url"))
            val expectedProfiles = InstrumentationRegistry.getArguments().getString("expectedProfiles")?.toInt() ?: 7
            assertEquals(expectedProfiles, repository.state.value.profiles.size)
            assertTrue("Subscription belongs to requested account", repository.state.value.profiles
                .all { it.uuid == credentials.getString("uuid") })
            note("import user 3: $expectedProfiles profiles, UUID matches")
            val profiles = repository.state.value.profiles
            val selected = profiles.first { it.transport == "xhttp" }
            repository.select(selected.id)
            repository.refresh()
            assertEquals(selected.id, repository.state.value.selectedId)
            note("refresh preserves selection")
            shell("appops set vikify.vpn ACTIVATE_VPN allow")
            assertNull("VPN permission", VpnService.prepare(context))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            val requested = InstrumentationRegistry.getArguments().getString("transports")?.split(',')
            val cycles = InstrumentationRegistry.getArguments().getString("cycles")?.toInt() ?: 1
            val selectedProfiles = profiles.withIndex().filter { requested == null || it.value.transport in requested }
            assertTrue("At least one live connection must be tested", cycles > 0 && selectedProfiles.isNotEmpty())
            if (requested != null) assertEquals("Every requested transport must be present",
                requested.toSet(), selectedProfiles.map { it.value.transport }.toSet())
            repeat(cycles) { cycle ->
            for ((index, profile) in selectedProfiles) {
                repository.select(profile.id)
                VpnStatus.publish(vikify.vpn.ui.home.ConnectionState(ConnectionPhase.CONNECTING))
                instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, Intent(context, VikifyVpnService::class.java)
                    .setAction(VikifyVpnService.ACTION_CONNECT)
                    .putExtra(VikifyVpnService.EXTRA_PROFILE_ID, profile.id)
                    .putExtra(VikifyVpnService.EXTRA_REVISION, profile.revision)) }
                val result = withTimeout(60_000) { VpnStatus.state.first {
                    it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.ERROR
                } }
                val label = "cycle ${cycle + 1}, profile ${index + 1}: ${profile.transport}/${profile.port}"
                if (result.phase == ConnectionPhase.CONNECTED) {
                    val probe = shell("am instrument -w -r -e class vikify.vpn.probe.TunnelTrafficTest " +
                        "vikify.vpn.probe.test/androidx.test.runner.AndroidJUnitRunner")
                    if (probe.contains("OK (3 tests)") && !probe.contains("FAILURES!!!")) note("$label PASS: DNS + 3 HTTPS scenarios via TUN")
                    else { failures.add("$label traffic probe failed"); note("$label FAIL: $probe") }
                    // Reopen UI without creating another connection or losing the actual state.
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    assertEquals(profile.id, VpnStatus.state.value.profile?.id)
                } else {
                    val error = result.error?.let(context::getString).orEmpty()
                    failures.add("$label $error")
                    note("$label FAIL: $error")
                    if (InstrumentationRegistry.getArguments().getString("diagnostics") == "true") diagnose(profile)
                }
            }
            }
            if (InstrumentationRegistry.getArguments().getString("lifecycle") == "true") uiLifecycle(repository)
            context.startService(Intent(context, VikifyVpnService::class.java).setAction(VikifyVpnService.ACTION_DISCONNECT))
            withTimeout(30_000) { VpnStatus.state.first { it.phase == ConnectionPhase.DISCONNECTED } }
            assertFalse("Temporary config removed", File(context.filesDir, "xray_config.json").exists())
            note("disconnect: state reset and temporary config removed")
            assertTrue(failures.joinToString("; "), failures.isEmpty())
        } finally {
            VpnConsent.set(context, priorConsent)
            context.startService(Intent(context, VikifyVpnService::class.java).setAction(VikifyVpnService.ACTION_DISCONNECT))
            withTimeout(30_000) { VpnStatus.state.first { !it.hasSession && !it.busy } }
            repository.remove()
            credentialsFile.delete()
        }
    }

    private suspend fun uiLifecycle(repository: SubscriptionRepository) {
        lateinit var home: vikify.vpn.ui.home.HomeViewModel
        var resumed: MainActivity? = null
        withTimeout(30_000) {
            while (resumed == null) {
                instrumentation.runOnMainSync {
                    resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                delay(100)
            }
        }
        instrumentation.runOnMainSync {
            val activity = checkNotNull(resumed)
            home = androidx.lifecycle.ViewModelProvider(activity)[vikify.vpn.ui.home.HomeViewModel::class.java]
            home.disconnect()
        }
        withTimeout(30_000) { VpnStatus.state.first { it.phase == ConnectionPhase.DISCONNECTED } }
        repository.select(repository.state.value.profiles.first { it.transport == "xhttp" }.id)
        shell("appops set vikify.vpn ACTIVATE_VPN deny")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant vikify.vpn android.permission.POST_NOTIFICATIONS")
        delay(500)
        instrumentation.runOnMainSync { home.connect() }
        note("waiting for VPN permission dialog: ${VpnStatus.state.value.phase}")
        systemButton("android:id/button2")
        withTimeout(15_000) { VpnStatus.state.first { it.phase == ConnectionPhase.DISCONNECTED } }
        note("VPN permission rejected: returned to disconnected")
        delay(500)
        instrumentation.runOnMainSync { home.connect() }
        systemButton("android:id/button1")
        waitConnected()
        note("VPN permission accepted: UI connected")
        val notifications = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
        assertTrue("Foreground notification available", notifications.isNotEmpty())
        notifications.first().notification.actions.first().actionIntent.send()
        withTimeout(30_000) { VpnStatus.state.first { it.phase == ConnectionPhase.DISCONNECTED } }
        note("notification disconnect works")
        delay(500)
        instrumentation.runOnMainSync { home.connect() }
        waitConnected()
        instrumentation.runOnMainSync { home.remove() }
        withTimeout(30_000) { repository.state.first { it.profiles.isEmpty() } }
        assertEquals(ConnectionPhase.DISCONNECTED, VpnStatus.state.value.phase)
        assertFalse(File(context.filesDir, "xray_config.json").exists())
        note("remove while connected: VPN stopped, saved access and config removed")
    }

    private suspend fun waitConnected() {
        val state = withTimeout(60_000) { VpnStatus.state.first {
            it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.ERROR
        } }
        assertEquals(state.error?.let(context::getString), ConnectionPhase.CONNECTED, state.phase)
    }

    private suspend fun systemButton(id: String) {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun nodes(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
            if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        withTimeout(15_000) {
            while (true) {
                val button = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.viewIdResourceName == id }
                if (button != null && button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) break
                delay(100)
            }
        }
    }

    private suspend fun diagnose(profile: ProxyProfile) {
        // An ordinary, verified TLS handshake identifies interception on this
        // exact endpoint. No VLESS identity or subscription token is sent.
        try {
            val serverName = profile.parameters["sni"] ?: profile.host
            java.net.Socket().use { tcp ->
                tcp.connect(java.net.InetSocketAddress(profile.host, profile.port), 5000)
                (javax.net.ssl.SSLSocketFactory.getDefault().let { it as javax.net.ssl.SSLSocketFactory }
                    .createSocket(tcp, serverName, profile.port, true) as javax.net.ssl.SSLSocket).use { tls ->
                    tls.soTimeout = 5000
                    tls.startHandshake()
                    val peer = tls.session.peerCertificates.first() as java.security.cert.X509Certificate
                    note("endpoint ${profile.port} TLS issuer: ${peer.issuerX500Principal.name}")
                    check(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(serverName, tls.session))
                }
            }
        } catch (failure: Exception) {
            note("endpoint ${profile.port} TLS diagnostic: ${failure.javaClass.simpleName}")
        }
        val config = File(context.filesDir, "qa-diagnostic.json")
        val log = File(context.filesDir, "qa-diagnostic.log")
        val json = JSONObject(XrayConfigBuilder.build(profile))
        json.getJSONObject("log").put("loglevel", "debug")
        config.writeText(json.toString())
        val process = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libxray.so").path,
            "-config", config.path).apply {
            val root = File(context.filesDir, "emulator-root.pem")
            if (root.isFile) environment()["SSL_CERT_FILE"] = root.path
        }.redirectErrorStream(true).redirectOutput(log).start()
        try {
            delay(500)
            try { vikify.vpn.core.ProxyHealthCheck.check() }
            catch (_: Exception) { note("diagnostic check failed for ${profile.transport}/${profile.port}") }
        } finally {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            var output = log.readText().takeLast(12000)
                .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<uuid>")
            for (key in listOf("pbk", "sid")) profile.parameters[key]?.takeIf { it.isNotEmpty() }
                ?.let { output = output.replace(it, "<redacted>") }
            output.lineSequence().filter { it.contains("failed", true) || it.contains("error", true) }
                .take(8).forEach { note("diagnostic ${profile.transport}/${profile.port}: $it") }
            config.delete(); log.delete()
        }
    }
}
