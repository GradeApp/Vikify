package vikify.vpn.ui.home

import androidx.annotation.StringRes
import java.net.URI
import vikify.vpn.R
import vikify.vpn.subscription.ProxyProfile
import vikify.vpn.subscription.SubscriptionState

private val providerLabels = mapOf(
    "Авто" to R.string.profile_automatic,
    "VLESS-REALITY-443-MAIN" to R.string.profile_main,
    "WEB-RESERVE" to R.string.profile_backup_server,
    "EDGE-2" to R.string.profile_secondary_server,
    "VLESS-REALITY-8443-RESERVE" to R.string.profile_backup_1,
    "VLESS-REALITY-8445-RESERVE" to R.string.profile_backup_2,
    "VLESS-XHTTP-TLS" to R.string.profile_alternative_xhttp,
    "VLESS-WS-TLS-FALLBACK" to R.string.profile_last_resort_ws
)

@StringRes
internal fun SubscriptionState.profileLabel(profile: ProxyProfile): Int? {
    val label = providerLabels[profile.name] ?: return null
    val source = snapshot ?: return null
    return label.takeIf { URI(source.url).host.equals("fall.94043.net", ignoreCase = true) }
}

@StringRes
internal fun SubscriptionState.profileDescription(profile: ProxyProfile): Int = when (profileLabel(profile)) {
    R.string.profile_automatic -> R.string.profile_description_automatic
    R.string.profile_main -> R.string.profile_description_main
    R.string.profile_backup_server, R.string.profile_secondary_server -> R.string.profile_description_server
    R.string.profile_backup_1, R.string.profile_backup_2 -> R.string.profile_description_reserve
    else -> when (profile.transport) {
        "xhttp" -> R.string.profile_description_xhttp
        "ws" -> R.string.profile_description_ws
        else -> R.string.profile_description_reality
    }
}
