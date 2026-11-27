package vikify.vpn.probe

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in black-box audit of the installed release; never reads release-private data.
 * Start on the English home screen with the requested profile already selected.
 * Modes: local, direct, connected, fault, cancel, layout. No mode imports a subscription,
 * changes a profile, connects, or disconnects the VPN. Cancel mode makes at most
 * three immediate Stop attempts against the existing source. It passes only if
 * an actual Stop action produces an incomplete CANCELLED report; fast completion
 * is reported explicitly, never counted as successful cancellation.
 *
 * Optional probe-private diagnostics-canaries.json: {"canaries":["synthetic-secret"]}.
 * diagnosticsNoShare=yes explicitly omits chooser/export and records shareTested=false.
 * Only explicit Run checks performs diagnostics. Shares target our local QA receiver.
 * Report bodies, canaries, original profile names and network addresses are never logged.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsAuditTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ui = ins.uiAutomation
    private val context = ins.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val expectedProfile get() = args.getString("diagnosticsProfile") ?: "Alternative · XHTTP"
    private val expectedProtocol get() = args.getString("diagnosticsProtocol") ?: "XHTTP_TLS"
    private val canaries by lazy {
        val file = File(context.filesDir, "diagnostics-canaries.json")
        if (!file.isFile) emptyList() else {
            check(file.length() <= 65_536) { "Canary fixture too large" }
            val list = JSONObject(file.readText()).getJSONArray("canaries")
            check(list.length() <= 128)
            (0 until list.length()).map { list.getString(it) }.onEach {
                check(it.length in 6..4096) { "Use a bounded synthetic canary" }
            }
        }
    }
    private val evidence = JSONObject()

    @Test fun auditDiagnosticsOnInstalledRelease() {
        val mode = args.getString("diagnosticsAudit")
        assumeTrue(mode in setOf("local", "direct", "connected", "fault", "cancel", "layout"))
        val selectedMode = requireNotNull(mode)
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        await { "Choose" in texts() && expectedProfile in texts() }
        val wasConnected = "VPN connected" in texts()
        assertEquals("Use connected mode only with the selected VPN already connected",
            selectedMode == "connected", wasConnected)
        val initialVpn = defaultNetworkIsVpn()
        assertEquals("Another VPN must not be active during this audit", wasConnected, initialVpn)
        val initialCount = texts().filter { it.startsWith("Subscription · ") || it.startsWith("Profiles · ") }.toSet()
        assertEquals("Exactly one saved source should be visible", 1, initialCount.size)
        evidence.put("mode", selectedMode).put("canaryCount", canaries.size).put("shareTested", false)

        val originalSettings = if (selectedMode == "layout") listOf(
            "font_scale" to setting("font_scale"),
            "accelerometer_rotation" to setting("accelerometer_rotation"),
            "user_rotation" to setting("user_rotation")
        ) else emptyList()
        try {
            if (selectedMode == "layout") {
                for (large in listOf(false, true)) {
                    shell("settings put system font_scale ${if (large) "2.0" else "1.0"}")
                    assertTrue(ui.setRotation(if (large) android.app.UiAutomation.ROTATION_FREEZE_90
                        else android.app.UiAutomation.ROTATION_FREEZE_0))
                    await { "Choose" in texts() }
                    auditOneRun("local", share = false)
                    closeToHome()
                    assertPreserved(initialCount, initialVpn)
                    mark(if (large) "Landscape 200%: passed" else "Portrait: passed")
                }
                evidence.put("largeFontAndRotation", true)
            } else {
                auditOneRun(selectedMode, share = args.getString("diagnosticsNoShare") != "yes")
                closeToHome()
                assertPreserved(initialCount, initialVpn)
            }
            evidence.put("passed", true).put("selectionPreserved", true)
                .put("profileCountPreserved", true).put("vpnStatePreserved", true)
            mark("Diagnostics audit passed")
        } finally {
            closeToHomeBestEffort()
            if (originalSettings.isNotEmpty()) {
                ui.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
                for ((key, value) in originalSettings) restoreSetting(key, value)
            }
            File(context.filesDir, "diagnostics-audit-$selectedMode.json").writeText(evidence.toString(2))
        }
    }

    private fun auditOneRun(mode: String, share: Boolean) {
        tap("Diagnostics")
        val direct = reveal("Allow direct checks", "diagnostics_direct")
        assertFalse("Direct consent must be off when opening diagnostics", checkable(direct)?.isChecked == true)
        assertNull("Sharing is unavailable before report preview", find("Share report", "diagnostics_share"))
        if (mode == "direct" || mode == "fault" || mode == "cancel") {
            tap("Allow direct checks", "diagnostics_direct")
            assertTrue("Explicit direct consent was selected",
                checkable(reveal("Allow direct checks", "diagnostics_direct"))?.isChecked == true)
        }
        val jsonText = if (mode == "cancel") cancelWithBoundedRetries() else {
            val runEvidence = JSONObject().put("mode", mode)
                .put("semanticClickAccepted", false)
                .put("reportPresentBeforeRun", find("View report", "diagnostics_report") != null)
            val runs = evidence.optJSONArray("runActions") ?: JSONArray().also { evidence.put("runActions", it) }
            runs.put(runEvidence)
            // The Run control may have just scrolled into view at 200% font.
            // A semantic action avoids tapping geometry captured mid-scroll.
            tapSemantic("Run checks", "diagnostics_run")
            runEvidence.put("semanticClickAccepted", true)
                .put("stopPresentAfterRun", find("Stop", "diagnostics_stop") != null)
                .put("reportPresentAfterRun", find("View report", "diagnostics_report") != null)
            await(100_000) { find("View report", "diagnostics_report") != null }
            runEvidence.put("reportObserved", true)
            assertNull("Sharing requires the preview step", find("Share report", "diagnostics_share"))
            tap("View report", "diagnostics_report")
            tap("JSON")
            preview(json = true)
        }
        val report = validateReport(jsonText)
        checkRunOutcome(mode, report)
        val events = report.getJSONArray("events")
        evidence.put("reportCodes", JSONArray((0 until events.length()).map { events.getJSONObject(it).getString("code") }))
        evidence.put("productionTunNotClaimed", eventsAsList(report).any {
            it.getString("stage") == "ANDROID_VPN" && it.getString("code") == "TUN_NOT_VERIFIED" &&
                it.getString("status") == "NOT_TESTED"
        } || mode == "cancel")
        if (share) {
            captureShare("application/json", jsonText)
            tapSemantic("Text")
            val text = preview(json = false)
            validateCanaries(text)
            assertTrue("Plain-text preview is a structured report", text.startsWith("Vikify diagnostic report\n"))
            captureShare("text/plain", text)
            evidence.put("textAndJsonShareBytesMatchPreview", true).put("shareTested", true)
        }
        if (mode == "connected") {
            // Separate UID proves Android TUN traffic in QA. The product report must
            // still say TUN_NOT_VERIFIED: its own UID bypasses its VpnService.
            TunnelTrafficTest().defaultNetworkCarriesDnsAndHttpsThroughVpn()
            TunnelTrafficTest().defaultHttpsStackDownloadsARealPage()
            evidence.put("independentQaTunDnsAndHttpsChecks", 2)
        }
    }

    private fun cancelWithBoundedRetries(): String {
        var completedBeforeStop = 0
        var stopRequests = 0
        for (attempt in 1..3) {
            evidence.put("cancellationAttempts", attempt)
            val run = reveal("Run checks", "diagnostics_run")
            assertTrue("Start cancellation attempt through the visible Run control", clickAction(run))
            // Accessibility click avoids the shell-input round trip and deliberate
            // debounce used for ordinary navigation. Never use production delays.
            val began = SystemClock.elapsedRealtime()
            var stopSent = false
            while (SystemClock.elapsedRealtime() - began < 5_000) {
                val stop = find("Stop", "diagnostics_stop")
                if (visible(stop) && clickAction(requireNotNull(stop))) {
                    stopSent = true
                    stopRequests++
                    break
                }
                if (stop != null && !visible(stop)) {
                    nodes().firstOrNull { it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                if (SystemClock.elapsedRealtime() - began >= 300 &&
                    find("View report", "diagnostics_report") != null &&
                    find("Stop", "diagnostics_stop") == null) break
                SystemClock.sleep(20)
            }
            await(if (stopSent) 7_000 else 100_000) { find("View report", "diagnostics_report") != null }
            assertNull("Sharing requires the preview step", find("Share report", "diagnostics_share"))
            tap("View report", "diagnostics_report")
            tap("JSON")
            val text = preview(json = true)
            val report = validateReport(text)
            val cancelled = !report.getBoolean("completed") && eventsAsList(report).any {
                it.getString("code") == "CANCELLED" && it.getString("status") == "CANCELLED"
            }
            evidence.put("stopActionCount", stopRequests)
            if (stopSent && cancelled) {
                evidence.put("completedBeforeStopCount", completedBeforeStop)
                return text
            }
            if (report.getBoolean("completed")) completedBeforeStop++
            evidence.put("completedBeforeStopCount", completedBeforeStop)
            mark("Cancellation attempt $attempt completed without an observed Stop cancellation")
            if (attempt < 3) tap("Back to results", "diagnostics_back_results")
        }
        evidence.put("cancellationObserved", false)
        error("Cancellation was not observed in three bounded attempts; the live checks may finish before Stop. This is not a cancellation pass.")
    }

    private fun checkRunOutcome(mode: String, report: JSONObject) {
        val rows = eventsAsList(report)
        fun has(code: String) = rows.any { it.getString("code") == code }
        if (mode == "cancel") {
            assertFalse("Cancelled report must not claim completion", report.getBoolean("completed"))
            assertTrue("Cancellation must be observed, not inferred from closing the dialog", has("CANCELLED"))
            evidence.put("cancellationObserved", true)
            return
        }
        assertTrue("Checks completed", report.getBoolean("completed"))
        assertTrue("The saved selected profile is locally valid", has("LOCAL_VALID"))
        assertTrue("Product must disclose unverified Android TUN", rows.any {
            it.getString("stage") == "ANDROID_VPN" && it.getString("code") == "TUN_NOT_VERIFIED" &&
                it.getString("status") == "NOT_TESTED"
        })
        if (mode == "local") {
            assertTrue("No report event may claim a direct request without consent",
                rows.none { it.getString("route") == "DIRECT" })
            assertTrue("At least one direct check was explicitly skipped", has("DIRECT_NOT_ALLOWED"))
            assertTrue("Disconnected proxy is not reported as tested", has("CONNECT_REQUIRED"))
            assertTrue("No network check may claim success in a disconnected local run",
                rows.filter { it.getString("stage") != "LOCAL_FORMAT" }.none { it.getString("status") == "PASS" && it.getString("code") != "LOCAL_ONLY" })
        }
        if (mode == "direct") {
            for (code in listOf("SUBSCRIPTION_OK", "DNS_OK", "TCP_OK")) {
                assertTrue("The live direct fixture must complete the expected stage: $code", has(code))
            }
            assertTrue("No VPN is started by direct diagnostics", has("CONNECT_REQUIRED"))
        }
        if (mode == "connected") {
            assertTrue("Current selected SOCKS proxy carried HTTPS", has("PROXY_HTTPS_OK"))
            assertTrue("No silent direct connection is allowed", rows.none { it.getString("route") == "DIRECT" })
        }
        if (mode == "fault") {
            val expected = requireNotNull(args.getString("diagnosticsExpectedCode")) {
                "A controlled fault audit needs diagnosticsExpectedCode"
            }
            require(expected in requireNotNull(ENUM_FIELDS["code"]))
            val event = rows.firstOrNull { it.getString("code") == expected }
            assertNotNull("The controlled fault must produce its documented code", event)
            val observed = requireNotNull(event)
            assertTrue("A fault must not be reported as success",
                observed.getString("status") in setOf("FAIL", "WARNING", "NOT_TESTED"))
            args.getString("diagnosticsExpectedHttpStatus")?.toInt()?.let { expectedStatus ->
                assertEquals("HTTP status is preserved without its body", expectedStatus, observed.getInt("httpStatus"))
            }
            fun stageHas(stage: String, status: String, code: String) = rows.any {
                it.getString("stage") == stage && it.getString("status") == status && it.getString("code") == code
            }
            when (expected) {
                "DNS_FAILED" -> {
                    assertTrue("DNS fixture must fail at the DNS stage",
                        stageHas("DNS", "FAIL", "DNS_FAILED"))
                    assertTrue("TCP must remain untested when DNS supplies no addresses",
                        stageHas("TCP", "NOT_TESTED", "DNS_FAILED"))
                    assertFalse("A DNS failure must not be mislabeled as a failed TCP attempt", has("TCP_FAILED"))
                }
                "OFFLINE" -> {
                    assertFalse("Offline state must not invent a DNS failure", has("DNS_FAILED"))
                    assertFalse("Offline state must not invent a TCP failure", has("TCP_FAILED"))
                    assertTrue("TCP must remain untested with the original offline cause",
                        stageHas("TCP", "NOT_TESTED", "OFFLINE"))
                }
                "TCP_FAILED" -> {
                    assertTrue("TCP failure fixture requires successful DNS",
                        stageHas("DNS", "PASS", "DNS_OK"))
                    assertTrue("TCP failure must be attached to an actual TCP stage",
                        stageHas("TCP", "FAIL", "TCP_FAILED"))
                }
            }
            evidence.put("controlledFaultVerified", expected)
        }
    }

    private fun validateReport(text: String): JSONObject = try {
        validateCanaries(text)
        val report = JSONObject(text)
        assertTrue("Only approved report fields are exported", ROOT_KEYS == report.keys().asSequence().toSet())
        assertEquals(1, report.getInt("schemaVersion"))
        assertTrue(report.getInt("appVersionCode") >= 25)
        assertEquals(Build.VERSION.SDK_INT, report.getInt("androidApi"))
        assertTrue("Only the expected coarse protocol is included", expectedProtocol == report.getString("protocol"))
        assertTrue(report.get("completed") is Boolean)
        val rows = eventsAsList(report)
        assertTrue(rows.size in 1..16)
        for (event in rows) {
            assertTrue("Only approved event fields are exported", EVENT_KEYS == event.keys().asSequence().toSet())
            for ((key, allowed) in ENUM_FIELDS) {
                assertTrue("Unknown enum field value: $key", event.getString(key) in allowed)
            }
            val elapsed = event.getLong("elapsedMs")
            assertTrue("Timing is bounded and rounded", elapsed in 0L..180_000L && elapsed % 100L == 0L)
            if (!event.isNull("httpStatus")) assertTrue(event.getInt("httpStatus") in 100..599)
            if (!event.isNull("acceptedCount")) assertTrue(event.getInt("acceptedCount") in 0..100)
            if (!event.isNull("skippedCount")) assertTrue(event.getInt("skippedCount") in 0..500)
        }
        report
    } catch (_: JSONException) {
        // JSON exception messages may quote the malformed input. Do not log them.
        error("Diagnostic report does not conform to the JSON schema")
    }

    private fun validateCanaries(text: String) {
        assertTrue("Report is bounded", text.toByteArray(Charsets.UTF_8).size <= 65_536)
        assertFalse("Report must not contain URLs", Regex("(?i)(?:https?|vless)://").containsMatchIn(text))
        assertFalse("Report must not contain UUIDs", Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(text))
        assertFalse("Report must not contain IP addresses", Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b").containsMatchIn(text))
        for (canary in canaries) {
            val bytes = canary.toByteArray(Charsets.UTF_8)
            val variants = setOf(canary, JSONObject.quote(canary).removeSurrounding("\""),
                URLEncoder.encode(canary, "UTF-8"),
                Base64.encodeToString(bytes, Base64.NO_WRAP),
                Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING))
            assertFalse("A planted secret reached the report", variants.any { text.contains(it, ignoreCase = true) })
        }
    }

    private fun captureShare(mime: String, expected: String) {
        val metadata = File(context.filesDir, "captured-diagnostic-meta.json")
        val receiverStart = File(context.filesDir, "captured-diagnostic-start.json")
        metadata.delete()
        receiverStart.delete()
        val shareEvidence = JSONObject().put("mime", mime).put("probePid", Process.myPid())
        val shares = evidence.optJSONArray("shareAttempts") ?: JSONArray().also { evidence.put("shareAttempts", it) }
        shares.put(shareEvidence)
        tapSemantic("Share report", "diagnostics_share")
        await(12_000) {
            shell("dumpsys activity activities").lineSequence().any {
                (it.contains("ResumedActivity") || it.contains("topResumedActivity")) &&
                    (it.contains("ChooserActivity") || it.contains("ResolverActivity"))
            }
        }
        evidence.put("systemChooserObserved", true)
        shareEvidence.put("beforeSelection", resumedComponent())
        chooseLocalReceiver(shareEvidence)
        shareEvidence.put("afterSelection", resumedComponent())
        // Samsung/Android can publish the confirmation after the target click.
        // Never set a persistent default and never select an unrelated recipient.
        try {
            await(15_000) {
                if (metadata.isFile) true else {
                    nodes(chooserRoot()).firstOrNull {
                        normalize(it.text?.toString()) == "Just once" && visible(it)
                    }?.let(::activate)
                    metadata.isFile
                }
            }
        } finally {
            shareEvidence.put("afterCaptureWait", resumedComponent())
                .put("receiverStarted", receiverStart.isFile).put("metadataWritten", metadata.isFile)
            if (receiverStart.isFile) {
                val started = JSONObject(receiverStart.readText())
                shareEvidence.put("receiverPid", started.optInt("pid", -1))
                shareEvidence.put("separateReceiverProcess", started.optInt("pid", -1) != Process.myPid())
            }
        }
        val captured = JSONObject(metadata.readText())
        assertTrue("The local QA share receiver accepted the export", captured.getBoolean("captured"))
        assertEquals(mime, captured.getString("mime"))
        val name = if (mime == "application/json") "captured-diagnostic.json" else "captured-diagnostic.txt"
        assertEquals(name, captured.getString("file"))
        val actual = File(context.filesDir, name).readText(Charsets.UTF_8)
        validateCanaries(actual)
        assertTrue("Exported bytes must match the preview", actual == expected)
        if (mime == "application/json") validateReport(actual)
        // A background accessibility window can expose Share before the receiver
        // has finished. Wait for the actual foreground app, not root()'s fallback.
        awaitAppResumed()
        await { find("Share report", "diagnostics_share") != null }
        shareEvidence.put("returnedToApp", true)
    }

    private fun chooseLocalReceiver(shareEvidence: JSONObject) {
        for (attempt in 0 until 12) {
            val root = chooserRoot()
            val candidates = nodes(root)
            val target = candidates.firstOrNull { node ->
                visible(node) && (normalize(node.text?.toString()) == "Vikify report test" ||
                    normalize(node.contentDescription?.toString()) == "Vikify report test")
            }
            if (target != null) {
                // Samsung clips the secondary activity label to a few pixels at
                // the bottom of the sheet; activate its verified app tile.
                val ancestors = generateSequence(target) { it.parent }.take(12).toList()
                val tile = ancestors.firstOrNull {
                    it.viewIdResourceName == "android:id/sem_chooser_grid_item_view" && visible(it)
                } ?: ancestors.firstOrNull { it.isClickable && visible(it) }
                val selected = requireNotNull(tile) { "The exact QA receiver label has no visible app tile" }
                val refreshed = selected.refresh()
                shareEvidence.put("targetAncestors", JSONArray(ancestors.map(::nodeGeometry)))
                    .put("selectedTile", nodeGeometry(selected)).put("chooserPageAttempts", attempt + 1)
                    .put("selectedTileRefreshed", refreshed)
                if (!refreshed) {
                    SystemClock.sleep(150)
                    continue
                }
                // Samsung can expose pre-animation geometry one full screen
                // below the display while claiming isVisibleToUser=true. The
                // verified tile's semantic click does not use those coordinates.
                val clicked = selected.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                shareEvidence.put("semanticTileClickAccepted", clicked)
                assertTrue("The exact QA share tile must accept its semantic click", clicked)
                return
            }
            // Samsung exposes the ranked-app horizontal pager as scrollable,
            // but ACTION_SCROLL_FORWARD may not move its page. Swipe only this
            // observed pager. Never swipe the sheet or the app behind it.
            val pager = candidates.firstOrNull {
                it.viewIdResourceName == "android:id/sem_chooser_recycler_ranked_app" && visible(it)
            }
            if (pager != null) {
                pager.refresh()
                val bounds = Rect().also(pager::getBoundsInScreen)
                val margin = (bounds.width() / 10).coerceAtLeast(1)
                val left = bounds.left + margin
                val right = bounds.right - margin
                val display = displayBounds()
                if (right > left && display.contains(left, bounds.centerY()) &&
                    display.contains(right, bounds.centerY())) {
                    val from = if (attempt < 6) right else left
                    val to = if (attempt < 6) left else right
                    shell("input swipe $from ${bounds.centerY()} $to ${bounds.centerY()} 350")
                } else {
                    shareEvidence.put("offscreenPagerGesturesRejected",
                        shareEvidence.optInt("offscreenPagerGesturesRejected") + 1)
                }
            } else {
                candidates.firstOrNull {
                    it.isScrollable && visible(it) &&
                        it.viewIdResourceName?.endsWith("/resolver_list") == true
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(200)
        }
        error("The local Vikify QA receiver was not visible in the system share chooser; no other destination was selected")
    }

    private fun chooserRoot(): AccessibilityNodeInfo? {
        // Public cache invalidation was added in API 34. Refresh is available on
        // older Android versions too, including the Galaxy under test.
        if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
        fun isChooser(node: AccessibilityNodeInfo) = node.packageName?.toString() in
            setOf("android", "com.android.intentresolver")
        val root = ui.rootInActiveWindow?.takeIf(::isChooser)
            ?: ui.windows.sortedByDescending { it.layer }.mapNotNull { it.root }.firstOrNull(::isChooser)
        return root?.takeIf { it.refresh() }
    }
    private fun normalize(value: String?) = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun nodeGeometry(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        // No accessibility text/content description: the chooser can contain contacts.
        return JSONObject().put("id", node.viewIdResourceName ?: "NONE")
            .put("class", node.className?.toString() ?: "NONE")
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("clickable", node.isClickable).put("enabled", node.isEnabled)
    }

    private fun resumedComponent(): String {
        val component = Regex("\\b[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")
        return shell("dumpsys activity activities").lineSequence().filter {
            it.contains("ResumedActivity") || it.contains("topResumedActivity")
        }.mapNotNull { component.find(it)?.value }.firstOrNull() ?: "NONE"
    }

    private fun preview(json: Boolean): String {
        fun matches(value: String) = if (json) value.trimStart().startsWith("{\n  \"schemaVersion\"")
            else value.startsWith("Vikify diagnostic report")
        fun current(): String? = findTag("diagnostics_preview")?.text?.toString()?.takeIf(::matches)
            ?: nodes().mapNotNull { it.text?.toString() }.firstOrNull(::matches)
        await { current() != null }
        return requireNotNull(current())
    }

    private fun assertPreserved(initialCount: Set<String>, vpn: Boolean) {
        await { "Choose" in texts() && expectedProfile in texts() }
        assertEquals("Saved source/profile count preserved", initialCount,
            texts().filter { it.startsWith("Subscription · ") || it.startsWith("Profiles · ") }.toSet())
        assertEquals("Default VPN state preserved", vpn, defaultNetworkIsVpn())
        assertEquals("VPN connection label preserved", vpn, "VPN connected" in texts())
    }

    private fun closeToHome() {
        if (find("Stop", "diagnostics_stop") != null) tap("Stop", "diagnostics_stop")
        if (find("Close", "diagnostics_close") != null) tap("Close", "diagnostics_close")
        await { "Choose" in texts() }
    }
    private fun closeToHomeBestEffort() { try { closeToHome() } catch (_: Exception) { /* root runner inspects failures */ } }
    private fun defaultNetworkIsVpn(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    private fun eventsAsList(report: JSONObject): List<JSONObject> {
        val array = report.getJSONArray("events")
        return (0 until array.length()).map(array::getJSONObject)
    }
    private fun checkable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        return nodes(node).firstOrNull { it.isCheckable } ?: node.parent?.takeIf { it.isCheckable }
    }
    private fun root(): AccessibilityNodeInfo? = ui.rootInActiveWindow?.takeIf { it.packageName?.toString() == APP }
        ?: ui.windows.sortedByDescending { it.layer }.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == APP }
    private fun nodes(node: AccessibilityNodeInfo? = root()): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun texts() = nodes().mapNotNull { it.text?.toString() }.toSet()
    private fun findTag(tag: String) = nodes().firstOrNull { it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith("/$tag") == true }
    private fun find(label: String, tag: String? = null, appOnly: Boolean = true): AccessibilityNodeInfo? {
        if (appOnly && tag != null) findTag(tag)?.let { return it }
        return nodes(if (appOnly) root() else ui.rootInActiveWindow).firstOrNull { it.text?.toString() == label }
    }
    private fun visible(node: AccessibilityNodeInfo?) = node != null && node.isVisibleToUser && !Rect().also(node::getBoundsInScreen).isEmpty
    private fun tap(label: String, tag: String? = null, appOnly: Boolean = true) {
        activate(reveal(label, tag, appOnly))
    }
    private fun awaitAppResumed() {
        await(15_000) {
            resumedComponent().startsWith("$APP/") &&
                ui.rootInActiveWindow?.packageName?.toString() == APP
        }
    }
    private fun tapSemantic(label: String, tag: String? = null) {
        awaitAppResumed()
        val target = reveal(label, tag)
        assertTrue("The requested diagnostic control must still exist", target.refresh())
        assertTrue("The requested diagnostic control must accept its semantic click", clickAction(target))
    }
    private fun reveal(label: String, tag: String? = null, appOnly: Boolean = true): AccessibilityNodeInfo {
        // Results and previews can be taller than the screen at 200% font.
        // Search both directions; never guess coordinates for a hidden control.
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            repeat(18) {
                val target = find(label, tag, appOnly)
                if (visible(target)) return requireNotNull(target)
                nodes(if (appOnly) root() else ui.rootInActiveWindow).firstOrNull { it.isScrollable }
                    ?.performAction(direction)
                SystemClock.sleep(200)
            }
        }
        error("Required diagnostic control is not visible")
    }
    private fun clickAction(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        repeat(4) {
            target?.let { current ->
                if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                target = current.parent
            }
        }
        return false
    }
    private fun activate(node: AccessibilityNodeInfo) {
        node.refresh()
        val rect = Rect().also(node::getBoundsInScreen)
        assertFalse("Target must be visible", rect.isEmpty)
        assertTrue("Physical tap must stay inside the actual display",
            displayBounds().contains(rect.centerX(), rect.centerY()))
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        SystemClock.sleep(180)
    }
    @Suppress("DEPRECATION")
    private fun displayBounds(): Rect {
        val size = Point()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
        check(size.x > 0 && size.y > 0) { "Cannot determine the actual display bounds" }
        return Rect(0, 0, size.x, size.y)
    }
    private fun await(timeout: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do {
            if (condition()) return
            SystemClock.sleep(120)
        } while (SystemClock.elapsedRealtime() < end)
        error("Diagnostic audit did not reach the expected state")
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun setting(key: String) = shell("settings get system $key").trim()
    private fun restoreSetting(key: String, value: String) {
        check(value == "null" || value.matches(Regex("[0-9.]+")))
        shell(if (value == "null") "settings delete system $key" else "settings put system $key $value")
    }
    private fun mark(message: String) = ins.sendStatus(2, Bundle().apply { putString("diagnosticsAudit", message) })

    private companion object {
        const val APP = "vikify.vpn"
        val ROOT_KEYS = setOf("schemaVersion", "appVersionCode", "androidApi", "protocol", "completed", "events")
        val EVENT_KEYS = setOf("stage", "status", "code", "route", "action", "elapsedMs", "httpStatus", "acceptedCount", "skippedCount")
        val ENUM_FIELDS = mapOf(
            "stage" to setOf("LOCAL_FORMAT", "SUBSCRIPTION", "DNS", "TCP", "PROXY_HTTPS", "ANDROID_VPN"),
            "status" to setOf("PASS", "WARNING", "FAIL", "NOT_TESTED", "CANCELLED"),
            "route" to setOf("NONE", "DIRECT", "LOCAL_SOCKS", "ANDROID_SYSTEM"),
            "action" to setOf("NONE", "CHECK_INTERNET", "CHECK_SUBSCRIPTION", "ASK_PROVIDER", "CONNECT_SELECTED", "RETRY", "RETRY_LATER", "CHECK_DEVICE_TIME", "ALLOW_DIRECT", "STOP_OTHER_VPN", "SELECT_PROFILE"),
            "code" to setOf("LOCAL_VALID", "NO_PROFILE", "LOCAL_INVALID", "LOCAL_ONLY", "DIRECT_NOT_ALLOWED", "OTHER_VPN_ACTIVE", "OFFLINE", "NETWORK_CHANGED", "SUBSCRIPTION_OK", "SUBSCRIPTION_HTTP_ERROR", "SUBSCRIPTION_REDIRECT_REJECTED", "SUBSCRIPTION_TOO_LARGE", "SUBSCRIPTION_FORMAT_ERROR", "SUBSCRIPTION_PARTIAL", "DNS_OK", "DNS_FAILED", "TCP_OK", "TCP_FAILED", "NETWORK_IO_FAILED", "TLS_FAILED", "TIMEOUT", "PROXY_HTTPS_OK", "PROXY_HTTPS_FAILED", "CONNECT_REQUIRED", "PROFILE_MISMATCH", "TUN_NOT_VERIFIED", "CANCELLED", "INTERNAL_ERROR")
        )
    }
}
