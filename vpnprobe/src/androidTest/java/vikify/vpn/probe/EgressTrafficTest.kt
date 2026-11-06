package vikify.vpn.probe

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Independent UID: checks the actual Android VPN path and its public exit. */
@RunWith(AndroidJUnit4::class)
class EgressTrafficTest {
    @Test fun dnsHttpsAndExpectedExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedIp"))
        val manager = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(manager.activeNetwork)
        assertTrue("Probe must use Android VPN", manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        assertTrue("DNS through VPN", network.getAllByName("www.gstatic.com").isNotEmpty())
        fun request(url: String): Pair<Int, String> {
            val connection = network.openConnection(URL(url)) as HttpsURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                val body = if (code == 200) connection.inputStream.bufferedReader().use { it.readText() } else ""
                return code to body.trim()
            } finally { connection.disconnect() }
        }
        assertEquals(204, request("https://www.gstatic.com/generate_204").first)
        val (status, address) = request("https://api.ipify.org/?qa=" + System.nanoTime())
        assertEquals(200, status)
        assertEquals("VPN public exit", expected, address)
        assertEquals(network, manager.activeNetwork)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("verified_exit_ip", address)
            putString("vpn_network_id", network.toString())
        })
    }
}
