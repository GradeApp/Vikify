package vikify.vpn.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import vikify.vpn.subscription.ProxyProfile

enum class ConnectionPhase { DISCONNECTED, PERMISSION, CONNECTING, CHECKING, CONNECTED, DISCONNECTING, ERROR }

data class ActiveProfile(val id: String, val name: String, val revision: String) {
    constructor(profile: ProxyProfile) : this(profile.id, profile.name, profile.revision)
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val profile: ActiveProfile? = null,
    val error: Int? = null
) {
    val hasSession: Boolean get() = phase in setOf(ConnectionPhase.CONNECTING,
        ConnectionPhase.CHECKING, ConnectionPhase.CONNECTED, ConnectionPhase.DISCONNECTING)
    val busy: Boolean get() = phase in setOf(ConnectionPhase.PERMISSION, ConnectionPhase.CONNECTING,
        ConnectionPhase.CHECKING, ConnectionPhase.DISCONNECTING)
}

/** Shared process state remains available when the activity is recreated. */
object VpnStatus {
    private val mutableState = MutableStateFlow(ConnectionState())
    val state = mutableState.asStateFlow()
    fun publish(state: ConnectionState) { mutableState.value = state }
}
