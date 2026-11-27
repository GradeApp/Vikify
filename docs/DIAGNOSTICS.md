# Vikify diagnostics

Implementation scope: Vikify 1.7.0, version code 25. This document describes
behavior and validation requirements; it is not a test completion report.

## What the user controls

Open **Diagnostics** and choose **Run checks**. Opening the dialog alone does not
start a diagnostic request. The run inspects a snapshot of saved access and the
current connection. It never refreshes the saved subscription, changes the
selected profile, creates a temporary VPN session or connects/disconnects VPN.
Any subscription response downloaded for diagnosis is parsed in memory only.

**Allow direct checks** is initially unchecked. Its choice belongs to the open
dialog and is not a saved preference. Leaving it unchecked still permits checks
through an already connected, matching selected profile. It does not permit a
direct fallback if a proxy request fails.

| Current state | Without direct consent | With direct consent |
|---|---|---|
| Selected profile is connected | Local format; subscription response through the current local SOCKS proxy; HTTPS probes through that proxy | The same checks, plus DNS and TCP on the underlying network |
| Selected profile is not connected | Local format; other steps explain why they were not tested | Local format; HTTPS subscription response, DNS and TCP on the underlying network; no proxy HTTPS test |
| Android reports a VPN whose owner or route cannot be confirmed | Network checks are blocked; no intentional bypass | Network checks are still blocked; consent does not authorize a VPN bypass |
| No usable network | Local format remains available; network results identify the limitation | Same limitation; consent does not make an offline request successful |

The VPN guard examines the networks Android exposes, including a VPN that may
exclude Vikify’s own UID. On API 30 and later it accepts a visible VPN owner only
when that owner is Vikify’s UID; an unknown owner is not assumed to be Vikify.
On API 26–29 the owner API is unavailable, so a visible VPN blocks network
diagnostics, potentially including Vikify’s own session. A VPN as the app’s
active route also blocks these checks. The UI describes an unconfirmed VPN
route rather than asserting that another app is certainly responsible. No
intentional bypass is performed; this guard relies on what Android exposes.

A local VLESS list has no remote subscription URL, so its subscription-download
step is not tested. A session for a different profile is not treated as proof
for the selected one. DNS/TCP consent applies only to this diagnostic screen;
the existing profile-list reachability indicator and normal subscription
refresh have their own behavior, documented in [Data inventory](DATA_INVENTORY.md).

**Connect and return** is a separate user action: it closes the diagnostic
dialog and invokes the ordinary VPN connection flow, including existing consent
requirements. The user can reopen Diagnostics after connecting. A diagnostic
run never silently swaps sessions to perform a test.

## Results and their limits

| Stage | What is observed | What success does not establish |
|---|---|---|
| Saved profile (`LOCAL_FORMAT`) | The selected VLESS configuration passes the local parser | Valid server credentials or a reachable server |
| Subscription (`SUBSCRIPTION`) | An HTTPS response can be fetched and parsed; accepted/skipped profile counts are shown | That every returned profile can connect, or that saved access was refreshed |
| Server address (`DNS`) | A numeric IPv4/IPv6 literal is parsed locally, or the underlying network resolver returns an address for the selected server | Port reachability, authentication or VPN traffic |
| Server port (`TCP`) | A short TCP connection to the selected address/port succeeds; no application payload is sent | TLS/REALITY authentication or a working tunnel |
| Proxy HTTPS (`PROXY_HTTPS`) | One fixed HTTPS endpoint returns HTTP 204 through the matching session’s local SOCKS proxy | Traffic from other Android applications through TUN |
| Other apps (`ANDROID_VPN`) | No end-to-end test of another app’s traffic is performed by this screen | Always `NOT_TESTED` with `TUN_NOT_VERIFIED`; SOCKS success is not substituted for this test |

TCP runs only after the address stage succeeds with a nonempty list of
addresses. Otherwise TCP is `NOT_TESTED` and retains the address-stage cause
(including `DNS_FAILED` or `OFFLINE`); it does not claim a separate TCP failure.

Vikify’s own UID is excluded from its Android VPN. A request made by the app via
local SOCKS therefore cannot prove that another UID’s DNS and HTTPS traffic
traverses Android TUN. A separate controlled test app is needed for that claim.

Every result includes a textual status, explanation and next step where one is
available. Colour is supplementary. `PASS` applies only to its individual
stage. `WARNING` can mean a partially accepted subscription. `FAIL`,
`NOT_TESTED` and `CANCELLED` remain distinct. A report with `completed: true`
means the sequence finished, not that every stage passed. An interrupted run
is incomplete; its earlier successful stages do not imply later success.

When an interrupted result remains visible, the view model records an explicit
`CANCELLED` event with `CANCELLED` status and `completed: false`. An unexpected
exception escaping the runner is recorded instead as `INTERNAL_ERROR` with
`FAIL` status and an incomplete report. It is not labelled as a user stop, and
the exception message is never copied into the result. Closing the dialog
clears its in-memory results rather than retaining an export for later.

The user can stop a run or close the dialog. Leaving the foreground or changing
the saved access/current session cancels an active run. Network operations are
bounded, watch for changes of the active network and close their resources on
cancellation. Android 9 lacks the newer cancellable DNS API: an already running
system resolution may finish later, but its result is discarded after
cancellation, and resolver threads/queued work are bounded.

## Requests and error explanations

Proxy HTTPS probes use `https://www.gstatic.com/generate_204`, followed on
failure by `https://cp.cloudflare.com/generate_204`. Both go through the local
SOCKS proxy. They do not contain the subscription token or UUID, and are never
retried directly. The remote endpoints see the proxy exit IP.

Subscription diagnosis uses the saved HTTPS URL and necessarily sends its token
to that subscription server. A connected matching session routes this request
through SOCKS; otherwise it requires direct consent and uses the underlying
network. Direct DNS/TCP likewise requires consent. The resolver can receive the
server hostname, and the server can observe source IP and timing.

Subscription responses are limited to 256 KiB and three HTTPS redirect hops.
Normal platform TLS verification remains enabled; diagnostics does not accept
insecure certificates, HTTP downgrade redirects or disabled verification.
The UI explains HTTP access denial, missing/expired resources, rate limiting,
server errors, malformed or partial subscriptions, DNS/TCP failure, TLS failure,
timeout, a changed network and the need to connect the selected profile. These
are observed conditions or suggested next steps, not claims to know the
provider’s internal cause. Raw response bodies and exception messages are
never shown or included in a report. A TLS failure is not a reason to turn off
certificate verification.

An otherwise unclassified I/O failure is `NETWORK_IO_FAILED`, including a failure while reading
a response body after HTTP 200. It does not imply that TCP setup failed or the
server port is unreachable. Identified DNS, TLS and timeout failures retain
their specific codes instead of being folded into this general result.

## Report format and sharing

**View report** opens a preview; **Text** and **JSON** select a format. **Share
report** is available from that preview after the run finishes or stops. The
preview uses the same serializer as the exported file. Vikify does not send a
report automatically or preselect an external recipient.

The serializer accepts a typed allowlist rather than trying to redact arbitrary
logs. Its schema version is 1:

| Field | Permitted content |
|---|---|
| `schemaVersion`, `appVersionCode`, `androidApi` | Schema, numeric app version and Android API level |
| `protocol`, `completed` | Fixed protocol category and completion boolean |
| `events[].stage`, `status`, `code`, `route`, `action` | Fixed enum values; no free-form strings |
| `events[].elapsedMs` | Nonnegative duration, capped at 180,000 ms and rounded down to 100 ms |
| `events[].httpStatus` | HTTP status 100–599 or absent/null |
| `events[].acceptedCount`, `skippedCount` | Optional bounded counts; maxima 100 and 500 respectively |

At most 16 events are serialized. The export has a 64 KiB size limit. Protocol
categories are `UNKNOWN`, `REALITY`, `XHTTP_TLS` and `WEBSOCKET_TLS`; these are not
profile identities. Report codes remain stable English identifiers in both
formats, while the result screen provides English and Russian explanations.

Excluded fields include subscription URLs/tokens, UUIDs, public/private keys,
profile names, server hostnames/IP addresses and ports, SSIDs, device identifiers,
timestamps, logs, raw server responses and exception messages. There is no
free-text report input, and no configuration dump or screenshot is attached.

Only explicit sharing writes a file to the app-private `diagnostic-reports`
cache directory. Android’s chooser grants the chosen recipient read access
through a non-exported FileProvider restricted to that directory. The recipient
may save or forward its own copy under its own rules; Vikify cannot delete that
copy. There is no automatic report upload by Vikify.

Up to eight cache files are retained. Files at least one hour old are removed
on the next app startup or share action. This is opportunistic cleanup, not an
exact one-hour deletion timer. Clearing the app’s cache or uninstalling deletes
the local copies. Removing saved VPN access is a separate action and does not
promise to remove copies already shared with another app.

## Validation required for release

Release evidence must be recorded separately, after execution. Relevant checks
include the following:

- A run with direct consent off produces no direct diagnostic DNS/TCP or
  disconnected subscription request; SOCKS failures do not fall back directly.
- No-network, foreign-VPN, expired/invalid/partial subscription, timeout,
  cancellation and profile/session changes produce the appropriate limited
  result, without changing saved access or the active VPN session.
- Successful proxy HTTPS still leaves other-app Android TUN traffic marked
  not tested. A separate traffic probe is used for any actual VPN traffic claim.
- TXT and JSON previews match files received by a local test receiver, contain
  only the schema allowlist and exclude seeded secrets and endpoint identities.
- On Galaxy A03s, both languages, large text, landscape, stopping, reopening and
  returning from the share chooser remain usable.
- The signed update retains the publisher certificate, package identity,
  saved subscription and selection. Phone testing ends in the requested state.

This document does not assert that those checks have passed. See the release’s
actual validation report for measured results and any remaining limitations.
