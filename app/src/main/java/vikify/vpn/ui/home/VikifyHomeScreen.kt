package vikify.vpn.ui.home

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vikify.vpn.R
import vikify.vpn.BuildConfig
import vikify.vpn.core.Availability
import vikify.vpn.core.ProbeEndpoint
import vikify.vpn.subscription.ProxyProfile
import vikify.vpn.subscription.SubscriptionError
import vikify.vpn.subscription.SubscriptionState
import java.text.DateFormat
import java.util.Date

private val Panel = Color(0xFF0A1D20)
private val ConnectionPanel = Panel.copy(alpha = .76f)
private val SecondaryPanel = Panel.copy(alpha = .69f)
private val DialogPanel = Panel.copy(alpha = .80f)
private val Ink = Color(0xFFFFF6E5)
private val Muted = Color(0xFFE0ECE8)
private val Accent = Color(0xFFFFDB97)
private val AvailableInk = Color(0xFFA7EBB2)
private val UnavailableInk = Color(0xFFFFAAA5)
private val Shape = RoundedCornerShape(20.dp)

@Composable
fun VikifyHomeScreen(viewModel: HomeViewModel, flightStage: Int = 2) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var details by rememberSaveable { mutableStateOf(false) }
    var policy by rememberSaveable { mutableStateOf(false) }
    val hasAccess = state.store.profiles.isNotEmpty() || state.store.error != null
    val modal = policy || state.consentOpen || state.importOpen || state.removeOpen || state.helpOpen || (details && hasAccess)
    FlightScene(active = !modal, stage = flightStage) {
        LagoonBackdrop(Modifier.fillMaxSize())
        if (state.profilesOpen) {
            ProfilePicker(state, viewModel)
        } else BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val wide = hasAccess && maxWidth >= 560.dp && maxWidth > maxHeight
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = if (wide) 920.dp else 580.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("VIKIFY", Modifier.birdClearance(), color = Ink,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { viewModel.showHelp(true) }, modifier = Modifier.birdClearance()) {
                        Text(stringResource(R.string.help), color = Ink)
                    }
                }
                val controls: @Composable () -> Unit = { ConnectionCard(state, viewModel) }
                val settings: @Composable () -> Unit = {
                    state.store.selectedProfile?.let { ProfileCard(state, it, viewModel) }
                    if (hasAccess) SubscriptionCard(state, viewModel) { details = true }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) { settings() }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) { controls(); settings() }
                }
            }
        }
    }
    when {
        policy -> SceneDialog({ policy = false }, flightStage) {
            DialogTitle(R.string.privacy_policy)
            val context = LocalContext.current
            val resources = context.resources
            if (BuildConfig.PUBLIC_PRIVACY_URL.isNotEmpty()) {
                var browserUnavailable by remember { mutableStateOf(false) }
                LinkButton(R.string.policy_online) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PUBLIC_PRIVACY_URL)))
                    } catch (_: ActivityNotFoundException) { browserUnavailable = true }
                }
                if (browserUnavailable) ErrorText(stringResource(R.string.policy_no_browser))
            }
            val content = remember(resources.configuration.locales.toLanguageTags()) {
                val html = resources.openRawResource(R.raw.privacy).bufferedReader(Charsets.UTF_8).use { it.readText() }
                HtmlCompat.fromHtml(html.substringAfter("<main>").substringBefore("</main>"),
                    HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            }
            Text(content, color = Ink)
            LinkButton(R.string.close) { policy = false }
        }
        state.consentOpen -> SceneDialog(viewModel::closeConsent, flightStage) {
            DialogTitle(R.string.vpn_consent_title)
            Text(stringResource(R.string.vpn_disclosure), color = Ink)
            LinkButton(R.string.privacy_policy) { policy = true }
            if (state.consentError) ErrorText(stringResource(R.string.consent_save_error))
            DialogActions(R.string.cancel, viewModel::closeConsent, R.string.agree_connect, onRight = viewModel::acceptConsent)
        }
        state.importOpen -> ImportDialog(state, viewModel, flightStage)
        state.removeOpen -> SceneDialog(viewModel::closeRemove, flightStage) {
            DialogTitle(R.string.remove_access)
            Text(stringResource(R.string.remove_hint), color = Muted)
            DialogActions(R.string.cancel, viewModel::closeRemove,
                if (state.connection.hasSession) R.string.disconnect_remove else R.string.remove) {
                details = false
                viewModel.remove()
            }
        }
        state.helpOpen -> SceneDialog({ viewModel.showHelp(false) }, flightStage) {
            DialogTitle(R.string.help)
            Text(stringResource(R.string.help_body), color = Muted)
            LinkButton(R.string.privacy_policy) { policy = true }
            if (state.consentAccepted) LinkButton(R.string.revoke_consent, action = viewModel::revokeConsent)
            if (state.consentError) ErrorText(stringResource(R.string.consent_save_error))
            LinkButton(R.string.close) { viewModel.showHelp(false) }
        }
        details && hasAccess -> SceneDialog({ details = false }, flightStage) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                DialogTitle(R.string.access_details, Modifier.weight(1f))
                LinkButton(R.string.close) { details = false }
            }
            state.store.snapshot?.let { snapshot ->
                Text(stringResource(R.string.last_updated, DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(Date(snapshot.updatedAt))), color = Muted)
                if (snapshot.local) Text(stringResource(R.string.local_profiles_hint), color = Muted,
                    style = MaterialTheme.typography.bodySmall)
                if (snapshot.skippedProfiles > 0) Text(stringResource(R.string.import_skipped_saved, snapshot.skippedProfiles),
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            } ?: Text(stringResource(R.string.legacy_hint), color = Muted)
            LinkButton(if (state.store.snapshot == null) R.string.add_subscription else R.string.replace_link,
                enabled = !state.working, action = viewModel::openImport)
            LinkButton(R.string.remove_access, !state.working && !state.connection.busy) { viewModel.showRemove(true) }
            state.store.profiles.forEach { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(profileTitle(profile, state.store), color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(state.store.profileDescription(profile)),
                        color = Ink, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.profile_connection_info, profile.protocolLabel, profile.port),
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (state.store.profileLabel(profile) != null) {
                        Text(stringResource(R.string.profile_original_name, profile.name),
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun HomeViewModel.closeRemove() = showRemove(false)

@Composable
private fun ConnectionCard(state: HomeUiState, vm: HomeViewModel) {
    val con = state.connection
    val selected = state.store.selectedProfile
    val active = con.profile
    val same = active?.id == selected?.id && active?.revision == selected?.revision
    val stop = con.busy || (con.hasSession && (same || !state.store.canConnect))
    CardPanel(ConnectionPanel) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(con.phase.label()), color = Accent, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            if (con.hasSession && active != null && active.id != selected?.id) {
                state.store.profiles.find { it.id == active.id }?.let {
                    Text(stringResource(R.string.active_name, profileTitle(it, state.store)), color = Ink)
                } ?: Text(stringResource(R.string.active_profile_removed), color = Muted)
            }
        }
        if (con.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        if (state.store.profiles.isEmpty() && !con.hasSession) {
            PrimaryButton(stringResource(R.string.add_subscription), !state.working, vm::openImport)
            Text(stringResource(R.string.welcome_body), color = Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            val action = when {
                con.phase == ConnectionPhase.DISCONNECTING -> R.string.status_disconnecting
                con.busy -> R.string.cancel
                stop -> R.string.disconnect
                con.hasSession && active?.id == selected?.id -> R.string.apply_reconnect
                con.hasSession -> R.string.switch_selected
                else -> R.string.connect
            }
            PrimaryButton(stringResource(action), con.phase != ConnectionPhase.DISCONNECTING &&
                (stop || (state.store.canConnect && !state.working))) { if (stop) vm.disconnect() else vm.connect() }
            if (con.hasSession && !stop) LinkButton(R.string.disconnect, action = vm::disconnect)
        }
        con.error?.let { ErrorText(stringResource(it)) }
    }
}

@Composable
private fun sourceProfileTitle(profile: ProxyProfile, index: Int): String =
    if (profile.name.contains(profile.host, ignoreCase = true))
        stringResource(R.string.unnamed_profile, index + 1)
    else profile.name

@Composable
private fun profileTitle(profile: ProxyProfile, store: SubscriptionState, label: Int? = store.profileLabel(profile)): String =
    if (label != null) stringResource(label)
    else sourceProfileTitle(profile, store.profiles.indexOfFirst { it.id == profile.id })

private fun HomeUiState.availabilityOf(profile: ProxyProfile) =
    availability.statuses[ProbeEndpoint(profile.host.lowercase(), profile.port)] ?: Availability.UNKNOWN

private fun Availability.ink() = when (this) {
    Availability.REACHABLE -> AvailableInk
    Availability.UNREACHABLE -> UnavailableInk
    else -> Ink
}

@Composable
private fun Availability.description(): String = stringResource(when (this) {
    Availability.REACHABLE -> R.string.availability_reachable
    Availability.UNREACHABLE -> R.string.availability_unreachable
    Availability.CHECKING -> R.string.availability_checking
    Availability.UNKNOWN -> R.string.availability_unknown
})

@Composable
private fun ProfileCard(state: HomeUiState, profile: ProxyProfile, vm: HomeViewModel) {
    val availability = state.availabilityOf(profile)
    val status = availability.description()
    CardPanel(SecondaryPanel) {
        CompactLine({
            Text(stringResource(R.string.selected_profile), color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(profileTitle(profile, state.store), Modifier.semantics { stateDescription = status },
                color = availability.ink(), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }) {
            LinkButton(R.string.choose_profile, !state.connection.busy && !state.working) { vm.showProfiles(true) }
        }
        val active = state.connection.profile
        if (state.connection.hasSession && active?.id == profile.id && active.revision != profile.revision)
            Text(stringResource(R.string.profile_updated), color = Accent, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SubscriptionCard(state: HomeUiState, vm: HomeViewModel, onDetails: () -> Unit) {
    CardPanel(SecondaryPanel) {
        CompactLine({
            Text(if (state.store.snapshot == null) stringResource(R.string.saved_access)
                else stringResource(if (state.store.snapshot.local) R.string.local_profiles_count else R.string.subscription_count,
                    state.store.profiles.size), color = Ink,
                style = MaterialTheme.typography.titleMedium)
        }) {
            if (state.store.snapshot?.local == false) LinkButton(R.string.refresh_subscription, !state.working, vm::refresh)
            LinkButton(R.string.details, action = onDetails)
        }
        (state.operationError ?: state.store.error)?.let { ErrorText(stringResource(it.message())) }
        if (state.working && !state.importOpen) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
    }
}

@Composable
private fun CompactLine(label: @Composable ColumnScope.() -> Unit, actions: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale >= 276.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), content = label)
                actions()
            }
        } else {
            Column { Column(content = label); Row(content = actions) }
        }
    }
}

@Composable
private fun CardPanel(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = Shape, color = color, contentColor = Ink) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp).birdClearance(),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun LinkButton(label: Int, enabled: Boolean = true, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(label), color = if (enabled) Accent else Muted)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF213333)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = Color(0xFFFFD2CD), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun DialogTitle(label: Int, modifier: Modifier = Modifier) {
    Text(stringResource(label), modifier = modifier, style = MaterialTheme.typography.titleLarge)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogActions(left: Int, onLeft: () -> Unit, right: Int, enabled: Boolean = true, onRight: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        LinkButton(left, action = onLeft)
        LinkButton(right, enabled, onRight)
    }
}

@Composable
private fun SceneDialog(onDismiss: () -> Unit, stage: Int,
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as?
            androidx.compose.ui.window.DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            val previousMode = dialogWindow?.attributes?.softInputMode
            val previousFitInsets = if (android.os.Build.VERSION.SDK_INT >= 35)
                dialogWindow?.attributes?.fitInsetsTypes else null
            if (android.os.Build.VERSION.SDK_INT >= 35 && dialogWindow != null) {
                // Compose already applies safe drawing insets. Target 35+ dialogs
                // otherwise get a smaller platform frame than their Compose layout.
                dialogWindow.attributes = dialogWindow.attributes.apply { setFitInsetsTypes(0) }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // Compose handles IME insets below. Prevent a second platform
                // resize/pan from collapsing the dialog on short landscape screens.
                dialogWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
            onDispose {
                if (previousMode != null) dialogWindow.setSoftInputMode(previousMode)
                if (android.os.Build.VERSION.SDK_INT >= 35 && previousFitInsets != null && dialogWindow != null) {
                    dialogWindow.attributes = dialogWindow.attributes.apply { setFitInsetsTypes(previousFitInsets) }
                }
            }
        }
        FlightScene(true, stage) {
            Box(Modifier.matchParentSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),
                contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = Shape,
                    color = DialogPanel, contentColor = Ink) {
                    Column(Modifier.verticalScroll(scroll).padding(16.dp).birdClearance(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePicker(state: HomeUiState, vm: HomeViewModel) {
    BackHandler { vm.showProfiles(false) }
    val title = stringResource(R.string.profile_list_title)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .22f))
            .pointerInput(vm) { detectTapGestures { vm.showProfiles(false) } })
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().semantics { paneTitle = title },
                color = DialogPanel, contentColor = Ink, shape = Shape) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
                    Row(Modifier.fillMaxWidth().birdClearance().pointerInput(vm) {
                        var drag = 0f
                        detectVerticalDragGestures(onDragStart = { drag = 0f },
                            onDragEnd = { if (drag > threshold) vm.showProfiles(false) }) { _, dy -> drag += dy }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        LinkButton(R.string.close) { vm.showProfiles(false) }
                    }
                    Row(Modifier.fillMaxWidth().birdClearance(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (state.availability.canCheck) R.string.availability_hint
                            else R.string.availability_no_network), Modifier.weight(1f),
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                        LinkButton(if (state.availability.busy) R.string.availability_checking else R.string.availability_check,
                            state.availability.canCheck && !state.availability.busy, vm::checkProfiles)
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).birdClearance()) {
                        ProfileChoices(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileChoices(state: HomeUiState, vm: HomeViewModel) {
    for (profile in state.store.profiles) {
        val chosen = profile.id == state.store.selectedId
        val availability = state.availabilityOf(profile)
        val status = availability.description()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = status }.selectable(chosen, role = Role.RadioButton,
            onClick = { vm.select(profile.id) }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(chosen, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accent,
                unselectedColor = Muted))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                val label = state.store.profileLabel(profile)
                val title = profileTitle(profile, state.store, label)
                Text(title, color = availability.ink(), fontWeight = FontWeight.SemiBold)
                val protocol = when (profile.transport) { "tcp" -> "REALITY"; "xhttp" -> "XHTTP"; else -> "WebSocket" }
                if (label == null && !title.contains(protocol, true) && !(profile.transport == "ws" && title.contains("WS", true)))
                    Text(protocol, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (state.connection.hasSession && profile.id == state.connection.profile?.id)
                    Text(stringResource(R.string.active_profile), color = Accent, style = MaterialTheme.typography.labelSmall)
            }
            Text(when (availability) {
                Availability.REACHABLE -> "✓"
                Availability.UNREACHABLE -> "×"
                Availability.CHECKING -> "…"
                Availability.UNKNOWN -> "—"
            }, Modifier.padding(start = 6.dp).clearAndSetSemantics { }, color = availability.ink())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImportDialog(state: HomeUiState, vm: HomeViewModel, stage: Int) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var qrOptions by rememberSaveable { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    val enabled = !state.working && !decoding
    val replacing = state.store.snapshot != null
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> result.data?.getStringExtra(QrScanActivity.EXTRA_TEXT)?.let(vm::offerImport)
            QrScanActivity.RESULT_UNAVAILABLE -> vm.importProblem(R.string.qr_camera_unavailable)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan.launch(Intent(context, QrScanActivity::class.java))
        else vm.importProblem(R.string.qr_permission_denied)
    }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            decoding = true
            try {
                val value = withContext(Dispatchers.IO) { SubscriptionQr.image(context.contentResolver, uri) }
                if (value == null) vm.importProblem(R.string.qr_image_invalid) else vm.offerImport(value)
            } finally { decoding = false }
        }
    }
    LaunchedEffect(state.importError) { if (state.importError != null) scroll.scrollTo(0) }
    SceneDialog(vm::closeImport, stage, scroll = scroll) {
        // The field belongs to the dialog window, so use its input controller.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val fieldVisibility = remember { BringIntoViewRequester() }
        var fieldFocused by remember { mutableStateOf(false) }
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(fieldFocused, imeBottom) {
            if (fieldFocused && imeBottom > 0) {
                // Wait for the inset to resize the scroll viewport before
                // revealing the editor, including large text in landscape.
                withFrameNanos { }
                fieldVisibility.bringIntoView()
            }
        }
        fun submit() {
            focus.clearFocus()
            keyboard?.hide()
            if (enabled && state.importUrl.isNotBlank()) vm.importSubscription()
        }
        DialogTitle(if (replacing) R.string.replace_link else R.string.add_subscription)
        state.importError?.let { ErrorText(stringResource(it)) }
        state.importPreview?.let { preview ->
            Text(stringResource(R.string.import_review, preview.parsed.profiles.size, preview.parsed.issues.size), color = Ink)
            preview.parsed.issues.take(8).forEach { issue ->
                val reason = when (issue.reason) {
                    vikify.vpn.subscription.ImportIssueReason.UNSUPPORTED -> R.string.import_issue_unsupported
                    vikify.vpn.subscription.ImportIssueReason.INVALID -> R.string.import_issue_invalid
                    vikify.vpn.subscription.ImportIssueReason.DUPLICATE -> R.string.import_issue_duplicate
                }
                Text(stringResource(R.string.import_issue_line, issue.line, stringResource(reason)), color = Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (preview.parsed.issues.size > 8) Text(stringResource(R.string.import_more_issues, preview.parsed.issues.size - 8), color = Muted)
            DialogActions(R.string.cancel, vm::cancelImportPreview, R.string.import_compatible, enabled, vm::importSubscription)
            return@SceneDialog
        }
        Text(stringResource(R.string.import_hint), color = Muted, style = MaterialTheme.typography.bodyMedium)
        if (state.importHost != null) {
            Text(if (state.importLocal) stringResource(R.string.import_local_source)
                else stringResource(R.string.import_source, state.importHost), color = Ink)
            LinkButton(R.string.enter_another_link, enabled) { vm.setImportUrl("") }
        } else OutlinedTextField(state.importUrl, vm::setImportUrl, enabled = enabled,
            label = { Text(stringResource(R.string.subscription_link)) }, maxLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedLabelColor = Accent, unfocusedLabelColor = Muted, focusedBorderColor = Accent, cursorColor = Accent),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldVisibility)
                .onFocusChanged { fieldFocused = it.isFocused })
        FlowRow {
            LinkButton(R.string.paste, enabled) {
                keyboard?.hide()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = try { clipboard.primaryClip } catch (_: SecurityException) { null }
                if (clip != null && clip.itemCount > 1) vm.importProblem(R.string.clipboard_many)
                else {
                    val item = clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)
                    val text = item?.text?.toString() ?: item?.uri?.toString()
                    if (text.isNullOrBlank()) vm.importProblem(R.string.clipboard_empty)
                    else vm.offerImport(text)
                }
            }
            LinkButton(R.string.qr_code, enabled) { keyboard?.hide(); qrOptions = !qrOptions }
        }
        if (qrOptions) FlowRow {
            LinkButton(R.string.qr_camera, enabled) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    scan.launch(Intent(context, QrScanActivity::class.java))
                else permission.launch(Manifest.permission.CAMERA)
            }
            LinkButton(R.string.qr_image, enabled) { picture.launch(arrayOf("image/*")) }
        }
        if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        DialogActions(R.string.cancel, vm::closeImport, if (replacing) R.string.replace else R.string.add,
            enabled && state.importUrl.isNotBlank(), ::submit)
    }
}
private fun ConnectionPhase.label(): Int = when (this) {
    ConnectionPhase.DISCONNECTED -> R.string.status_disconnected
    ConnectionPhase.PERMISSION -> R.string.status_permission
    ConnectionPhase.CONNECTING -> R.string.status_connecting
    ConnectionPhase.CHECKING -> R.string.status_checking
    ConnectionPhase.CONNECTED -> R.string.status_connected
    ConnectionPhase.DISCONNECTING -> R.string.status_disconnecting
    ConnectionPhase.ERROR -> R.string.status_error
}

internal fun SubscriptionError.message(): Int = when (this) {
    SubscriptionError.URL -> R.string.subscription_error_url
    SubscriptionError.SPECIALIZED -> R.string.subscription_error_specialized
    SubscriptionError.FORMAT -> R.string.subscription_error_format
    SubscriptionError.PARTIAL -> R.string.subscription_error_partial
    SubscriptionError.UNAVAILABLE -> R.string.subscription_error_unavailable
    SubscriptionError.NETWORK -> R.string.subscription_error_network
    SubscriptionError.TLS -> R.string.subscription_error_tls
    SubscriptionError.SERVER -> R.string.subscription_error_server
    SubscriptionError.STORAGE -> R.string.subscription_error_storage
}
