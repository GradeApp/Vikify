# Data inventory · Vikify VPN 1.7.0 (version code 25)

| Data | Storage and retention | Network use |
|---|---|---|
| Subscription URL including token | EncryptedSharedPreferences, until replacement or removal | HTTPS request to the user-provided subscription server |
| VLESS profiles, UUIDs and transport parameters | One encrypted subscription snapshot, replaced after a successful update | Authentication and connection to the selected VPN endpoint |
| Selected profile and last successful update | Stored with the encrypted snapshot | Used locally |
| Legacy UUID | EncryptedSharedPreferences until access is removed | Authentication using the bundled legacy profiles |
| Generated Xray configuration | Memory only. Passed to the native process through stdin. A leftover file from older versions is removed at application startup. | Loaded by Xray |
| VPN disclosure choice | Version of the accepted disclosure, in private preferences. Withdraw in Help. | None |
| Connection state | Process memory, shared between service and UI | Used locally |
| Connectivity checks | Results held in memory | HTTPS through the selected SOCKS proxy to gstatic.com and, on failure, cp.cloudflare.com |
| Profile reachability | Address/port and result in memory for 60 seconds, cleared on network changes | DNS on the underlying network and TCP handshakes to subscription servers. No application payload, UUID or subscription token. Servers see source IP and timing. Runs when opening the list, explicitly checking, or changing networks while the list is visible. Stops in the background. |
| On-demand diagnostic input | Existing saved subscription/profile snapshot and current session state, in memory only. A downloaded subscription response is inspected without replacing saved access. | Explicit Run checks only. Subscription fetch uses the matching connected session’s local SOCKS proxy; when that session is not connected, direct fetch requires the initially unchecked direct-check consent. The provider receives the subscription URL token. |
| On-demand direct DNS/TCP checks | Host/port and resolved addresses in memory during the operation; not included in the report | Explicit direct-check consent only. DNS and TCP use the underlying network. Servers see source IP and timing; the resolver may see the hostname. No TCP application payload. Network checks stop if Android exposes a VPN whose owner or route cannot be confirmed. API 26–29 cannot identify the owner, so even Vikify may block checks; API 30+ requires a confirmed Vikify owner. No intentional bypass; detection depends on Android-visible networks. |
| On-demand proxy HTTPS checks | Fixed probe URLs and outcome in memory | Through the matching current session’s SOCKS proxy to www.gstatic.com/generate_204, then cp.cloudflare.com/generate_204 if needed. No direct fallback; endpoints see the proxy exit IP. No subscription URL, UUID or token is sent to these endpoints. |
| Diagnostic results and report preview | In memory until the diagnostic screen closes or the process ends | No automatic upload. Only enum/numeric/boolean fields enter the report: schema/app/API versions, protocol category, completion state, stages, statuses, routes, error/action codes, rounded elapsed time, optional HTTP status and profile counts. |
| Shared TXT/JSON report | Created only after preview and explicit Share report, in app-private cache. At most eight files. Files at least one hour old are pruned on the next app startup/share, not on a one-hour timer. Clear app cache or uninstall to remove local files. | Android chooser grants read access to the chosen recipient through a narrowly scoped FileProvider. The recipient controls its copy. No report upload by Vikify. |
| Xray output | Read and discarded, including debug builds | No diagnostic upload |
| Debug health diagnostics | Android logcat: HTTP status or exception class only | No upload; no subscription token or UUID |

Connectivity checks run before VPN establishment, after changes of the active network, and approximately every minute. Remote subscription refresh runs manually, on return to the app when stale, and through an Android daily scheduled job. Android can defer background work. Imported local VLESS sets have no update URL and no scheduled refresh.

The removal action stops a running session, clears the subscription and legacy access code, and cancels the scheduled refresh. Uninstall removes app-private storage. On startup, version 1.4 deletes the legacy configuration file that earlier versions could leave after abrupt termination.

The client has no advertising, analytics, location, contact or account-registration SDK. Server-side handling is defined by the subscription and VPN operator.

Diagnostic reports exclude subscription URLs/tokens, UUIDs, keys, profile names,
server hostnames/IP addresses and ports, SSIDs, device identifiers, timestamps,
logs, raw server responses and exception messages. Reporting uses a typed
allowlist, not redaction of arbitrary text. Export is limited to 16 events and
64 KiB; numeric fields are bounded and durations rounded down to 100 ms.

Diagnostics does not refresh saved access, select profiles or start/stop VPN.
The separate Connect and return button closes the dialog and invokes the normal
connection flow. Closing the screen, leaving the foreground or changing the
saved access/session cancels an active run. An HTTPS success through local SOCKS
does not prove traffic from other Android apps traverses TUN: that stage remains
`NOT_TESTED` / `TUN_NOT_VERIFIED`. The direct-check consent covers this diagnostic
screen, not the existing profile-list reachability or scheduled refresh features.
See [diagnostic behavior and report schema](DIAGNOSTICS.md).

Opt-in emulator QA uses a separate probe app to test DNS and HTTPS through the
VPN. An optional Windows scanner public certificate is generated under ignored
build output and packaged only with `-Pemulator=true`; it is absent from normal
ARM64 builds. Live test credentials are streamed to an app-private file, deleted
on reading, and saved test access is removed at the end. Optional native
diagnostics exist only in the instrumentation APK and redact transport
credentials before printing selected errors. See `BUILD_AND_VALIDATE.md`.

### Subscription input in version 1.3

Paste reads one clipboard item on demand. Camera frames and user-selected images
are decoded locally with ZXing and are not retained. The camera permission is
requested only for live scanning. The file picker grants access to the selected
image. Version 1.6 accepts HTTPS URLs, VLESS links and plain/Base64 lists. Only a confirmed import enters the encrypted repository. Mixed imports show sanitized line numbers and reasons; rejected entries are not retained in the saved profile body. Failed or cancelled imports retain previous access. Only one subscription or local set is active. HTTP URLs and disabled TLS verification are rejected; redirects are limited to three HTTPS hops.
