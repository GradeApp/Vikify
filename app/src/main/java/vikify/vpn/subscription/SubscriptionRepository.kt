package vikify.vpn.subscription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vikify.vpn.core.SecurePrefs
import vikify.vpn.core.VikifyApplication
import vikify.vpn.core.isValidUuidV4
import vikify.vpn.profile.ProfileType

data class SubscriptionSnapshot(
    val url: String,
    val body: String,
    val updatedAt: Long,
    val selectedId: String,
    val unavailable: Boolean = false,
    val local: Boolean = false,
    val skippedProfiles: Int = 0
) {
    override fun toString(): String = "SubscriptionSnapshot(updatedAt=$updatedAt)"
}

class PreparedSubscription internal constructor(val url: String, val local: Boolean, val parsed: ParsedSubscription) {
    override fun toString(): String = "PreparedSubscription(profiles=${parsed.profiles.size})"
}

data class SubscriptionState(
    val snapshot: SubscriptionSnapshot? = null,
    val profiles: List<ProxyProfile> = emptyList(),
    val selectedId: String? = null,
    val error: SubscriptionError? = null
) {
    val canConnect: Boolean get() = profiles.isNotEmpty() && snapshot?.unavailable != true
    val selectedProfile: ProxyProfile? get() = profiles.find { it.id == selectedId }
}

val Context.subscriptions: SubscriptionRepository
    get() = (applicationContext as VikifyApplication).subscriptions

class SubscriptionRepository(
    private val context: Context,
    private val download: suspend (String) -> String = ::downloadSubscription
) {
    private val prefs by lazy { SecurePrefs(context) }
    private val mutex = Mutex()
    private var refreshVersion = 0L
    private val mutableState = MutableStateFlow(readStored())
    val state = mutableState.asStateFlow()

    suspend fun prepareImport(value: String): PreparedSubscription = when (val input = SubscriptionInput.parse(value)) {
        is SubscriptionInput.Remote -> {
            val normalized = input.uri.toASCIIString()
            PreparedSubscription(normalized, false, VlessParser.inspectSubscription(download(normalized)))
        }
        is SubscriptionInput.Local -> PreparedSubscription("", true, input.parsed)
    }

    suspend fun importSubscription(value: String) {
        val prepared = prepareImport(value)
        if (prepared.parsed.issues.isNotEmpty()) throw SubscriptionException(SubscriptionError.PARTIAL)
        commitImport(prepared)
    }

    suspend fun commitImport(prepared: PreparedSubscription) = mutex.withLock {
        val profiles = prepared.parsed.profiles
        currentCoroutineContext().ensureActive()
        val old = state.value
        val selection = old.selectedId?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        val snapshot = SubscriptionSnapshot(prepared.url, profiles.joinToString("\n") { it.source },
            System.currentTimeMillis(), selection, local = prepared.local, skippedProfiles = prepared.parsed.issues.size)
        withContext(NonCancellable) {
            persist(snapshot)
            refreshVersion++
            mutableState.value = SubscriptionState(snapshot, profiles, selection)
        }
        if (prepared.local) SubscriptionRefreshJob.cancel(context) else SubscriptionRefreshJob.schedule(context)
    }

    suspend fun refresh() {
        val request = mutex.withLock {
            val snapshot = state.value.snapshot ?: return@withLock null
            if (snapshot.local) return@withLock null
            ++refreshVersion to snapshot.url
        } ?: return
        try {
            // Network I/O must not hold the lock needed by selection/removal/import.
            val body = download(request.second)
            val parsed = VlessParser.inspectSubscription(body)
            val profiles = parsed.profiles
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                if (request.first != refreshVersion) return@withLock
                val current = state.value
                val previous = current.snapshot ?: return@withLock
                // Preserve the choice made while the response was being downloaded.
                if (parsed.issues.isNotEmpty() && profiles.none { it.id == current.selectedId })
                    throw SubscriptionException(SubscriptionError.PARTIAL)
                val selected = current.selectedId?.takeIf { id -> profiles.any { it.id == id } }
                    ?: profiles.first().id
                val updated = previous.copy(body = profiles.joinToString("\n") { it.source }, updatedAt = System.currentTimeMillis(),
                    selectedId = selected, unavailable = false, skippedProfiles = parsed.issues.size)
                withContext(NonCancellable) {
                    persist(updated)
                    mutableState.value = SubscriptionState(updated, profiles, selected)
                }
            }
        } catch (e: SubscriptionException) {
            val applied = mutex.withLock {
                if (request.first != refreshVersion) return@withLock false
                val current = state.value
                val previous = current.snapshot ?: return@withLock false
                withContext(NonCancellable) {
                    val snapshot = if (e.reason == SubscriptionError.UNAVAILABLE) previous.copy(unavailable = true) else previous
                    mutableState.value = current.copy(snapshot = snapshot, error = e.reason)
                    // Fail closed in memory even if saving the revocation fails.
                    if (snapshot.unavailable != previous.unavailable) persist(snapshot)
                }
                true
            }
            if (applied) throw e
        }
    }

    suspend fun select(id: String) = mutex.withLock {
        val old = state.value
        if (old.profiles.none { it.id == id }) return@withLock
        val snapshot = old.snapshot?.copy(selectedId = id)
        withContext(NonCancellable) {
            if (snapshot != null) persist(snapshot)
            mutableState.value = old.copy(snapshot = snapshot, selectedId = id)
        }
    }

    suspend fun remove() = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            // A configuration can outlive an abruptly killed service.
            val config = java.io.File(context.filesDir, "xray_config.json")
            if (config.exists() && !config.delete()) throw SubscriptionException(SubscriptionError.STORAGE)
            prefs.removeAccess()
            refreshVersion++
            mutableState.value = SubscriptionState()
            SubscriptionRefreshJob.cancel(context)
        }
    }

    private suspend fun persist(snapshot: SubscriptionSnapshot) = withContext(Dispatchers.IO) {
        try {
            prefs.saveSubscription(JSONObject().put("url", snapshot.url).put("body", snapshot.body)
                .put("updatedAt", snapshot.updatedAt).put("selectedId", snapshot.selectedId)
                .put("unavailable", snapshot.unavailable).put("local", snapshot.local)
                .put("skippedProfiles", snapshot.skippedProfiles).toString())
        } catch (_: Exception) {
            throw SubscriptionException(SubscriptionError.STORAGE)
        }
    }

    private fun readStored(): SubscriptionState = try {
        val stored = prefs.getSubscription()
        if (stored != null) {
            val json = JSONObject(stored)
            val snapshot = SubscriptionSnapshot(json.getString("url"), json.getString("body"),
                json.getLong("updatedAt"), json.getString("selectedId"), json.optBoolean("unavailable"),
                json.optBoolean("local"), json.optInt("skippedProfiles"))
            if (!snapshot.local) SubscriptionUrl.parse(snapshot.url)
            val profiles = VlessParser.parseSubscription(snapshot.body)
            val selected = snapshot.selectedId.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.first().id
            SubscriptionState(snapshot.copy(selectedId = selected), profiles, selected,
                if (snapshot.unavailable) SubscriptionError.UNAVAILABLE else null)
        } else {
            val uuid = prefs.getUuid("").trim()
            val profiles = if (isValidUuidV4(uuid)) legacyProfiles(uuid) else emptyList()
            SubscriptionState(profiles = profiles, selectedId = profiles.firstOrNull()?.id)
        }
    } catch (_: Exception) {
        SubscriptionState(error = SubscriptionError.STORAGE)
    }

    private fun legacyProfiles(uuid: String): List<ProxyProfile> = ProfileType.entries.map { type ->
        val template = context.assets.open("profiles/${type.assetName}").bufferedReader().use { it.readText() }
        val outbound = JSONObject(template).getJSONArray("outbounds").getJSONObject(0)
        val server = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        val port = server.getInt("port")
        val transport = stream.getString("network")
        val name = if (transport == "ws") "WebSocket · TLS" else "REALITY · $port"
        ProxyProfile("legacy-${type.name}", name, server.getString("address"), port, uuid,
            transport, stream.getString("security"), emptyMap(), template + uuid, type.assetName)
    }

}
