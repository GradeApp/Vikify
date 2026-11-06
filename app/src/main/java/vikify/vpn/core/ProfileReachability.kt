package vikify.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class ProbeRoute(val network: Network, val properties: LinkProperties)

/** Observe the best physical network separately from the app's default VPN network. */
internal class ProfileReachability(context: Context, private val onRoute: (ProbeRoute?) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var ownVpn = false
    private val routes = linkedMapOf<Network, ProbeRoute>()
    private val capabilities = mutableMapOf<Network, NetworkCapabilities>()
    private val blocked = mutableSetOf<Network>()
    private var selected: ProbeRoute? = null

    fun setOwnVpn(active: Boolean) {
        ownVpn = active
        selectRoute()
    }

    private fun selectRoute() {
        if (callback == null) { selected = null; onRoute(null); return }
        val active = connectivity.activeNetwork
        val activeCaps = connectivity.getNetworkCapabilities(active)
        val candidates = routes.values.filter { usable(capabilities[it.network]) && it.network !in blocked }
        // Never bypass another app's VPN. Our own tunnel uses Android's default physical network.
        selected = if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            if (owns(activeCaps)) candidates.singleOrNull() else null
        } else candidates.firstOrNull { it.network == active }
        onRoute(selected)
    }

    fun start() {
        if (callback != null) return
        val handler = Handler(Looper.getMainLooper())
        fun schedule() { handler.post { selectRoute() } }
        val observer = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                if (Build.VERSION.SDK_INT >= 31) {
                    routes.clear();capabilities.clear();blocked.clear()
                }
                schedule()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (callback !== this) return
                capabilities[network] = caps; schedule()
            }
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                if (callback !== this) return
                routes[network] = ProbeRoute(network, link); schedule()
            }
            override fun onBlockedStatusChanged(network: Network, value: Boolean) {
                if (callback !== this) return
                if (value) blocked.add(network) else blocked.remove(network)
                schedule()
            }
            override fun onLost(network: Network) {
                if (callback !== this) return
                routes.remove(network);capabilities.remove(network);blocked.remove(network);schedule()
            }
        }
        val defaults = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (defaultCallback === this) schedule() }
            override fun onLost(network: Network) { if (defaultCallback === this) schedule() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (defaultCallback === this) schedule()
            }
        }
        callback = observer
        defaultCallback = defaults
        try {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            if (Build.VERSION.SDK_INT >= 31) connectivity.registerBestMatchingNetworkCallback(request, observer, handler)
            else connectivity.registerNetworkCallback(request, observer, handler)
            connectivity.registerDefaultNetworkCallback(defaults, handler)
        } catch (_: RuntimeException) { stop() }
    }

    fun stop() {
        val old = callback;val defaults = defaultCallback
        callback = null;defaultCallback = null
        if (old != null) runCatching { connectivity.unregisterNetworkCallback(old) }
        if (defaults != null) runCatching { connectivity.unregisterNetworkCallback(defaults) }
        routes.clear();capabilities.clear();blocked.clear();selected = null
        onRoute(null)
    }

    suspend fun attempt(route: ProbeRoute, endpoint: ProbeEndpoint): Boolean? {
        if (!current(route)) return null
        val answer = try {
            withTimeoutOrNull(3_000) {
                val deadline = SystemClock.elapsedRealtime() + 3_000
                val addresses = if (Build.VERSION.SDK_INT >= 29) resolve(route.network, endpoint.host)
                    else blocking { route.network.getAllByName(endpoint.host).toList() }
                var reachable = false
                for ((index, address) in addresses.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    val slice = (remaining / (addresses.size - index)).coerceAtLeast(1).toInt()
                    val socket = Socket()
                    try {
                        blocking(onCancel = { socket.close() }) {
                            socket.use {
                                route.network.bindSocket(it)
                                it.connect(InetSocketAddress(address, endpoint.port), slice)
                            }
                        }
                        reachable = true
                        break
                    } catch (_: IOException) {
                        // No payload, TLS handshake or profile credentials are sent.
                    } finally { runCatching { socket.close() } }
                }
                reachable
            } ?: false
        } catch (_: IOException) { false }
          catch (_: RejectedExecutionException) { null }
          catch (_: SecurityException) { null }
        return answer.takeIf { current(route) }
    }

    private fun current(route: ProbeRoute): Boolean {
        val active = connectivity.activeNetwork
        val activeCaps = connectivity.getNetworkCapabilities(active)
        val permitted = if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) owns(activeCaps) else active == route.network
        return permitted && route == selected && route.network !in blocked &&
            usable(connectivity.getNetworkCapabilities(route.network)) &&
            connectivity.getLinkProperties(route.network) == route.properties
    }

    private fun owns(caps: NetworkCapabilities) = ownVpn && Build.VERSION.SDK_INT >= 30 &&
        caps.ownerUid == android.os.Process.myUid()

    private fun usable(caps: NetworkCapabilities?): Boolean = caps != null &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    @RequiresApi(29)
    private suspend fun resolve(network: Network, host: String): List<InetAddress> {
        if (InetAddresses.isNumericAddress(host)) return listOf(InetAddresses.parseNumericAddress(host))
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, Executor { it.run() }, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        continuation.resume(if (rcode == 0) answer else emptyList())
                    }
                    override fun onError(error: DnsResolver.DnsException) { continuation.resume(emptyList()) }
                })
        }
    }

    private suspend fun <T> blocking(onCancel: () -> Unit = {}, action: () -> T): T = suspendCancellableCoroutine { c ->
        val task = FutureTask {
            try { c.resume(action()) }
            catch (e: Exception) { if (c.isActive) c.resumeWithException(e) }
        }
        c.invokeOnCancellation {
            runCatching(onCancel)
            task.cancel(true)
            workers.remove(task)
        }
        try { if (c.isActive) workers.execute(task) }
        catch (e: RejectedExecutionException) { if (c.isActive) c.resumeWithException(e) }
    }

    companion object {
        // Bounded fallback for Android 8/9 DNS, which has no cancellable DnsResolver API.
        // A slow platform resolver cannot create unbounded threads or pending tasks.
        private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(2),
            { task -> Thread(task, "profile-reachability").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
