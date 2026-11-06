package vikify.vpn.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_NAME = "vikify_secure_prefs"
private const val KEY_UUID = "uuid"

/**
 * Stores the legacy UUID and the subscription snapshot in encrypted app-private preferences.
 * Subscription updates use one committed value so credentials and profiles change together.
 */
class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getUuid(default: String): String =
        prefs.getString(KEY_UUID, null) ?: default

    fun setUuid(uuid: String) {
        prefs.edit().putString(KEY_UUID, uuid).apply()
    }

    fun clearUuid() {
        prefs.edit().remove(KEY_UUID).apply()
    }

    fun getSubscription(): String? = prefs.getString("subscription", null)

    fun saveSubscription(json: String) {
        check(prefs.edit().putString("subscription", json).commit())
    }

    fun removeAccess() {
        check(prefs.edit().remove("subscription").remove(KEY_UUID).commit())
    }
}
