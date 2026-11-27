package vikify.vpn.diagnostics

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiagnosticHttpTest {
    private class Connection(val status: Int, val body: ByteArray = byteArrayOf(), val location: String? = null,
        val readFailure: IOException? = null) :
        HttpURLConnection(URL("https://provider.invalid/sub/private-token")) {
        var closed = false
        var responseRead = false
        var bodyReadAttempted = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int { responseRead = true; return status }
        override fun getInputStream(): InputStream = readFailure?.let { failure ->
            object : InputStream() {
                override fun read(): Int { bodyReadAttempted = true; throw failure }
            }
        } ?: ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }

    @Test fun safeHttpCodesAreRetainedWithoutResponseBodiesOrReasonPhrases() = runBlocking {
        for (status in listOf(401, 403, 404, 410, 429, 500, 503)) {
            val connection = Connection(status, "SECRET-TOKEN".toByteArray())
            val failure = try {
                DiagnosticHttp.fetch("https://provider.invalid/private", { connection }); error("Expected failure")
            } catch (e: DiagnosticFailure) { e }
            assertEquals(DiagnosticCode.SUBSCRIPTION_HTTP_ERROR, failure.code)
            assertEquals(status, failure.status)
            assertTrue(connection.closed)
            assertFalse(failure.toString().contains("SECRET"))
            if (status == 429 || status >= 500) assertEquals(DiagnosticAction.RETRY_LATER, failure.action)
        }
    }

    @Test fun rejectsDowngradeUserinfoAndRedirectLoops() = runBlocking {
        for (location in listOf("http://provider.invalid/private", "https://secret@provider.invalid/sub", "https://provider.invalid/start")) {
            val opened = mutableListOf<URI>()
            val connection = Connection(302, location = location)
            val failure = try {
                DiagnosticHttp.fetch("https://provider.invalid/start", { opened += it; connection }); error("Expected failure")
            } catch (e: DiagnosticFailure) { e }
            assertEquals(DiagnosticCode.SUBSCRIPTION_REDIRECT_REJECTED, failure.code)
            assertEquals(1, opened.size)
            assertTrue(connection.closed)
        }
    }

    @Test fun acceptsRelativeHttpsRedirectButBoundsTheNumberOfHops() = runBlocking {
        val connections = listOf(Connection(302, location = "/two"), Connection(200, "ok".toByteArray()))
        var index = 0
        val response = DiagnosticHttp.fetch("https://provider.invalid/one", { connections[index++] })
        assertEquals("ok", response.body)
        assertTrue(connections.all { it.closed })
        var opens = 0
        val failure = try {
            DiagnosticHttp.fetch("https://provider.invalid/one", { opens++; Connection(302, location = "/step$opens") })
            error("Expected failure")
        } catch (e: DiagnosticFailure) { e }
        assertEquals(4, opens)
        assertEquals(DiagnosticCode.SUBSCRIPTION_REDIRECT_REJECTED, failure.code)
    }

    @Test fun sizeLimitAndCancellationAlwaysCloseTheConnection() = runBlocking {
        val connection = Connection(200, ByteArray(256 * 1024 + 1))
        val failure = try {
            DiagnosticHttp.fetch("https://provider.invalid/sub", { connection }); error("Expected failure")
        } catch (e: DiagnosticFailure) { e }
        assertEquals(DiagnosticCode.SUBSCRIPTION_TOO_LARGE, failure.code)
        assertTrue(connection.closed)
        val cancelled = Connection(200, "private-data".toByteArray())
        var checks = 0
        try {
            DiagnosticHttp.fetch("https://provider.invalid/sub", { cancelled }, check = {
                if (++checks > 1) throw CancellationException("Secret cancellation text")
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(cancelled.closed)
    }

    @Test fun rejectsInvalidInputBeforeOpeningAnyConnection() = runBlocking {
        for (url in listOf("http://provider.invalid/sub", "https://user:secret@provider.invalid/sub", "https://provider.invalid/#secret")) {
            var called = false
            val failure = try {
                DiagnosticHttp.fetch(url, { called = true; Connection(200) }); error("Expected failure")
            } catch (e: DiagnosticFailure) { e }
            assertFalse(called)
            assertEquals(DiagnosticCode.SUBSCRIPTION_FORMAT_ERROR, failure.code)
        }
    }

    @Test fun bodyReadFailureAfterHttp200IsNotMisreportedAsTcpSetupFailure() = runBlocking {
        val secret = "PRIVATE-TOKEN-https://provider.invalid/private-body"
        val connection = Connection(200, readFailure = IOException(secret))
        val failure = try {
            DiagnosticHttp.fetch("https://provider.invalid/sub", { connection })
            error("Expected body read failure")
        } catch (failure: IOException) { diagnosticIoFailure(failure) }
        assertTrue(connection.responseRead)
        assertTrue(connection.bodyReadAttempted)
        assertTrue(connection.closed)
        assertEquals(DiagnosticCode.NETWORK_IO_FAILED, failure.code)
        assertEquals(DiagnosticAction.RETRY, failure.action)
        assertNull(failure.cause)
        assertEquals(DiagnosticCode.NETWORK_IO_FAILED.name, failure.message)
        val report = DiagnosticReportData(appVersionCode = 25, androidApi = 33, completed = true,
            events = listOf(DiagnosticEvent(DiagnosticStage.SUBSCRIPTION, DiagnosticStatus.FAIL,
                failure.code, DiagnosticRoute.DIRECT, failure.action)))
        for (text in listOf(DiagnosticReport.toText(report), DiagnosticReport.toJson(report), failure.toString())) {
            assertFalse("Raw read error must not enter the report", text.contains(secret))
            assertFalse("A body read failure must not invent a TCP setup failure", text.contains("TCP_FAILED"))
        }
    }

    @Test fun specializedIoFailuresKeepTheirMeaningWithoutRetainingTheirException() {
        for ((error, code) in listOf<Pair<IOException, DiagnosticCode>>(
            SSLException("PRIVATE-TLS-DETAIL") to DiagnosticCode.TLS_FAILED,
            SocketTimeoutException("PRIVATE-TIMEOUT-DETAIL") to DiagnosticCode.TIMEOUT,
            UnknownHostException("private-dns.invalid") to DiagnosticCode.DNS_FAILED
        )) {
            val failure = diagnosticIoFailure(error)
            assertEquals(code, failure.code)
            assertEquals(code.name, failure.message)
            assertNull(failure.cause)
        }
    }
}
