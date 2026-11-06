package vikify.vpn.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class EndpointAvailabilityTest {
    private val endpoint = ProbeEndpoint("server.example", 443)

    @Test fun sharedEndpointsUseOneProbeAndCacheExpiresWithoutBackgroundTraffic() = runBlocking {
        var calls = 0
        val checker = EndpointAvailability(this, { _, _ -> calls++; true }, ttlMs = 80)
        try {
            checker.setContext("wifi", setOf(endpoint, endpoint))
            checker.check()!!.join()
            assertEquals(Availability.REACHABLE, checker.state.value.statuses[endpoint])
            assertNull(checker.check())
            assertEquals(1, calls)
            delay(150)
            assertTrue(checker.state.value.statuses.isEmpty())
            assertEquals(1, calls)
            checker.check()!!.join()
            assertEquals(2, calls)
        } finally { checker.stop() }
    }

    @Test fun failureNeedsTwoAttemptsAndTransientFailureCanRecover() = runBlocking {
        for (recovers in listOf(false, true)) {
            var calls = 0
            val checker = EndpointAvailability(this, { _, _ -> ++calls == 2 && recovers }, retryDelayMs = 0)
            try {
                checker.setContext("wifi", setOf(endpoint))
                checker.check()!!.join()
                assertEquals(2, calls)
                assertEquals(if (recovers) Availability.REACHABLE else Availability.UNREACHABLE,
                    checker.state.value.statuses[endpoint])
            } finally { checker.stop() }
        }
    }

    @Test fun localFailureAndLostNetworkRemainUnknown() = runBlocking {
        var calls = 0
        val checker = EndpointAvailability(this, { _, _ -> calls++; null })
        try {
            checker.setContext(null, setOf(endpoint))
            assertNull(checker.check())
            assertEquals(0, calls)
            checker.setContext("wifi", setOf(endpoint))
            checker.check()!!.join()
            assertEquals(Availability.UNKNOWN, checker.state.value.statuses[endpoint])
            assertEquals(1, calls)
            checker.setContext(null, setOf(endpoint))
            assertTrue(checker.state.value.statuses.isEmpty())
            assertFalse(checker.state.value.canCheck)
        } finally { checker.stop() }
    }

    @Test fun lateResultFromPreviousNetworkCannotPaintNewNetwork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val checker = EndpointAvailability(this, { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            false
        }, retryDelayMs = 0)
        try {
            checker.setContext("wifi", setOf(endpoint))
            val old = checker.check()!!
            entered.await()
            checker.setContext("mobile", setOf(endpoint))
            release.complete(Unit);old.join()
            assertTrue(checker.state.value.statuses.isEmpty())
            assertFalse(checker.state.value.busy)
        } finally { checker.stop() }
    }

    @Test fun onlyTwoEndpointsRunAtOnceAndRepeatedClicksDoNotStartAnotherBatch() = runBlocking {
        var active = 0; var peak = 0; var calls = 0
        val checker = EndpointAvailability(this, { _, _ ->
            calls++;active++;peak = maxOf(peak, active)
            delay(15)
            active--;true
        })
        try {
            checker.setContext("wifi", (1..7).map { ProbeEndpoint("server.example", 8000 + it) }.toSet())
            val work = checker.check()!!
            assertSame(work, checker.check(force = true))
            work.join()
            assertEquals(2, peak)
            assertEquals(7, calls)
            assertFalse(checker.state.value.busy)
        } finally { checker.stop() }
    }

    @Test fun changedEndpointsAndNetworkClearCacheButSelectionDoesNot() = runBlocking {
        val checker = EndpointAvailability(this, { _, _ -> true })
        try {
            checker.setContext("wifi", setOf(endpoint))
            checker.check()!!.join()
            assertFalse(checker.setContext("wifi", setOf(endpoint)))
            assertEquals(Availability.REACHABLE, checker.state.value.statuses[endpoint])
            assertTrue(checker.setContext("mobile", setOf(endpoint)))
            assertTrue(checker.state.value.statuses.isEmpty())
            checker.check()!!.join()
            assertTrue(checker.setContext("mobile", setOf(endpoint.copy(port = 8443))))
            assertTrue(checker.state.value.statuses.isEmpty())
        } finally { checker.stop() }
    }
}
