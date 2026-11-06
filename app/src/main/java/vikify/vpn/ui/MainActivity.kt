package vikify.vpn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import vikify.vpn.ui.home.HomeViewModel
import vikify.vpn.ui.home.VikifyHomeScreen
import vikify.vpn.ui.home.homeViewModelFactory
import vikify.vpn.ui.theme.VikifyTheme

class MainActivity : ComponentActivity() {
    private lateinit var home: HomeViewModel
    private val bridge: VpnBridgeViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bridge.onVpnPermissionResult(it.resultCode == RESULT_OK)
    }
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bridge.setPermissionLauncher { vpnPermission.launch(it) }
        bridge.setPostNotificationsPreflight {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("notifications_requested", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested", true).apply()
                notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        home = ViewModelProvider(this, homeViewModelFactory(application, bridge.vpnController))[HomeViewModel::class.java]
        // The separate QA installation can measure the three scene stages without changing saved user access.
        val flightStage = if (vikify.vpn.BuildConfig.DEBUG && packageName.endsWith(".uiqa"))
            intent.getIntExtra("flightStage", 2).coerceIn(0, 2) else 2
        setContent { VikifyTheme { VikifyHomeScreen(home, flightStage) } }
    }

    override fun onStart() {
        super.onStart()
        home.startAvailability()
    }

    override fun onStop() {
        home.stopAvailability()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        bridge.bindHost(this)
        home.refreshIfStale()
    }

    override fun onPause() {
        bridge.unbindHost(this)
        super.onPause()
    }
}
