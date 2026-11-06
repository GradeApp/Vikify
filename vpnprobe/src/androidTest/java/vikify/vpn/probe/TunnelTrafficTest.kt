package vikify.vpn.probe

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class TunnelTrafficTest {
    @Test fun defaultNetworkCarriesDnsAndHttpsThroughVpn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // ConnectivityService publishes default-network changes asynchronously.
        // Wait for the probe UID's route, not just the VPN app's ready label.
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        var network = connectivity.activeNetwork
        while (connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true &&
            android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(100)
            network = connectivity.activeNetwork
        }
        network = requireNotNull(network) { "Probe has no default network" }
        assertTrue("Probe UID must use the VPN", connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        assertTrue("DNS over VPN", network.getAllByName("www.gstatic.com").isNotEmpty())
        val connection = network.openConnection(URL("https://www.gstatic.com/generate_204?vikify-qa=${System.nanoTime()}")) as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            assertEquals("HTTPS through Android TUN", 204, connection.responseCode)
            assertEquals("VPN must remain the default network", network, connectivity.activeNetwork)
        } finally { connection.disconnect() }
    }

    @Test fun defaultHttpsStackDownloadsARealPage() {
        val connectivity = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(connectivity.activeNetwork)
        assertTrue(connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        // Exercise ordinary app routing, independently of Network.openConnection above.
        val connection = URL("https://example.com/?vikify-qa=${System.nanoTime()}")
            .openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            // Each profile must open its own connection. A socket pooled before
            // the VPN was replaced belongs to the previous network session.
            connection.setRequestProperty("Connection", "close")
            connection.instanceFollowRedirects = false
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            assertTrue("Expected HTTPS page body", body.contains("Example Domain"))
            assertEquals(network, connectivity.activeNetwork)
        } finally { connection.disconnect() }
    }

    @Test fun separateHttpsHostReturnsAnExitAddress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connectivity = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(connectivity.activeNetwork)
        assertTrue(connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        val connection = network.openConnection(URL(
            "https://www.cloudflare.com/cdn-cgi/trace?vikify-qa=${System.nanoTime()}"
        )) as HttpsURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.instanceFollowRedirects = false
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val ip = body.lineSequence().singleOrNull { it.startsWith("ip=") }?.removePrefix("ip=")
            assertTrue("Public exit address returned over HTTPS", ip?.matches(Regex("[0-9a-fA-F:.]+")) == true)
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("vpnExit", ip) })
            assertEquals(network, connectivity.activeNetwork)
        } finally { connection.disconnect() }
    }
}
