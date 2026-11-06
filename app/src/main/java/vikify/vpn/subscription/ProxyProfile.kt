package vikify.vpn.subscription

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class SubscriptionError { URL, SPECIALIZED, FORMAT, PARTIAL, UNAVAILABLE, NETWORK, TLS, SERVER, STORAGE }

class SubscriptionException(val reason: SubscriptionError) : Exception(reason.name)

data class ProxyProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val uuid: String,
    val transport: String,
    val security: String,
    val parameters: Map<String, String>,
    val source: String,
    val legacyAsset: String? = null
) {
    val revision: String get() = digest(source)
    val protocolLabel: String get() = when (transport) {
        "xhttp" -> "VLESS · XHTTP / TLS"
        "ws" -> "VLESS · WebSocket / TLS"
        else -> "VLESS · REALITY"
    }

    // Profile credentials must never appear in logs or exception messages.
    override fun toString(): String = "ProxyProfile(id=$id)"
}

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

enum class ImportIssueReason { UNSUPPORTED, INVALID, DUPLICATE }
data class ImportIssue(val line: Int, val reason: ImportIssueReason)
data class ParsedSubscription(val profiles: List<ProxyProfile>, val issues: List<ImportIssue>)

sealed class SubscriptionInput {
    class Remote(val uri: URI) : SubscriptionInput()
    class Local(val parsed: ParsedSubscription) : SubscriptionInput()
    final override fun toString(): String = "SubscriptionInput"

    companion object {
        fun parse(value: String): SubscriptionInput = if (value.trim().startsWith("https:", true)) {
            Remote(SubscriptionUrl.parse(value))
        } else if (value.trim().startsWith("http:", true)) {
            throw SubscriptionException(SubscriptionError.URL)
        } else Local(VlessParser.inspectSubscription(value))
    }
}

object VlessParser {
    private val allowedParameters = setOf(
        "type", "security", "encryption", "sni", "fp", "pbk", "sid", "spx",
        "flow", "host", "path", "alpn", "mode", "allowInsecure", "insecure"
    )

    fun parseSubscription(body: String): List<ProxyProfile> {
        val result = inspectSubscription(body)
        if (result.issues.isNotEmpty()) throw SubscriptionException(SubscriptionError.FORMAT)
        return result.profiles
    }

    fun inspectSubscription(body: String): ParsedSubscription {
        if (body.toByteArray(Charsets.UTF_8).size > 256 * 1024) throw SubscriptionException(SubscriptionError.FORMAT)
        var text = body.trim().removePrefix("\uFEFF").trim()
        if (text.startsWith("{") || text.startsWith("[") || text.startsWith("proxies:"))
            throw SubscriptionException(SubscriptionError.SPECIALIZED)
        if (!text.contains("://")) {
            text = try {
                val compact = text.filterNot(Char::isWhitespace)
                require(compact.isNotEmpty() && compact.matches(Regex("[A-Za-z0-9+/_=-]+")))
                val decoded = Base64.getDecoder().decode(compact.replace('-', '+').replace('_', '/'))
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString().trim()
            } catch (_: Exception) { throw SubscriptionException(SubscriptionError.FORMAT) }
        }
        if (text.startsWith("{") || text.startsWith("[") || text.startsWith("proxies:"))
            throw SubscriptionException(SubscriptionError.SPECIALIZED)
        val lines = text.lineSequence().toList()
        if (lines.size > 500) throw SubscriptionException(SubscriptionError.FORMAT)
        val profiles = linkedMapOf<String, ProxyProfile>()
        val issues = mutableListOf<ImportIssue>()
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (!line.startsWith("vless://", true)) {
                issues += ImportIssue(index + 1, if (Regex("[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(line))
                    ImportIssueReason.UNSUPPORTED else ImportIssueReason.INVALID)
                continue
            }
            val profile = try { parse(line) } catch (_: SubscriptionException) {
                issues += ImportIssue(index + 1, ImportIssueReason.INVALID); continue
            }
            if (profiles.containsKey(profile.id)) issues += ImportIssue(index + 1, ImportIssueReason.DUPLICATE)
            else profiles[profile.id] = profile
        }
        if (profiles.isEmpty() || profiles.size > 100) throw SubscriptionException(SubscriptionError.FORMAT)
        return ParsedSubscription(profiles.values.toList(), issues)
    }

    fun parse(text: String): ProxyProfile = try {
        require(text.length <= 8192 && text.none { it.isISOControl() })
        val uri = URI(text)
        require(uri.scheme.equals("vless", true) && uri.path.isNullOrEmpty())
        val host = uri.host?.removeSurrounding("[", "]") ?: error("host")
        require(host.isNotBlank() && uri.port in 1..65535)
        val uuid = uri.userInfo ?: error("uuid")
        require(UUID.fromString(uuid).toString().equals(uuid, ignoreCase = true))
        val pairs = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            decode(parts[0]) to decode(parts[1])
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        val parameters = pairs.toMap().toMutableMap()
        require(parameters.keys.all { it in allowedParameters })
        require(parameters.values.all { value -> value.none { it.isISOControl() } })
        require(parameters["encryption"].orEmpty() in setOf("", "none"))
        for (flag in listOf("allowInsecure", "insecure")) {
            require(parameters[flag].orEmpty().lowercase() in setOf("", "0", "false"))
        }
        if (parameters["type"] == "raw") parameters["type"] = "tcp"
        val transport = parameters["type"] ?: "tcp"
        val security = parameters["security"] ?: error("security")
        require((transport == "tcp" && security == "reality") ||
            (transport in setOf("ws", "xhttp") && security == "tls"))
        if (security == "tls") {
            if (parameters["sni"].isNullOrBlank()) parameters["sni"] = host
            if (parameters["path"].isNullOrEmpty()) parameters["path"] = "/"
        }
        require(!parameters["sni"].isNullOrBlank())
        if (security == "reality") {
            require(parameters["pbk"]?.matches(Regex("[A-Za-z0-9_-]{43}")) == true)
            val shortId = parameters["sid"].orEmpty()
            require(shortId.length % 2 == 0 && shortId.matches(Regex("[0-9a-fA-F]{0,16}")))
            require(parameters["flow"].orEmpty() in setOf("", "xtls-rprx-vision"))
        } else {
            require(parameters["flow"].isNullOrEmpty())
            require(parameters["path"].orEmpty().startsWith('/'))
        }
        if (transport == "xhttp") require(parameters["mode"].orEmpty() in
            setOf("", "auto", "packet-up", "stream-up", "stream-one"))
        val name = uri.rawFragment?.let { decode(it, preservePlus = true) }
            ?.takeIf(String::isNotBlank) ?: "$host · ${uri.port}"
        require(name.length <= 120 && name.none { it.isISOControl() })
        // Authentication and transport settings can rotate without losing the selected endpoint.
        val id = digest(listOf(host.lowercase(), uri.port, transport, security, name).joinToString("|"))
        ProxyProfile(id, name, host, uri.port, uuid, transport, security, parameters, text)
    } catch (_: Exception) {
        throw SubscriptionException(SubscriptionError.FORMAT)
    }

    private fun decode(value: String, preservePlus: Boolean = false): String =
        URLDecoder.decode(if (preservePlus) value.replace("+", "%2B") else value, "UTF-8")
}

object SubscriptionUrl {
    fun parse(value: String): URI = try {
        val text = value.trim()
        require(text.length <= 8192 && text.none { it.isISOControl() })
        val uri = URI(text)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        URI("https" + text.substring(text.indexOf(':')))
    } catch (e: SubscriptionException) {
        throw e
    } catch (_: Exception) {
        throw SubscriptionException(SubscriptionError.URL)
    }
}
