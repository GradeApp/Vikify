package vikify.vpn.profile

import android.content.Context
import org.json.JSONObject

private const val PROFILES_ASSET_PATH = "profiles"
private const val UUID_PLACEHOLDER = "\${UUID}"

class ProfileManager(private val context: Context) {

    /**
     * Loads the profile template, replaces ${UUID} with the given UUID,
     * and returns the final JSON string. Does not write to disk.
     */
    fun generateConfigJson(profileType: ProfileType, uuid: String): String {
        val template = context.assets.open("$PROFILES_ASSET_PATH/${profileType.assetName}")
            .bufferedReader().use { it.readText() }
        return template.replace(UUID_PLACEHOLDER, uuid)
    }

    /**
     * Generates config JSON for Android VPN: same as template but inbounds replaced
     * with a single SOCKS inbound (127.0.0.1:10808) so xray runs without creating TUN.
     * Routing rule is updated to use the socks inbound tag.
     */
    fun generateConfigJsonForVpn(profileType: ProfileType, uuid: String): String {
        val json = generateConfigJson(profileType, uuid)
        val root = JSONObject(json)
        val socksInbound = JSONObject().apply {
            put("tag", "socks")
            put("listen", "127.0.0.1")
            put("port", 10808)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", org.json.JSONArray(listOf("http", "tls")))
            })
        }
        root.put("inbounds", org.json.JSONArray(listOf(socksInbound)))
        val routing = root.getJSONObject("routing")
        val rules = routing.getJSONArray("rules")
        if (rules.length() > 0) {
            val first = rules.getJSONObject(0)
            first.put("inboundTag", org.json.JSONArray(listOf("socks")))
        }
        return root.toString()
    }
}
