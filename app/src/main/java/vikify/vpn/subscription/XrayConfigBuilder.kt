package vikify.vpn.subscription

import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {
    fun build(profile: ProxyProfile): String {
        val params = profile.parameters
        val user = JSONObject().put("id", profile.uuid).put("encryption", "none")
        params["flow"]?.takeIf(String::isNotEmpty)?.let { user.put("flow", it) }
        val stream = JSONObject().put("network", profile.transport).put("security", profile.security)
        if (profile.security == "reality") {
            stream.put("realitySettings", JSONObject().apply {
                put("serverName", params.getValue("sni"))
                put("fingerprint", params["fp"] ?: "chrome")
                put("publicKey", params.getValue("pbk"))
                put("shortId", params["sid"].orEmpty())
                put("spiderX", params["spx"] ?: "/")
            })
        } else {
            stream.put("tlsSettings", JSONObject().apply {
                put("serverName", params.getValue("sni"))
                put("allowInsecure", false)
                put("fingerprint", params["fp"] ?: "chrome")
                params["alpn"]?.takeIf(String::isNotBlank)?.let {
                    put("alpn", JSONArray(it.split(',')))
                }
            })
            val transportSettings = JSONObject().put("path", params.getValue("path"))
            if (profile.transport == "ws") {
                params["host"]?.let { transportSettings.put("headers", JSONObject().put("Host", it)) }
                stream.put("wsSettings", transportSettings)
            } else {
                transportSettings.put("host", params["host"] ?: profile.host)
                transportSettings.put("mode", params["mode"] ?: "auto")
                stream.put("xhttpSettings", transportSettings)
            }
        }
        val server = JSONObject().put("address", profile.host).put("port", profile.port)
            .put("users", JSONArray().put(user))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(server)))
            .put("streamSettings", stream)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "socks").put("listen", "127.0.0.1").put("port", 10808)
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true))))
            .put("outbounds", JSONArray().put(outbound))
            .toString()
    }
}
