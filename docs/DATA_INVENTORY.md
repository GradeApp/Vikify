# Data inventory · Vikify VPN 1.6

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
| Xray output | Read and discarded, including debug builds | No diagnostic upload |
| Debug health diagnostics | Android logcat: HTTP status or exception class only | No upload; no subscription token or UUID |

Connectivity checks run before VPN establishment, after changes of the active network, and approximately every minute. Remote subscription refresh runs manually, on return to the app when stale, and through an Android daily scheduled job. Android can defer background work. Imported local VLESS sets have no update URL and no scheduled refresh.

The removal action stops a running session, clears the subscription and legacy access code, and cancels the scheduled refresh. Uninstall removes app-private storage. On startup, version 1.4 deletes the legacy configuration file that earlier versions could leave after abrupt termination.

The client has no advertising, analytics, location, contact or account-registration SDK. Server-side handling is defined by the subscription and VPN operator.

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
