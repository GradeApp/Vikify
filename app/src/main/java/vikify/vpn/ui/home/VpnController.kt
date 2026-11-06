package vikify.vpn.ui.home

import kotlinx.coroutines.flow.StateFlow
import vikify.vpn.subscription.ProxyProfile

interface VpnController {
    val state: StateFlow<ConnectionState>
    fun connect(profile: ProxyProfile)
    fun disconnect()
}
