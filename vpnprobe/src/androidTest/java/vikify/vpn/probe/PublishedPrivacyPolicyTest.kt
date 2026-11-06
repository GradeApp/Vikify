package vikify.vpn.probe

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class PublishedPrivacyPolicyTest {
    @Test fun publicPageMatchesReleaseAndRendersWithoutCookies() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("policyUrl")
        val expectedDigest = args.getString("policySha256")
        assumeNotNull(url, expectedDigest)
        val policy = requireNotNull(url)
        val connection = URL(policy).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            assertEquals(200, connection.responseCode)
            assertTrue(connection.contentType.startsWith("text/html"))
            val bytes = connection.inputStream.use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            assertEquals(expectedDigest, digest.joinToString("") { "%02x".format(it) })
        } finally {
            connection.disconnect()
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val finished = CountDownLatch(1)
        val rendered = AtomicReference<String>()
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext)
            CookieManager.getInstance().setAcceptCookie(false)
            view.settings.javaScriptEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(browser: WebView, address: String) {
                    val check = "location.href === " + JSONObject.quote(policy) +
                        " && document.querySelector('main')?.innerText.includes('VictoriaV')" +
                        " && document.title.includes('Vikify VPN')"
                    browser.evaluateJavascript(check) { result ->
                        rendered.set(result)
                        finished.countDown()
                    }
                }
            }
            view.loadUrl(policy)
        }
        try {
            assertTrue("Policy did not load in WebView", finished.await(30, TimeUnit.SECONDS))
            assertEquals("true", rendered.get())
        } finally {
            instrumentation.runOnMainSync { view.destroy() }
        }
    }
}
