package vikify.vpn.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import vikify.vpn.R
import vikify.vpn.subscription.ProxyProfile
import vikify.vpn.subscription.subscriptions
import vikify.vpn.ui.home.ActiveProfile
import vikify.vpn.ui.home.ConnectionPhase
import vikify.vpn.ui.home.ConnectionState
import vikify.vpn.ui.home.VpnController
import vikify.vpn.ui.home.VpnStatus
import vikify.vpn.vpn.VikifyVpnService
import java.lang.ref.WeakReference

class VpnBridgeViewModel(application: Application) : AndroidViewModel(application) {
    private var host: WeakReference<ComponentActivity>? = null
    private var permissionLauncher: ((Intent) -> Unit)? = null
    private var notificationsPreflight: (() -> Unit)? = null
    private var pending: ProxyProfile? = null

    val vpnController = object : VpnController {
        override val state = VpnStatus.state

        override fun connect(profile: ProxyProfile) {
            if (state.value.busy) return
            val activity = host?.get() ?: return
            val permission = VpnService.prepare(activity)
            if (permission != null) {
                pending = profile
                VpnStatus.publish(ConnectionState(ConnectionPhase.PERMISSION, ActiveProfile(profile)))
                permissionLauncher?.invoke(permission)
            } else start(profile)
        }

        override fun disconnect() {
            pending = null
            val current = state.value
            if (!current.hasSession) {
                VpnStatus.publish(ConnectionState())
                return
            }
            VpnStatus.publish(current.copy(phase = ConnectionPhase.DISCONNECTING))
            try {
                getApplication<Application>().startService(Intent(getApplication(), VikifyVpnService::class.java)
                    .setAction(VikifyVpnService.ACTION_DISCONNECT))
            } catch (_: RuntimeException) {
                VpnStatus.publish(current.copy(error = R.string.error_disconnect))
            }
        }
    }

    fun bindHost(activity: ComponentActivity) { host = WeakReference(activity) }
    fun unbindHost(activity: ComponentActivity) {
        if (host?.get() === activity) host = null
    }
    fun setPermissionLauncher(launcher: (Intent) -> Unit) { permissionLauncher = launcher }
    fun setPostNotificationsPreflight(preflight: () -> Unit) { notificationsPreflight = preflight }

    fun onVpnPermissionResult(granted: Boolean) {
        val requested = pending
        pending = null
        if (requested == null) return
        if (granted) start(requested) else VpnStatus.publish(ConnectionState())
    }

    private fun start(profile: ProxyProfile) {
        val context = getApplication<Application>()
        val store = context.subscriptions.state.value
        if (!store.canConnect || store.profiles.none { it.id == profile.id && it.revision == profile.revision }) {
            VpnStatus.publish(ConnectionState(ConnectionPhase.ERROR, error = R.string.error_profile_changed))
            return
        }
        VpnStatus.publish(ConnectionState(ConnectionPhase.CONNECTING, ActiveProfile(profile)))
        try {
            notificationsPreflight?.invoke()
            ContextCompat.startForegroundService(context, Intent(context, VikifyVpnService::class.java)
                .setAction(VikifyVpnService.ACTION_CONNECT)
                .putExtra(VikifyVpnService.EXTRA_PROFILE_ID, profile.id)
                .putExtra(VikifyVpnService.EXTRA_REVISION, profile.revision))
        } catch (_: RuntimeException) {
            VpnStatus.publish(ConnectionState(ConnectionPhase.ERROR, error = R.string.error_vpn_service_start))
        }
    }
}
