package vikify.vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import vikify.vpn.BuildConfig

object ProxyHealthCheck {
    /** Explicit SOCKS routing is required because Vikify itself bypasses the Android VPN. */
    suspend fun check() = withContext(Dispatchers.IO) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
        val endpoints = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        for (endpoint in endpoints) {
            currentCoroutineContext().ensureActive()
            val connection = URL(endpoint).openConnection(proxy) as HttpsURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                val status = connection.responseCode
                if (status == 204) return@withContext
                if (BuildConfig.DEBUG) android.util.Log.i("VikifyHealth", "Probe HTTP $status")
            } catch (failure: java.io.IOException) {
                if (BuildConfig.DEBUG) android.util.Log.i("VikifyHealth", "Probe ${failure.javaClass.simpleName}")
                // Try the independent endpoint without logging transport credentials.
            } finally {
                connection.disconnect()
            }
        }
        currentCoroutineContext().ensureActive()
        error("Proxy connectivity check failed")
    }
}
