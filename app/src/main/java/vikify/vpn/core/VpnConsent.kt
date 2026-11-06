package vikify.vpn.core

import android.content.Context

internal object VpnConsent {
    // Increase when the disclosed processing of VPN traffic changes.
    private const val VERSION = 1
    private fun prefs(context: Context) = context.getSharedPreferences("vpn_disclosure", Context.MODE_PRIVATE)

    fun accepted(context: Context) = prefs(context).getInt("accepted_version", 0) == VERSION
    fun set(context: Context, accepted: Boolean) =
        prefs(context).edit().putInt("accepted_version", if (accepted) VERSION else 0).commit()
}
