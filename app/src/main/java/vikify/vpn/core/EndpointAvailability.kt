package vikify.vpn.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ProbeEndpoint(val host: String, val port: Int)
enum class Availability { UNKNOWN, CHECKING, REACHABLE, UNREACHABLE }
data class AvailabilityState(
    val statuses: Map<ProbeEndpoint, Availability> = emptyMap(),
    val canCheck: Boolean = false,
    val busy: Boolean = false
)

/** All calls and state changes run on the owning (UI) scope. No credentials or disk cache. */
internal class EndpointAvailability(
    private val scope: CoroutineScope,
    private val probe: suspend (Any, ProbeEndpoint) -> Boolean?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 60_000,
    private val retryDelayMs: Long = 250
) {
    private data class Result(val status: Availability, val expires: Long)
    private val mutableState = MutableStateFlow(AvailabilityState())
    val state = mutableState.asStateFlow()
    private val results = mutableMapOf<ProbeEndpoint, Result>()
    private var endpoints = emptySet<ProbeEndpoint>()
    private var route: Any? = null
    private var generation = 0
    private var work: Job? = null
    private var expiry: Job? = null

    fun setContext(network: Any?, targets: Set<ProbeEndpoint>): Boolean {
        if (network == route && targets == endpoints) return false
        generation++
        work?.cancel()
        expiry?.cancel()
        results.clear()
        endpoints = targets
        route = network
        publish()
        return true
    }

    fun check(force: Boolean = false): Job? {
        val network = route ?: return null
        if (work?.isActive == true) return work
        results.entries.removeAll { it.value.expires <= clock() }
        val pending = endpoints.filter { force || it !in results }
        if (pending.isEmpty()) { publish(); return null }
        val token = generation
        pending.forEach { results[it] = Result(Availability.CHECKING, Long.MAX_VALUE) }
        publish()
        work = scope.launch {
            val permits = Semaphore(2)
            coroutineScope {
                pending.forEach { endpoint -> launch {
                    permits.withPermit {
                        val first = attempt(network, endpoint)
                        val answer = if (first == false) {
                            delay(retryDelayMs)
                            attempt(network, endpoint)
                        } else first
                        if (token != generation) return@withPermit
                        val status = when (answer) {
                            true -> Availability.REACHABLE
                            false -> Availability.UNREACHABLE
                            null -> Availability.UNKNOWN
                        }
                        results[endpoint] = Result(status, clock() + ttlMs)
                        publish()
                        scheduleExpiry()
                    }
                } }
            }
        }
        return work
    }

    private suspend fun attempt(network: Any, endpoint: ProbeEndpoint): Boolean? = try {
        probe(network, endpoint)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    private fun publish() {
        val current = results.filterValues { it.expires > clock() }.mapValues { it.value.status }
        mutableState.value = AvailabilityState(current, route != null,
            current.values.any { it == Availability.CHECKING })
    }

    private fun scheduleExpiry() {
        expiry?.cancel()
        val next = results.values.filter { it.status != Availability.CHECKING }.minOfOrNull { it.expires } ?: return
        expiry = scope.launch {
            delay((next - clock()).coerceAtLeast(1))
            results.entries.removeAll { it.value.expires <= clock() }
            publish()
            scheduleExpiry()
        }
    }

    fun stop() { setContext(null, emptySet()) }
}
