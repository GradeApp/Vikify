package vikify.vpn.diagnostics

/** Deliberately contains no free-form text, endpoint identity or configuration. */
enum class DiagnosticStage { LOCAL_FORMAT, SUBSCRIPTION, DNS, TCP, PROXY_HTTPS, ANDROID_VPN }
enum class DiagnosticStatus { PASS, WARNING, FAIL, NOT_TESTED, CANCELLED }
enum class DiagnosticRoute { NONE, DIRECT, LOCAL_SOCKS, ANDROID_SYSTEM }
enum class DiagnosticProtocol { UNKNOWN, REALITY, XHTTP_TLS, WEBSOCKET_TLS }
enum class DiagnosticCode {
    LOCAL_VALID, NO_PROFILE, LOCAL_INVALID, LOCAL_ONLY,
    DIRECT_NOT_ALLOWED, OTHER_VPN_ACTIVE, OFFLINE, NETWORK_CHANGED,
    SUBSCRIPTION_OK, SUBSCRIPTION_HTTP_ERROR, SUBSCRIPTION_REDIRECT_REJECTED,
    SUBSCRIPTION_TOO_LARGE, SUBSCRIPTION_FORMAT_ERROR, SUBSCRIPTION_PARTIAL,
    DNS_OK, DNS_FAILED, TCP_OK, TCP_FAILED, NETWORK_IO_FAILED, TLS_FAILED, TIMEOUT,
    PROXY_HTTPS_OK, PROXY_HTTPS_FAILED, CONNECT_REQUIRED, PROFILE_MISMATCH,
    TUN_NOT_VERIFIED, CANCELLED, INTERNAL_ERROR
}
enum class DiagnosticAction {
    NONE, CHECK_INTERNET, CHECK_SUBSCRIPTION, ASK_PROVIDER, CONNECT_SELECTED,
    RETRY, RETRY_LATER, CHECK_DEVICE_TIME, ALLOW_DIRECT, STOP_OTHER_VPN, SELECT_PROFILE
}

data class DiagnosticEvent(
    val stage: DiagnosticStage,
    val status: DiagnosticStatus,
    val code: DiagnosticCode,
    val route: DiagnosticRoute = DiagnosticRoute.NONE,
    val action: DiagnosticAction = DiagnosticAction.NONE,
    val elapsedMs: Long = 0,
    val httpStatus: Int? = null,
    val acceptedCount: Int? = null,
    val skippedCount: Int? = null
)

data class DiagnosticReportData(
    val schemaVersion: Int = 1,
    val appVersionCode: Int,
    val androidApi: Int,
    val protocol: DiagnosticProtocol = DiagnosticProtocol.UNKNOWN,
    val events: List<DiagnosticEvent> = emptyList(),
    val completed: Boolean = false
)
