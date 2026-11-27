package vikify.vpn.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vikify.vpn.R
import vikify.vpn.diagnostics.*

/** Contains only the allowlisted result model. Raw configuration never enters this UI. */
data class DiagnosticUiState(
    val busy: Boolean = false,
    val events: List<DiagnosticEvent> = emptyList(),
    val report: DiagnosticReportData? = null,
    val canConnect: Boolean = false,
    val interrupted: Boolean = false,
    val shareError: Boolean = false
)

enum class DiagnosticExportFormat { TEXT, JSON }

private val DiagnosticInk = Color(0xFFFFF6E5)
private val DiagnosticMuted = Color(0xFFE0ECE8)
private val DiagnosticAccent = Color(0xFFFFDB97)

/** Content for the existing, inset-aware SceneDialog. The parent provides scrolling. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsContent(
    ui: DiagnosticUiState,
    onRun: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onShare: (DiagnosticExportFormat) -> Unit,
    onConnect: () -> Unit,
    onContentStart: () -> Unit = {}
) {
    // Direct-network consent belongs to this visible dialog, not a persistent app preference.
    var allowDirect by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(DiagnosticExportFormat.TEXT) }
    LaunchedEffect(ui.busy) { if (ui.busy) preview = false }
    LaunchedEffect(ui.busy, ui.report, preview) { onContentStart() }

    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(stringResource(if (preview) R.string.diagnostics_report_title else R.string.diagnostics_title),
            style = MaterialTheme.typography.titleLarge, color = DiagnosticInk,
            modifier = Modifier.semantics { heading() }.padding(vertical = 8.dp))
        when {
            ui.busy -> DiagnosticButton(R.string.diagnostics_stop, "diagnostics_stop", onClick = onCancel)
            preview && ui.report != null -> DiagnosticButton(R.string.diagnostics_share,
                "diagnostics_share", onClick = { onShare(format) })
            ui.report != null -> DiagnosticButton(R.string.diagnostics_view_report,
                "diagnostics_report", onClick = { preview = true })
        }
        DiagnosticButton(R.string.diagnostics_close, "diagnostics_close", onClick = onClose)
    }

    if (preview && ui.report != null && !ui.busy) {
        Text(stringResource(R.string.diagnostics_report_explanation), color = DiagnosticMuted,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = format == DiagnosticExportFormat.TEXT,
                onClick = { format = DiagnosticExportFormat.TEXT },
                label = { Text(stringResource(R.string.diagnostics_text)) })
            FilterChip(selected = format == DiagnosticExportFormat.JSON,
                onClick = { format = DiagnosticExportFormat.JSON },
                label = { Text(stringResource(R.string.diagnostics_json)) })
        }
        val report = ui.report
        val content = remember(report, format) {
            if (format == DiagnosticExportFormat.TEXT) DiagnosticReport.toText(report)
            else DiagnosticReport.toJson(report)
        }
        Text(content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            color = DiagnosticInk, modifier = Modifier.fillMaxWidth().testTag("diagnostics_preview")
                .background(Color.White.copy(alpha = .04f), RoundedCornerShape(10.dp)).padding(10.dp))
        if (ui.shareError) Text(stringResource(R.string.diagnostics_share_error), color = Color(0xFFFFAAA5),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DiagnosticButton(R.string.diagnostics_back_results, "diagnostics_back_results", onClick = { preview = false })
        }
        return
    }

    if (ui.interrupted) Text(stringResource(R.string.diagnostics_interrupted), color = DiagnosticAccent,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

    if (ui.events.isEmpty()) {
        Text(stringResource(R.string.diagnostics_intro), color = DiagnosticInk)
        Text(stringResource(R.string.diagnostics_limits), color = DiagnosticMuted,
            style = MaterialTheme.typography.bodySmall)
    } else {
        Text(stringResource(if (ui.busy) R.string.diagnostics_running else R.string.diagnostics_results),
            color = DiagnosticAccent, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("diagnostics_status").semantics { liveRegion = LiveRegionMode.Polite })
        Text(stringResource(R.string.diagnostics_unchanged), color = DiagnosticMuted,
            style = MaterialTheme.typography.bodySmall)
        ui.events.forEach { event -> DiagnosticResult(event) }
    }

    if (ui.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = DiagnosticAccent)
    } else {
        if (ui.canConnect && ui.events.any { it.action == DiagnosticAction.CONNECT_SELECTED }) {
            Text(stringResource(R.string.diagnostics_connect_hint), color = DiagnosticMuted,
                style = MaterialTheme.typography.bodySmall)
            DiagnosticButton(R.string.diagnostics_connect, "diagnostics_connect", onClick = onConnect)
        }
        Row(Modifier.fillMaxWidth().testTag("diagnostics_direct")
            .toggleable(value = allowDirect, role = Role.Checkbox, onValueChange = { allowDirect = it })
            .padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = allowDirect, onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = DiagnosticAccent, checkmarkColor = Color(0xFF213333)),
                modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.diagnostics_allow_direct), color = DiagnosticInk)
                Text(stringResource(R.string.diagnostics_direct_explanation), color = DiagnosticMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.diagnostics_probe_destinations), color = DiagnosticMuted,
            style = MaterialTheme.typography.bodySmall)
        // A local-only run can finish before Compose observes busy=true. Reset
        // scrolling on the action itself, including identical repeated reports.
        Button(onClick = { onContentStart(); onRun(allowDirect) }, modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp).testTag("diagnostics_run"),
            colors = ButtonDefaults.buttonColors(containerColor = DiagnosticAccent, contentColor = Color(0xFF213333))) {
            Text(stringResource(R.string.diagnostics_run))
        }
    }
}

@Composable
private fun DiagnosticButton(label: Int, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(label), color = DiagnosticAccent)
    }
}

@Composable
private fun DiagnosticResult(event: DiagnosticEvent) {
    val color = when (event.status) {
        DiagnosticStatus.PASS -> Color(0xFFA7EBB2)
        DiagnosticStatus.FAIL -> Color(0xFFFFAAA5)
        DiagnosticStatus.WARNING -> DiagnosticAccent
        else -> DiagnosticMuted
    }
    Column(Modifier.fillMaxWidth().testTag("diagnostics_stage_${event.stage.name}")
        .background(Color.White.copy(alpha = .035f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(stageLabel(event.stage)) + " · " + stringResource(statusLabel(event.status)),
            color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text(observation(event), color = DiagnosticInk, style = MaterialTheme.typography.bodyMedium)
        if (event.acceptedCount != null) Text(stringResource(R.string.diagnostics_profile_counts,
            event.acceptedCount, event.skippedCount ?: 0), color = DiagnosticMuted,
            style = MaterialTheme.typography.bodySmall)
        if (event.action != DiagnosticAction.NONE) Text(stringResource(actionLabel(event.action)),
            color = DiagnosticMuted, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.diagnostics_route, stringResource(routeLabel(event.route))),
            color = DiagnosticMuted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun stageLabel(stage: DiagnosticStage): Int = when (stage) {
    DiagnosticStage.LOCAL_FORMAT -> R.string.diagnostics_stage_local
    DiagnosticStage.SUBSCRIPTION -> R.string.diagnostics_stage_subscription
    DiagnosticStage.DNS -> R.string.diagnostics_stage_dns
    DiagnosticStage.TCP -> R.string.diagnostics_stage_tcp
    DiagnosticStage.PROXY_HTTPS -> R.string.diagnostics_stage_proxy
    DiagnosticStage.ANDROID_VPN -> R.string.diagnostics_stage_android
}

private fun statusLabel(status: DiagnosticStatus): Int = when (status) {
    DiagnosticStatus.PASS -> R.string.diagnostics_pass
    DiagnosticStatus.WARNING -> R.string.diagnostics_warning
    DiagnosticStatus.FAIL -> R.string.diagnostics_fail
    DiagnosticStatus.NOT_TESTED -> R.string.diagnostics_not_tested
    DiagnosticStatus.CANCELLED -> R.string.diagnostics_cancelled
}

private fun routeLabel(route: DiagnosticRoute): Int = when (route) {
    DiagnosticRoute.NONE -> R.string.diagnostics_route_none
    DiagnosticRoute.DIRECT -> R.string.diagnostics_route_direct
    DiagnosticRoute.LOCAL_SOCKS -> R.string.diagnostics_route_proxy
    DiagnosticRoute.ANDROID_SYSTEM -> R.string.diagnostics_route_android
}

@Composable
private fun observation(event: DiagnosticEvent): String {
    if (event.code == DiagnosticCode.SUBSCRIPTION_HTTP_ERROR) {
        val label = when (event.httpStatus) {
            401, 403 -> R.string.diagnostics_http_denied
            404, 410 -> R.string.diagnostics_http_missing
            429 -> R.string.diagnostics_http_limited
            else -> R.string.diagnostics_http_error
        }
        return stringResource(label, event.httpStatus ?: 0)
    }
    return stringResource(when (event.code) {
        DiagnosticCode.LOCAL_VALID -> R.string.diagnostics_local_valid
        DiagnosticCode.NO_PROFILE -> R.string.diagnostics_no_profile
        DiagnosticCode.LOCAL_INVALID -> R.string.diagnostics_local_invalid
        DiagnosticCode.LOCAL_ONLY -> R.string.diagnostics_local_only
        DiagnosticCode.DIRECT_NOT_ALLOWED -> R.string.diagnostics_direct_not_allowed
        DiagnosticCode.OTHER_VPN_ACTIVE -> R.string.diagnostics_other_vpn
        DiagnosticCode.OFFLINE -> R.string.diagnostics_offline
        DiagnosticCode.NETWORK_CHANGED -> R.string.diagnostics_network_changed
        DiagnosticCode.SUBSCRIPTION_OK -> R.string.diagnostics_subscription_ok
        DiagnosticCode.SUBSCRIPTION_HTTP_ERROR -> R.string.diagnostics_http_error
        DiagnosticCode.SUBSCRIPTION_REDIRECT_REJECTED -> R.string.diagnostics_redirect
        DiagnosticCode.SUBSCRIPTION_TOO_LARGE -> R.string.diagnostics_too_large
        DiagnosticCode.SUBSCRIPTION_FORMAT_ERROR -> R.string.diagnostics_format_error
        DiagnosticCode.SUBSCRIPTION_PARTIAL -> R.string.diagnostics_partial
        DiagnosticCode.DNS_OK -> R.string.diagnostics_dns_ok
        DiagnosticCode.DNS_FAILED -> R.string.diagnostics_dns_failed
        DiagnosticCode.TCP_OK -> R.string.diagnostics_tcp_ok
        DiagnosticCode.TCP_FAILED -> R.string.diagnostics_tcp_failed
        DiagnosticCode.NETWORK_IO_FAILED -> R.string.diagnostics_network_io_failed
        DiagnosticCode.TLS_FAILED -> R.string.diagnostics_tls_failed
        DiagnosticCode.TIMEOUT -> R.string.diagnostics_timeout
        DiagnosticCode.PROXY_HTTPS_OK -> R.string.diagnostics_proxy_ok
        DiagnosticCode.PROXY_HTTPS_FAILED -> R.string.diagnostics_proxy_failed
        DiagnosticCode.CONNECT_REQUIRED -> R.string.diagnostics_connect_required
        DiagnosticCode.PROFILE_MISMATCH -> R.string.diagnostics_profile_mismatch
        DiagnosticCode.TUN_NOT_VERIFIED -> R.string.diagnostics_tun_not_verified
        DiagnosticCode.CANCELLED -> R.string.diagnostics_stopped
        DiagnosticCode.INTERNAL_ERROR -> R.string.diagnostics_internal_error
    })
}

private fun actionLabel(action: DiagnosticAction): Int = when (action) {
    DiagnosticAction.NONE -> R.string.diagnostics_action_none
    DiagnosticAction.CHECK_INTERNET -> R.string.diagnostics_action_internet
    DiagnosticAction.CHECK_SUBSCRIPTION -> R.string.diagnostics_action_subscription
    DiagnosticAction.ASK_PROVIDER -> R.string.diagnostics_action_provider
    DiagnosticAction.CONNECT_SELECTED -> R.string.diagnostics_action_connect
    DiagnosticAction.RETRY -> R.string.diagnostics_action_retry
    DiagnosticAction.RETRY_LATER -> R.string.diagnostics_action_later
    DiagnosticAction.CHECK_DEVICE_TIME -> R.string.diagnostics_action_time
    DiagnosticAction.ALLOW_DIRECT -> R.string.diagnostics_action_direct
    DiagnosticAction.STOP_OTHER_VPN -> R.string.diagnostics_action_other_vpn
    DiagnosticAction.SELECT_PROFILE -> R.string.diagnostics_action_profile
}
