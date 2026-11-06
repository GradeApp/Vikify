package vikify.vpn.subscription

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDownloadTest {
    private class Reply(url: URL, val status: Int, val body: ByteArray = byteArrayOf(), val location: String? = null) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream(body)
    }

    @Test fun followsBoundedHttpsRedirectsWithoutForwardingOriginalQuery() = runBlocking {
        val replies = mutableListOf<Reply>()
        val result = fetchSubscription("https://first.example/api?token=private") { uri ->
            Reply(uri.toURL(), if (replies.isEmpty()) 302 else 200, "encoded-payload".toByteArray(),
                "https://second.example/feed?key=new").also { replies += it }
        }
        assertEquals("encoded-payload", result)
        assertEquals("https://second.example/feed?key=new", replies[1].url.toString())
        assertTrue(replies.all { it.closed && !it.instanceFollowRedirects && !it.useCaches })
        assertTrue(replies.all { it.getRequestProperty("Authorization") == null && it.getRequestProperty("Referer") == null })
    }

    @Test fun blocksDowngradeLoopsAndExcessRedirects() = runBlocking {
        for (target in listOf("http://unsafe.example/list", "https://first.example/api", "/next")) {
            var opened = 0
            try {
                fetchSubscription("https://first.example/api") { uri ->
                    opened++
                    Reply(uri.toURL(), 302, location = if (target == "/next") "/next$opened" else target)
                }
                fail("Unexpected redirect accepted")
            } catch (_: SubscriptionException) { }
            assertTrue(opened <= 4)
            if (target.startsWith("http:")) assertEquals(1, opened)
        }
    }

    @Test fun boundsResponsesAndClosesFailedConnections() = runBlocking {
        for ((code, body, expected) in listOf(
            Triple(200, ByteArray(256 * 1024 + 1), SubscriptionError.FORMAT),
            Triple(403, byteArrayOf(), SubscriptionError.UNAVAILABLE),
            Triple(500, byteArrayOf(), SubscriptionError.SERVER))) {
            val reply = Reply(URL("https://source.example/list"), code, body)
            try { fetchSubscription(reply.url.toString()) { reply }; fail() }
            catch (e: SubscriptionException) { assertEquals(expected, e.reason) }
            assertTrue(reply.closed)
        }
    }
}
