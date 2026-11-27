package vikify.vpn.ui.home

import android.app.Application
import vikify.vpn.R
import vikify.vpn.BuildConfig
import vikify.vpn.diagnostics.DiagnosticRunner
import vikify.vpn.diagnostics.DiagnosticReportData
import vikify.vpn.diagnostics.DiagnosticProtocol
import vikify.vpn.diagnostics.DiagnosticEvent
import vikify.vpn.diagnostics.DiagnosticStage
import vikify.vpn.diagnostics.DiagnosticStatus
import vikify.vpn.diagnostics.DiagnosticCode
import vikify.vpn.core.VpnConsent
import vikify.vpn.core.AvailabilityState
import vikify.vpn.core.EndpointAvailability
import vikify.vpn.core.ProbeEndpoint
import vikify.vpn.core.ProbeRoute
import vikify.vpn.core.ProfileReachability
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import vikify.vpn.subscription.SubscriptionError
import vikify.vpn.subscription.SubscriptionException
import vikify.vpn.subscription.SubscriptionRefreshJob
import vikify.vpn.subscription.SubscriptionState
import vikify.vpn.subscription.SubscriptionInput
import vikify.vpn.subscription.PreparedSubscription
import vikify.vpn.subscription.subscriptions
import java.util.concurrent.TimeUnit

data class HomeUiState(
    val store: SubscriptionState = SubscriptionState(),
    val connection: ConnectionState = ConnectionState(),
    val importOpen: Boolean = false,
    val importUrl: String = "",
    val importHost: String? = null,
    val importLocal: Boolean = false,
    val importPreview: PreparedSubscription? = null,
    val importError: Int? = null,
    val working: Boolean = false,
    val profilesOpen: Boolean = false,
    val removeOpen: Boolean = false,
    val helpOpen: Boolean = false,
    val consentOpen: Boolean = false,
    val consentError: Boolean = false,
    val consentAccepted: Boolean = false,
    val availability: AvailabilityState = AvailabilityState(),
    val operationError: SubscriptionError? = null,
    val diagnosticsOpen: Boolean = false,
    val diagnostic: DiagnosticUiState = DiagnosticUiState()
)

class HomeViewModel(application: Application, private val controller: VpnController) : AndroidViewModel(application) {
    private val repository = application.subscriptions
    private val mutableState = MutableStateFlow(HomeUiState(repository.state.value, controller.state.value,
        consentAccepted = VpnConsent.accepted(application)))
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var lastAutoRefresh = 0L
    private var operationGeneration = 0
    private var diagnosticJob: Job? = null
    private var diagnosticGeneration = 0
    private var diagnosticStore: SubscriptionState? = null
    private var diagnosticConnection: ConnectionState? = null
    private var probeRoute: ProbeRoute? = null
    private val reachability = ProfileReachability(application) { route ->
        probeRoute = route
        syncAvailability()
    }
    private val availability = EndpointAvailability(viewModelScope, { route, endpoint ->
        reachability.attempt(route as ProbeRoute, endpoint)
    })

    init {
        viewModelScope.launch { repository.state.collect { store ->
            if (state.value.diagnostic.busy && diagnosticStore != store) cancelDiagnostics()
            mutableState.update { it.copy(store = store, diagnostic = it.diagnostic.copy(
                canConnect = canConnectDiagnostic(store, it.connection))) }
            syncAvailability()
        } }
        viewModelScope.launch { availability.state.collect { value -> mutableState.update { it.copy(availability = value) } } }
        viewModelScope.launch { controller.state.collect { connection ->
            val expected = diagnosticConnection
            if (state.value.diagnostic.busy && expected != null &&
                (connection.profile != expected.profile ||
                    connection.hasSession != expected.hasSession ||
                    connection.phase == ConnectionPhase.ERROR ||
                    connection.phase == ConnectionPhase.DISCONNECTING)) cancelDiagnostics()
            mutableState.update { it.copy(connection = connection, diagnostic = it.diagnostic.copy(
                canConnect = canConnectDiagnostic(it.store, connection))) }
            reachability.setOwnVpn(connection.hasSession)
        } }
        if (repository.state.value.snapshot?.local == false) SubscriptionRefreshJob.schedule(application)
        else SubscriptionRefreshJob.cancel(application)
    }

    fun openImport() { closeDiagnostics(); mutableState.update { it.copy(importOpen = true, importUrl = "", importHost = null, importLocal = false, importPreview = null, importError = null) } }
    fun setImportUrl(value: String) { mutableState.update { it.copy(importUrl = value, importHost = null, importLocal = false, importPreview = null, importError = null) } }
    fun cancelImportPreview() { mutableState.update { it.copy(importPreview = null) } }
    fun importProblem(message: Int) {
        mutableState.update { if (it.importOpen) it.copy(importError = message) else it }
    }

    private fun checkedInput(text: String): SubscriptionInput? {
        return try { SubscriptionInput.parse(text) } catch (e: SubscriptionException) {
            importProblem(e.reason.message())
            null
        }
    }

    fun offerImport(text: String) {
        if (!state.value.importOpen || state.value.working) return
        val input = checkedInput(text.trim()) ?: return
        mutableState.update { it.copy(importUrl = if (input is SubscriptionInput.Remote) input.uri.toASCIIString() else text.trim(),
            importHost = if (input is SubscriptionInput.Remote) input.uri.rawAuthority else "",
            importLocal = input is SubscriptionInput.Local, importPreview = null, importError = null) }
    }
    fun closeImport() {
        operationGeneration++
        operation?.cancel()
        mutableState.update { it.copy(importOpen = false, importUrl = "", importHost = null, importPreview = null, importLocal = false, importError = null, working = false) }
    }

    fun importSubscription() {
        if (state.value.working) return
        val value = state.value.importUrl.trim()
        val pending = state.value.importPreview
        if (pending == null && checkedInput(value) == null) return
        val generation = ++operationGeneration
        operation = viewModelScope.launch {
            mutableState.update { it.copy(working = true, importError = null) }
            try {
                val prepared = pending ?: repository.prepareImport(value)
                if (pending == null && prepared.parsed.issues.isNotEmpty()) {
                    if (generation == operationGeneration) mutableState.update { it.copy(importPreview = prepared) }
                } else {
                    repository.commitImport(prepared)
                    if (generation == operationGeneration) mutableState.update { it.copy(importOpen = false, importUrl = "",
                        importHost = null, importLocal = false, importPreview = null, operationError = null) }
                }
            } catch (e: SubscriptionException) {
                if (generation == operationGeneration) mutableState.update { it.copy(importError = e.reason.message()) }
            } finally {
                if (generation == operationGeneration) mutableState.update { it.copy(working = false) }
            }
        }
    }

    fun refresh() {
        if (state.value.working || state.value.importOpen || state.value.diagnosticsOpen) return
        val generation = ++operationGeneration
        operation = viewModelScope.launch {
            mutableState.update { it.copy(working = true, operationError = null) }
            try {
                repository.refresh()
            } catch (e: SubscriptionException) {
                mutableState.update { it.copy(operationError = e.reason) }
            } finally {
                if (generation == operationGeneration) mutableState.update { it.copy(working = false) }
            }
        }
    }

    fun refreshIfStale() {
        val snapshot = repository.state.value.snapshot ?: return
        if (snapshot.local) return
        val now = System.currentTimeMillis()
        if (now - snapshot.updatedAt >= TimeUnit.HOURS.toMillis(24) &&
            now - lastAutoRefresh > TimeUnit.MINUTES.toMillis(15)) {
            lastAutoRefresh = now
            refresh()
        }
    }

    private fun syncAvailability() {
        val store = repository.state.value
        val targets = if (store.canConnect) store.profiles.map { ProbeEndpoint(it.host.lowercase(), it.port) }.toSet()
            else emptySet()
        if (availability.setContext(probeRoute, targets) && state.value.profilesOpen) availability.check()
    }
    fun startAvailability() { reachability.start() }
    fun stopAvailability() { cancelDiagnostics(); reachability.stop(); availability.stop() }
    fun checkProfiles() { availability.check(force = true) }
    fun showProfiles(show: Boolean) {
        mutableState.update { it.copy(profilesOpen = show) }
        if (show) availability.check()
    }
    override fun onCleared() { diagnosticJob?.cancel(); reachability.stop(); availability.stop(); super.onCleared() }
    fun select(id: String) {
        cancelDiagnostics()
        viewModelScope.launch {
            try { repository.select(id) } catch (e: SubscriptionException) {
                mutableState.update { it.copy(operationError = e.reason) }
            }
            showProfiles(false)
        }
    }

    fun connect() {
        cancelDiagnostics()
        val store = state.value.store
        if (!store.canConnect || store.selectedProfile == null) return
        if (!VpnConsent.accepted(getApplication())) {
            mutableState.update { it.copy(consentOpen = true, consentError = false) }
            return
        }
        store.selectedProfile?.let(controller::connect)
    }

    fun closeConsent() { mutableState.update { it.copy(consentOpen = false, consentError = false) } }

    fun acceptConsent() {
        if (!state.value.consentOpen) return
        if (!VpnConsent.set(getApplication(), true)) {
            mutableState.update { it.copy(consentError = true) }
            return
        }
        mutableState.update { it.copy(consentAccepted = true, consentOpen = false, consentError = false) }
        connect()
    }

    fun revokeConsent() {
        controller.disconnect()
        val cleared = VpnConsent.set(getApplication(), false)
        mutableState.update { it.copy(consentAccepted = !cleared, consentError = !cleared) }
    }
    fun disconnect() { cancelDiagnostics(); controller.disconnect() }
    fun showRemove(show: Boolean) { mutableState.update { it.copy(removeOpen = show) } }
    fun showHelp(show: Boolean) { mutableState.update { it.copy(helpOpen = show) } }

    fun remove() {
        closeDiagnostics()
        if (state.value.working) return
        val generation = ++operationGeneration
        operation = viewModelScope.launch {
            mutableState.update { it.copy(working = true, removeOpen = false, operationError = null) }
            try {
                controller.disconnect()
                withTimeout(30_000) { controller.state.first { !it.hasSession && !it.busy } }
                repository.remove()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                mutableState.update { it.copy(operationError = SubscriptionError.STORAGE) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(operationError = SubscriptionError.STORAGE) }
            } finally {
                if (generation == operationGeneration) mutableState.update { it.copy(working = false) }
            }
        }
    }

    /** Diagnostic operations never call repository.refresh(), select(), connect() or disconnect(). */
    private fun canConnectDiagnostic(store: SubscriptionState, connection: ConnectionState): Boolean {
        val selected = store.selectedProfile
        val alreadySelected = connection.hasSession && connection.profile?.id == selected?.id &&
            connection.profile?.revision == selected?.revision
        return store.canConnect && !connection.busy && !alreadySelected
    }

    fun openDiagnostics() {
        if (state.value.working) return
        val current = state.value
        mutableState.update { it.copy(diagnosticsOpen = true,
            diagnostic = DiagnosticUiState(canConnect = canConnectDiagnostic(current.store, current.connection))) }
    }

    fun runDiagnostics(allowDirect: Boolean) {
        if (!state.value.diagnosticsOpen || state.value.diagnostic.busy || state.value.working) return
        val store = repository.state.value
        val connection = controller.state.value
        diagnosticStore = store
        diagnosticConnection = connection
        val generation = ++diagnosticGeneration
        mutableState.update { it.copy(diagnostic = it.diagnostic.copy(busy = true,
            events = emptyList(), report = null, interrupted = false, shareError = false)) }
        diagnosticJob = viewModelScope.launch {
            try {
                val report = DiagnosticRunner(getApplication()).run(store, connection, allowDirect) { event ->
                    if (generation == diagnosticGeneration) mutableState.update {
                        it.copy(diagnostic = it.diagnostic.copy(events = it.diagnostic.events + event))
                    }
                }
                if (generation == diagnosticGeneration) mutableState.update {
                    it.copy(diagnostic = it.diagnostic.copy(busy = false, report = report))
                }
            } catch (cancelled: CancellationException) {
                if (generation == diagnosticGeneration) cancelDiagnostics()
                throw cancelled
            } catch (_: Exception) {
                // Never render or serialize exception.message: it may contain credentials.
                if (generation == diagnosticGeneration) finishInterruptedDiagnostics(DiagnosticCode.INTERNAL_ERROR)
            } finally {
                if (generation == diagnosticGeneration) {
                    diagnosticJob = null
                    diagnosticStore = null
                    diagnosticConnection = null
                }
            }
        }
    }

    fun cancelDiagnostics() = finishInterruptedDiagnostics(DiagnosticCode.CANCELLED)

    private fun finishInterruptedDiagnostics(code: DiagnosticCode) {
        if (!state.value.diagnostic.busy) return
        val selected = diagnosticStore?.selectedProfile
        val protocol = when (selected?.transport) {
            "tcp" -> DiagnosticProtocol.REALITY
            "xhttp" -> DiagnosticProtocol.XHTTP_TLS
            "ws" -> DiagnosticProtocol.WEBSOCKET_TLS
            else -> DiagnosticProtocol.UNKNOWN
        }
        ++diagnosticGeneration
        diagnosticJob?.cancel()
        diagnosticJob = null
        diagnosticStore = null
        diagnosticConnection = null
        mutableState.update {
            val prior = it.diagnostic.events
            val stage = DiagnosticStage.values().getOrElse(
                (prior.lastOrNull()?.stage?.ordinal ?: -1) + 1) { DiagnosticStage.ANDROID_VPN }
            val events = if (prior.lastOrNull()?.code == code) prior else prior + DiagnosticEvent(
                stage, if (code == DiagnosticCode.CANCELLED) DiagnosticStatus.CANCELLED else DiagnosticStatus.FAIL, code)
            it.copy(diagnostic = it.diagnostic.copy(busy = false, interrupted = code == DiagnosticCode.CANCELLED,
                events = events,
                report = DiagnosticReportData(appVersionCode = BuildConfig.VERSION_CODE,
                    androidApi = android.os.Build.VERSION.SDK_INT, protocol = protocol,
                    events = events, completed = false)))
        }
    }

    fun closeDiagnostics() {
        cancelDiagnostics()
        mutableState.update { it.copy(diagnosticsOpen = false, diagnostic = DiagnosticUiState()) }
    }

    fun diagnosticShareFailed() {
        mutableState.update { it.copy(diagnostic = it.diagnostic.copy(shareError = true)) }
    }
}

fun homeViewModelFactory(application: Application, controller: VpnController) =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == HomeViewModel::class.java)
            return HomeViewModel(application, controller) as T
        }
    }
