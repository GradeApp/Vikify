package vikify.vpn.subscription

import java.net.URI

internal object SubscriptionRedirect {
    fun next(current: URI, location: String?): URI = try {
        require(!location.isNullOrBlank())
        // No copied authorization, referer, or original query: only the server's explicit Location.
        SubscriptionUrl.parse(current.resolve(location).toASCIIString())
    } catch (_: Exception) { throw SubscriptionException(SubscriptionError.URL) }
}
