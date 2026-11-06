package vikify.vpn.core

import android.app.Application

class VikifyApplication : Application() {
    val subscriptions by lazy { vikify.vpn.subscription.SubscriptionRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // Migrate installations interrupted while older builds were running Xray.
        java.io.File(filesDir, "xray_config.json").delete()
    }
}
