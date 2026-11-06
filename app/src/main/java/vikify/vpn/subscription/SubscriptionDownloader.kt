package vikify.vpn.subscription

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import vikify.vpn.BuildConfig

internal suspend fun downloadSubscription(url: String): String = fetchSubscription(url) {
    it.toURL().openConnection() as HttpURLConnection
}

internal suspend fun fetchSubscription(url: String, openConnection: (URI) -> HttpURLConnection): String = withContext(Dispatchers.IO) {
    try { withTimeout(45_000) {
        var target = SubscriptionUrl.parse(url)
        val visited = mutableSetOf<String>()
        repeat(4) { hop ->
            if (!visited.add(target.toASCIIString())) throw SubscriptionException(SubscriptionError.SERVER)
            currentCoroutineContext().ensureActive()
            val connection = openConnection(target)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "text/plain")
                connection.setRequestProperty("User-Agent", "Vikify/${BuildConfig.VERSION_NAME}")
                when (connection.responseCode) {
                    200 -> {
                        val output = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 256 * 1024) throw SubscriptionException(SubscriptionError.FORMAT)
                                output.write(buffer, 0, count)
                            }
                        }
                        return@withTimeout output.toString("UTF-8").removePrefix("\uFEFF")
                    }
                    301, 302, 303, 307, 308 -> {
                        if (hop == 3) throw SubscriptionException(SubscriptionError.SERVER)
                        target = SubscriptionRedirect.next(target, connection.getHeaderField("Location"))
                    }
                    401, 403, 404, 410 -> throw SubscriptionException(SubscriptionError.UNAVAILABLE)
                    else -> throw SubscriptionException(SubscriptionError.SERVER)
                }
            } catch (e: SubscriptionException) { throw e
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: javax.net.ssl.SSLException) { throw SubscriptionException(SubscriptionError.TLS)
            } catch (_: Exception) { throw SubscriptionException(SubscriptionError.NETWORK)
            } finally { connection.disconnect() }
        }
        throw SubscriptionException(SubscriptionError.SERVER)
    } } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw SubscriptionException(SubscriptionError.NETWORK)
    }
}
