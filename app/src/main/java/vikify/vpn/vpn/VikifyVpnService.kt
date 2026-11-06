package vikify.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vikify.vpn.R
import vikify.vpn.core.ProxyHealthCheck
import vikify.vpn.core.Tun2socksManager
import vikify.vpn.core.XrayManager
import vikify.vpn.core.VpnConsent
import vikify.vpn.profile.ProfileManager
import vikify.vpn.profile.ProfileType
import vikify.vpn.subscription.ProxyProfile
import vikify.vpn.subscription.XrayConfigBuilder
import vikify.vpn.subscription.subscriptions
import vikify.vpn.ui.MainActivity
import vikify.vpn.ui.home.ActiveProfile
import vikify.vpn.ui.home.ConnectionPhase
import vikify.vpn.ui.home.ConnectionState
import vikify.vpn.ui.home.VpnStatus
import vikify.vpn.ui.home.profileLabel

class VikifyVpnService : VpnService() {
    private data class Command(val intent: Intent?, val startId: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.CONFLATED)
    private var session: Job? = null
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            for (command in commands) {
                session?.cancelAndJoin()
                session = null
                if (command.intent?.action != ACTION_CONNECT || !VpnConsent.accepted(this@VikifyVpnService)) {
                    VpnStatus.publish(ConnectionState())
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(command.startId)
                    continue
                }
                val store = subscriptions.state.value
                val id = command.intent.getStringExtra(EXTRA_PROFILE_ID)
                val revision = command.intent.getStringExtra(EXTRA_REVISION)
                val profile = store.profiles.find { it.id == id && it.revision == revision }
                if (!store.canConnect || profile == null) {
                    VpnStatus.publish(ConnectionState(ConnectionPhase.ERROR, error = R.string.error_profile_changed))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(command.startId)
                } else {
                    session = launch { runSession(profile, command.startId) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CONNECT && VpnConsent.accepted(this)) {
            try {
                val notification = notification(getString(R.string.status_connecting))
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                } else startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
                VpnStatus.publish(ConnectionState(ConnectionPhase.ERROR, error = R.string.error_foreground_failed))
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        commands.trySend(Command(intent, startId))
        return START_NOT_STICKY
    }

    private suspend fun runSession(profile: ProxyProfile, startId: Int) = nativeSessionMutex.withLock {
        val xray = XrayManager(this)
        val tun = Tun2socksManager()
        var fd: ParcelFileDescriptor? = null
        var errorRes = R.string.error_connect_failed
        var failed = false
        fun publish(phase: ConnectionPhase, text: Int) {
            if (latestStartId != startId) return
            VpnStatus.publish(ConnectionState(phase, ActiveProfile(profile)))
            val title = subscriptions.state.value.profileLabel(profile)?.let { getString(it) } ?: profile.name
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                notification(getString(text) + " · " + title))
        }
        try {
            publish(ConnectionPhase.CONNECTING, R.string.status_connecting)
            withContext(Dispatchers.IO) {
                val json = if (profile.legacyAsset != null) {
                    val type = ProfileType.entries.first { it.assetName == profile.legacyAsset }
                    ProfileManager(this@VikifyVpnService).generateConfigJsonForVpn(type, profile.uuid)
                } else XrayConfigBuilder.build(profile)
                xray.start(json)
            }
            errorRes = R.string.error_proxy_check
            publish(ConnectionPhase.CHECKING, R.string.status_checking)
            ProxyHealthCheck.check()
            check(xray.isAlive)
            errorRes = R.string.error_vpn_establish_failed
            val builder = Builder().setSession(getString(R.string.app_name))
                .setConfigureIntent(openAppIntent()).addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0).addDnsServer("8.8.8.8").addDnsServer("8.8.4.4")
                .setMtu(1500).addDisallowedApplication(packageName)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            fd = builder.establish() ?: error("VPN permission unavailable")
            errorRes = R.string.error_tun2socks_failed
            check(tun.start(checkNotNull(fd)))
            publish(ConnectionPhase.CONNECTED, R.string.status_connected)
            val connectivity = getSystemService(ConnectivityManager::class.java)
            var previousNetwork = connectivity.activeNetwork
            var nextCheck = android.os.SystemClock.elapsedRealtime() + 60_000
            while (scope.isActive) {
                delay(1000)
                errorRes = R.string.error_core_stopped
                check(xray.isAlive)
                val currentNetwork = connectivity.activeNetwork
                if (currentNetwork != previousNetwork || android.os.SystemClock.elapsedRealtime() >= nextCheck) {
                    previousNetwork = currentNetwork
                    errorRes = R.string.error_proxy_check
                    publish(ConnectionPhase.CHECKING, R.string.status_checking)
                    delay(1000)
                    ProxyHealthCheck.check()
                    publish(ConnectionPhase.CONNECTED, R.string.status_connected)
                    nextCheck = android.os.SystemClock.elapsedRealtime() + 60_000
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            // Every new command waits for cleanup, preventing overlapping native sessions.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { tun.stop() }
                runCatching { xray.stop() }
                runCatching { fd?.close() }
            }
        }
        if (failed && latestStartId == startId) {
            VpnStatus.publish(ConnectionState(ConnectionPhase.ERROR, ActiveProfile(profile), errorRes))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    override fun onRevoke() {
        VpnStatus.publish(ConnectionState())
        stopSelf()
    }

    override fun onDestroy() {
        commands.close()
        scope.cancel()
        if (VpnStatus.state.value.phase != ConnectionPhase.ERROR) VpnStatus.publish(ConnectionState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(text: String): Notification {
        val disconnect = PendingIntent.getService(this, 1,
            Intent(this, VikifyVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setContentTitle(getString(R.string.app_name))
            .setContentText(text).setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.disconnect), disconnect).setOngoing(true).build()
    }

    companion object {
        // onDestroy cannot suspend: a replacement service must still await the old native cleanup.
        private val nativeSessionMutex = Mutex()
        const val ACTION_CONNECT = "vikify.vpn.CONNECT"
        const val ACTION_DISCONNECT = "vikify.vpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_REVISION = "revision"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "vikify_vpn"
    }
}
