package vikify.vpn.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import vikify.vpn.BuildConfig
import vikify.vpn.subscription.SubscriptionState
import vikify.vpn.subscription.SubscriptionUrl
import vikify.vpn.subscription.VlessParser
import vikify.vpn.ui.home.ConnectionPhase
import vikify.vpn.ui.home.ConnectionState

internal data class DiagnosticNetworkState(val available: Boolean, val vpnActive: Boolean, val identity: Long? = null)
/** Unknown owners are deliberately not assumed to be ours. Never exported. */
internal fun hasUnconfirmedVpn(owners: List<Int?>, ownUid: Int): Boolean = owners.any { it != ownUid }

/** Numeric-only parsing: getByAddress never performs a DNS or reverse-DNS lookup. */
internal fun diagnosticNumericAddress(host: String): InetAddress? {
    fun ipv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            val number = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index] = number.toByte()
        }
        return bytes
    }
    if (':' !in host) return ipv4(host)?.let(InetAddress::getByAddress)
    if (host.length > 45) return null
    var text = host
    if ('.' in text) {
        val separator = text.lastIndexOf(':')
        val bytes = ipv4(text.substring(separator + 1)) ?: return null
        val high = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
        val low = ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
        text = text.substring(0, separator + 1) + high.toString(16) + ":" + low.toString(16)
    }
    if (text.any { it !in "0123456789abcdefABCDEF:" }) return null
    val compression = text.indexOf("::")
    if (compression >= 0 && text.indexOf("::", compression + 2) >= 0) return null
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { token ->
            if (token.isEmpty() || token.length > 4) return null
            token.toIntOrNull(16) ?: return null
        }
    }
    val left = groups(if (compression < 0) text else text.substring(0, compression)) ?: return null
    val right = if (compression < 0) emptyList() else groups(text.substring(compression + 2)) ?: return null
    val used = left.size + right.size
    if ((compression < 0 && used != 8) || (compression >= 0 && used >= 8)) return null
    val values = left + List(8 - used) { 0 } + right
    val bytes = ByteArray(16)
    values.forEachIndexed { index, value ->
        bytes[index * 2] = (value ushr 8).toByte()
        bytes[index * 2 + 1] = value.toByte()
    }
    return InetAddress.getByAddress(bytes)
}
internal class DiagnosticPayload(val body: String, val status: Int) {
    override fun toString() = "DiagnosticPayload(status=$status)"
}
internal class DiagnosticFailure(
    val code: DiagnosticCode,
    val action: DiagnosticAction = DiagnosticAction.RETRY,
    val status: Int? = null
) : Exception(code.name)

/** Do not infer TCP setup failure from an error that can also occur during a body read. */
internal fun diagnosticIoFailure(failure: IOException): DiagnosticFailure = when (failure) {
    is SSLException -> DiagnosticFailure(DiagnosticCode.TLS_FAILED, DiagnosticAction.CHECK_DEVICE_TIME)
    is SocketTimeoutException -> DiagnosticFailure(DiagnosticCode.TIMEOUT)
    is UnknownHostException -> DiagnosticFailure(DiagnosticCode.DNS_FAILED, DiagnosticAction.CHECK_INTERNET)
    else -> DiagnosticFailure(DiagnosticCode.NETWORK_IO_FAILED)
}

/** Private network seam: credentials can enter probes, never events or reports. */
internal interface DiagnosticNetwork {
    fun state(): DiagnosticNetworkState
    suspend fun fetch(url: String, route: DiagnosticRoute): DiagnosticPayload
    suspend fun resolve(host: String): List<InetAddress>
    suspend fun connect(addresses: List<InetAddress>, port: Int)
    suspend fun proxyHttps()
}

class DiagnosticRunner internal constructor(
    private val network: DiagnosticNetwork,
    private val appVersionCode: Int,
    private val androidApi: Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    constructor(context: Context) : this(AndroidDiagnosticNetwork(context.applicationContext),
        BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)

    suspend fun run(
        store: SubscriptionState,
        connection: ConnectionState,
        allowDirect: Boolean = false,
        onEvent: (DiagnosticEvent) -> Unit = {}
    ): DiagnosticReportData {
        val events = mutableListOf<DiagnosticEvent>()
        val initialNetwork = network.state().identity
        var stage = DiagnosticStage.LOCAL_FORMAT
        fun emit(event: DiagnosticEvent) { events += event; onEvent(event) }
        fun skipped(code: DiagnosticCode, action: DiagnosticAction = DiagnosticAction.NONE) =
            emit(DiagnosticEvent(stage, DiagnosticStatus.NOT_TESTED, code, action = action))
        val selected = store.selectedProfile
        val protocol = when {
            selected?.transport == "tcp" && selected.security == "reality" -> DiagnosticProtocol.REALITY
            selected?.transport == "xhttp" && selected.security == "tls" -> DiagnosticProtocol.XHTTP_TLS
            selected?.transport == "ws" && selected.security == "tls" -> DiagnosticProtocol.WEBSOCKET_TLS
            else -> DiagnosticProtocol.UNKNOWN
        }
        fun report() = DiagnosticReportData(appVersionCode = appVersionCode, androidApi = androidApi,
            protocol = protocol, events = events.toList(), completed = true)
        suspend fun check(route: DiagnosticRoute, success: DiagnosticCode, action: suspend () -> DiagnosticEvent?) {
            val start = clock()
            try {
                currentCoroutineContext().ensureActive()
                val state = network.state()
                if (state.vpnActive) throw DiagnosticFailure(DiagnosticCode.OTHER_VPN_ACTIVE, DiagnosticAction.STOP_OTHER_VPN)
                if (!state.available) throw DiagnosticFailure(DiagnosticCode.OFFLINE, DiagnosticAction.CHECK_INTERNET)
                if (state.identity != initialNetwork) throw DiagnosticFailure(DiagnosticCode.NETWORK_CHANGED)
                val detail = withTimeout(35_000) { action() }
                currentCoroutineContext().ensureActive()
                emit((detail ?: DiagnosticEvent(stage, DiagnosticStatus.PASS, success, route)).copy(
                    elapsedMs = ((clock() - start).coerceAtLeast(0) / 100) * 100))
            } catch (failure: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                emit(DiagnosticEvent(stage, DiagnosticStatus.FAIL, DiagnosticCode.TIMEOUT, route,
                    DiagnosticAction.RETRY, ((clock() - start).coerceAtLeast(0) / 100) * 100))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: DiagnosticFailure) {
                currentCoroutineContext().ensureActive()
                emit(DiagnosticEvent(stage, DiagnosticStatus.FAIL, failure.code, route, failure.action,
                    ((clock() - start).coerceAtLeast(0) / 100) * 100, failure.status))
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                emit(DiagnosticEvent(stage, DiagnosticStatus.FAIL, DiagnosticCode.INTERNAL_ERROR, route,
                    DiagnosticAction.RETRY))
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val valid = selected != null && runCatching { VlessParser.parse(selected.source) }.isSuccess
            emit(DiagnosticEvent(stage, if (valid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                if (selected == null) DiagnosticCode.NO_PROFILE else if (valid) DiagnosticCode.LOCAL_VALID else DiagnosticCode.LOCAL_INVALID,
                action = if (selected == null) DiagnosticAction.SELECT_PROFILE else if (valid) DiagnosticAction.NONE else DiagnosticAction.CHECK_SUBSCRIPTION))
            val connected = connection.phase == ConnectionPhase.CONNECTED
            val activeProfile = connection.profile
            val sameProfile = selected != null && activeProfile != null && activeProfile.id == selected.id &&
                activeProfile.revision == selected.revision
            val useProxy = connected && sameProfile
            stage = DiagnosticStage.SUBSCRIPTION
            val snapshot = store.snapshot
            when {
                snapshot == null || snapshot.local -> skipped(DiagnosticCode.LOCAL_ONLY)
                !allowDirect && !useProxy -> skipped(DiagnosticCode.DIRECT_NOT_ALLOWED, DiagnosticAction.ALLOW_DIRECT)
                else -> {
                    val route = if (useProxy) DiagnosticRoute.LOCAL_SOCKS else DiagnosticRoute.DIRECT
                    check(route, DiagnosticCode.SUBSCRIPTION_OK) {
                        val response = network.fetch(snapshot.url, route)
                        val parsed = try { VlessParser.inspectSubscription(response.body) }
                        catch (_: Exception) { throw DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_FORMAT_ERROR, DiagnosticAction.CHECK_SUBSCRIPTION) }
                        val partial = parsed.issues.isNotEmpty()
                        DiagnosticEvent(stage, if (partial) DiagnosticStatus.WARNING else DiagnosticStatus.PASS,
                            if (partial) DiagnosticCode.SUBSCRIPTION_PARTIAL else DiagnosticCode.SUBSCRIPTION_OK,
                            route, if (partial) DiagnosticAction.CHECK_SUBSCRIPTION else DiagnosticAction.NONE,
                            httpStatus = response.status, acceptedCount = parsed.profiles.size, skippedCount = parsed.issues.size)
                    }
                }
            }
            var addresses: List<InetAddress>? = null
            stage = DiagnosticStage.DNS
            when {
                !valid -> skipped(DiagnosticCode.LOCAL_INVALID, DiagnosticAction.CHECK_SUBSCRIPTION)
                !allowDirect -> skipped(DiagnosticCode.DIRECT_NOT_ALLOWED, DiagnosticAction.ALLOW_DIRECT)
                else -> check(DiagnosticRoute.DIRECT, DiagnosticCode.DNS_OK) {
                    val resolved = network.resolve(requireNotNull(selected).host)
                    if (resolved.isEmpty()) throw DiagnosticFailure(DiagnosticCode.DNS_FAILED, DiagnosticAction.CHECK_INTERNET)
                    addresses = resolved
                    null
                }
            }
            val dnsResult = events.last()
            stage = DiagnosticStage.TCP
            when {
                !valid -> skipped(DiagnosticCode.LOCAL_INVALID, DiagnosticAction.CHECK_SUBSCRIPTION)
                !allowDirect -> skipped(DiagnosticCode.DIRECT_NOT_ALLOWED, DiagnosticAction.ALLOW_DIRECT)
                addresses == null || dnsResult.status != DiagnosticStatus.PASS -> skipped(dnsResult.code, dnsResult.action)
                else -> check(DiagnosticRoute.DIRECT, DiagnosticCode.TCP_OK) {
                    network.connect(requireNotNull(addresses), requireNotNull(selected).port); null
                }
            }
            stage = DiagnosticStage.PROXY_HTTPS
            when {
                !valid -> skipped(DiagnosticCode.LOCAL_INVALID, DiagnosticAction.CHECK_SUBSCRIPTION)
                !connected -> skipped(DiagnosticCode.CONNECT_REQUIRED, DiagnosticAction.CONNECT_SELECTED)
                !sameProfile -> skipped(DiagnosticCode.PROFILE_MISMATCH, DiagnosticAction.CONNECT_SELECTED)
                else -> check(DiagnosticRoute.LOCAL_SOCKS, DiagnosticCode.PROXY_HTTPS_OK) { network.proxyHttps(); null }
            }
            stage = DiagnosticStage.ANDROID_VPN
            // The app UID bypasses its own VPN. SOCKS success is not proof that
            // other applications traverse TUN; no structural check can prove that.
            emit(DiagnosticEvent(stage, DiagnosticStatus.NOT_TESTED, DiagnosticCode.TUN_NOT_VERIFIED,
                DiagnosticRoute.ANDROID_SYSTEM))
            return report()
        } catch (cancelled: CancellationException) {
            emit(DiagnosticEvent(stage, DiagnosticStatus.CANCELLED, DiagnosticCode.CANCELLED))
            throw cancelled
        }
    }
}

/** HTTPS logic is JVM-testable with fake connections; errors never retain response text. */
internal object DiagnosticHttp {
    suspend fun fetch(
        url: String,
        open: (URI) -> HttpURLConnection,
        check: () -> Unit = {},
        attach: (HttpURLConnection) -> Unit = {}
    ): DiagnosticPayload = withTimeout(30_000) {
        var target = try { SubscriptionUrl.parse(url) }
        catch (_: Exception) { throw DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_FORMAT_ERROR, DiagnosticAction.CHECK_SUBSCRIPTION) }
        val visited = mutableSetOf<URI>()
        repeat(4) { hop ->
            currentCoroutineContext().ensureActive(); check()
            if (!visited.add(target)) throw DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_REDIRECT_REJECTED, DiagnosticAction.ASK_PROVIDER)
            val connection = open(target)
            attach(connection)
            try {
                connection.connectTimeout = 6_000; connection.readTimeout = 6_000
                connection.instanceFollowRedirects = false; connection.useCaches = false
                connection.setRequestProperty("Accept", "text/plain")
                connection.setRequestProperty("Connection", "close")
                when (val status = connection.responseCode) {
                    200 -> {
                        val bytes = ByteArrayOutputStream()
                        connection.inputStream.use { stream ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                currentCoroutineContext().ensureActive(); check()
                                val size = stream.read(buffer)
                                if (size < 0) break
                                if (bytes.size() + size > 256 * 1024) throw DiagnosticFailure(
                                    DiagnosticCode.SUBSCRIPTION_TOO_LARGE, DiagnosticAction.ASK_PROVIDER)
                                bytes.write(buffer, 0, size)
                            }
                        }
                        check()
                        return@withTimeout DiagnosticPayload(bytes.toString("UTF-8").removePrefix("\uFEFF"), status)
                    }
                    301, 302, 303, 307, 308 -> {
                        if (hop == 3) throw DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_REDIRECT_REJECTED, DiagnosticAction.ASK_PROVIDER)
                        target = try {
                            val location = connection.getHeaderField("Location") ?: error("missing")
                            SubscriptionUrl.parse(target.resolve(location).toASCIIString())
                        } catch (_: Exception) { throw DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_REDIRECT_REJECTED, DiagnosticAction.ASK_PROVIDER) }
                    }
                    else -> throw DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_HTTP_ERROR,
                        when (status) {
                            429 -> DiagnosticAction.RETRY_LATER
                            in 500..599 -> DiagnosticAction.RETRY_LATER
                            401, 403, 410 -> DiagnosticAction.ASK_PROVIDER
                            else -> DiagnosticAction.CHECK_SUBSCRIPTION
                        }, status.takeIf { it in 100..599 })
                }
            } finally { connection.disconnect() }
        }
        throw DiagnosticFailure(DiagnosticCode.SUBSCRIPTION_REDIRECT_REJECTED, DiagnosticAction.ASK_PROVIDER)
    }
}

private class AndroidDiagnosticNetwork(context: Context) : DiagnosticNetwork {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    override fun state(): DiagnosticNetworkState {
        val active = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(active)
        // A foreign VPN may exclude this UID, leaving activeNetwork physical.
        // Check all VPN networks Android exposes, without requesting location or
        // package visibility. Older Android cannot identify the owner: fail closed.
        @Suppress("DEPRECATION")
        val owners = connectivity.allNetworks.mapNotNull { connectivity.getNetworkCapabilities(it) }
            .filter { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .map { if (Build.VERSION.SDK_INT >= 30) it.ownerUid else null }
        return DiagnosticNetworkState(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                hasUnconfirmedVpn(owners, Process.myUid()), active?.networkHandle)
    }

    private inner class Guard(val initial: Network) : AutoCloseable {
        private val reason = AtomicReference<DiagnosticCode?>()
        private val resources = CopyOnWriteArrayList<() -> Unit>()
        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { validate() }
            override fun onLost(network: Network) { if (network == initial) abort(DiagnosticCode.NETWORK_CHANGED) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { validate() }
        }
        private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { validate() }
            override fun onLost(network: Network) { validate() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { validate() }
        }
        init {
            connectivity.registerDefaultNetworkCallback(callback)
            try {
                connectivity.registerNetworkCallback(NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpnCallback)
                validate()
            } catch (failure: RuntimeException) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                throw failure
            }
        }
        private fun abort(code: DiagnosticCode) {
            if (reason.compareAndSet(null, code)) resources.forEach { runCatching(it) }
        }
        private fun validate() {
            val current = connectivity.activeNetwork
            if (state().vpnActive)
                abort(DiagnosticCode.OTHER_VPN_ACTIVE)
            else if (current != initial) abort(DiagnosticCode.NETWORK_CHANGED)
        }
        fun add(close: () -> Unit) { resources += close; if (reason.get() != null) runCatching(close) }
        fun check() {
            validate()
            reason.get()?.let { throw DiagnosticFailure(it, if (it == DiagnosticCode.OTHER_VPN_ACTIVE)
                DiagnosticAction.STOP_OTHER_VPN else DiagnosticAction.RETRY) }
        }
        fun cancel() { resources.forEach { runCatching(it) } }
        override fun close() {
            cancel()
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            runCatching { connectivity.unregisterNetworkCallback(vpnCallback) }
        }
    }

    private suspend fun <T> guarded(block: suspend (Guard) -> T): T = withContext(Dispatchers.IO) {
        val state = state()
        if (state.vpnActive) throw DiagnosticFailure(DiagnosticCode.OTHER_VPN_ACTIVE, DiagnosticAction.STOP_OTHER_VPN)
        val initial = connectivity.activeNetwork ?: throw DiagnosticFailure(DiagnosticCode.OFFLINE, DiagnosticAction.CHECK_INTERNET)
        Guard(initial).use { guard ->
            // Close blocking I/O immediately on cancellation, without waiting for
            // HttpURLConnection's read timeout. The child is only a cancellation hook.
            kotlinx.coroutines.coroutineScope {
                val hook = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    try { kotlinx.coroutines.awaitCancellation() } finally { guard.cancel() }
                }
                try {
                    guard.check()
                    val result = block(guard)
                    currentCoroutineContext().ensureActive(); guard.check(); result
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: DiagnosticFailure) { throw failure
                } catch (failure: IOException) {
                    currentCoroutineContext().ensureActive()
                    guard.check(); throw diagnosticIoFailure(failure)
                } finally { hook.cancel() }
            }
        }
    }

    override suspend fun fetch(url: String, route: DiagnosticRoute): DiagnosticPayload = guarded { guard ->
        require(route == DiagnosticRoute.DIRECT || route == DiagnosticRoute.LOCAL_SOCKS)
        DiagnosticHttp.fetch(url, { uri ->
            guard.check()
            val connection = if (route == DiagnosticRoute.LOCAL_SOCKS)
                uri.toURL().openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)))
            else guard.initial.openConnection(uri.toURL())
            // Never install a custom trust manager or hostname verifier.
            connection as HttpsURLConnection
        }, guard::check, { connection -> guard.add { connection.disconnect() } })
    }

    override suspend fun resolve(host: String): List<InetAddress> = guarded { guard ->
        // DnsResolver.query treats its argument as a DNS question. Literal IP
        // servers need no lookup and must still proceed to the TCP check.
        diagnosticNumericAddress(host)?.let { return@guarded listOf(it) }
        withTimeout(6_000) {
            if (Build.VERSION.SDK_INT >= 29) suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                val delivered = AtomicBoolean(false)
                guard.add {
                    signal.cancel()
                    if (delivered.compareAndSet(false, true)) continuation.resumeWithException(DiagnosticFailure(DiagnosticCode.NETWORK_CHANGED))
                }
                continuation.invokeOnCancellation { delivered.set(true); signal.cancel() }
                DnsResolver.getInstance().query(guard.initial, host, DnsResolver.FLAG_EMPTY,
                    Executor { it.run() }, signal, object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (delivered.compareAndSet(false, true)) continuation.resume(answer)
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            if (delivered.compareAndSet(false, true)) continuation.resumeWithException(DiagnosticFailure(
                                DiagnosticCode.DNS_FAILED, DiagnosticAction.CHECK_INTERNET))
                        }
                    })
            } else suspendCancellableCoroutine { continuation ->
                // Older Android has no cancellable platform resolver. Bound its
                // threads and queue; cancel queued work and discard late answers.
                val delivered = AtomicBoolean(false)
                try {
                    val future = resolver.submit {
                        try {
                            guard.check()
                            val addresses = guard.initial.getAllByName(host).toList()
                            if (delivered.compareAndSet(false, true)) continuation.resume(addresses)
                        } catch (_: Exception) {
                            if (delivered.compareAndSet(false, true)) continuation.resumeWithException(DiagnosticFailure(
                                DiagnosticCode.DNS_FAILED, DiagnosticAction.CHECK_INTERNET))
                        }
                    }
                    guard.add {
                        future.cancel(true)
                        if (delivered.compareAndSet(false, true)) continuation.resumeWithException(DiagnosticFailure(DiagnosticCode.NETWORK_CHANGED))
                    }
                    continuation.invokeOnCancellation { delivered.set(true); future.cancel(true) }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    if (delivered.compareAndSet(false, true)) continuation.resumeWithException(DiagnosticFailure(DiagnosticCode.TIMEOUT, DiagnosticAction.RETRY_LATER))
                }
            }
        }
    }

    override suspend fun connect(addresses: List<InetAddress>, port: Int) = guarded { guard ->
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        var connected = false
        for (address in addresses.take(8)) {
            currentCoroutineContext().ensureActive(); guard.check()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtMost(2_000)
            if (remaining <= 0) break
            Socket().use { socket ->
                guard.add { socket.close() }
                guard.initial.bindSocket(socket)
                try { socket.connect(InetSocketAddress(address, port), remaining.toInt()); connected = true }
                catch (_: IOException) { guard.check() }
            }
            if (connected) break
        }
        if (!connected) throw DiagnosticFailure(DiagnosticCode.TCP_FAILED, DiagnosticAction.ASK_PROVIDER)
    }

    override suspend fun proxyHttps() = guarded { guard ->
        var worked = false
        for (url in listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
            currentCoroutineContext().ensureActive(); guard.check()
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
            val connection = URI(url).toURL().openConnection(proxy) as HttpsURLConnection
            guard.add { connection.disconnect() }
            try {
                connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false; connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                if (connection.responseCode == 204) { worked = true; break }
            } catch (_: IOException) { guard.check()
            } finally { connection.disconnect() }
        }
        if (!worked) throw DiagnosticFailure(DiagnosticCode.PROXY_HTTPS_FAILED, DiagnosticAction.RETRY)
    }

    companion object {
        private val resolver = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(2), { command -> Thread(command, "vikify-diagnostic-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}
