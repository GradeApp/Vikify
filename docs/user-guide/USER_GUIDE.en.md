# Vikify · user guide

Version 1.7.0, versionCode 25. Android 8.0 or newer on ARM64 is required. Before the first connection, read the VPN disclosure and tap Agree and connect. The Privacy policy is available there and in Help. Withdrawing consent in Help disconnects the VPN.

## Add a subscription or profiles

Supported transports are VLESS + REALITY, XHTTP/TLS and WebSocket/TLS. Import an HTTPS subscription with arbitrary paths and query parameters, standard or URL-safe Base64, an individual VLESS link, or a list of links. JSON configurations with routing rules and other protocols are not automatically converted.

Open Add subscription or Details → Replace link. Enter text, tap Paste, or choose QR code to scan with the camera or select an image. Check the displayed source and confirm. Mixed lists first show the number of compatible profiles and reasons for skipped entries. Import compatible saves only validated profiles; cancelling preserves existing access.

One subscription or saved set is active at a time. HTTPS subscriptions support manual and background updates. Profiles imported directly from text/Base64/QR without an HTTPS address are stored locally and have no remote Update button; import new links to replace them.

HTTPS/TLS certificates are always verified. At most three HTTPS redirects are accepted. HTTP, URL credentials/fragments and options disabling certificate verification are rejected. Limits: 100 compatible profiles, 500 source lines and a 256 KiB response. Failed imports and cancelled reviews preserve saved access. An incomplete background update cannot remove the selected endpoint.

## Connect

The Proxy profiles appear as Primary, Backup 1, Backup 2, Alternative · XHTTP and Last resort · WebSocket. Optional entries appear as Automatic and Backup server (WEB-RESERVE) or Secondary server (EDGE-2). Details lists each original name with its protocol and port.

Choose a profile and tap Connect. Accept the Android VPN permission request. Start with the main REALITY profile, then try the reserve REALITY profiles, XHTTP and WS if needed.

Vikify checks connectivity through the selected SOCKS proxy before creating the VPN. It checks again when the network changes and approximately once a minute. A failed check offers a retry or another profile.

Disconnect is available on the home screen and in the VPN notification. Selecting another profile prepares a switch. The explicit switch action restarts the connection.

## Profile name colours

Opening the list checks the server addresses. Check starts another check. A green name and ✓ mean the server accepts connections; a red name and × mean two attempts failed. A neutral name means unknown, expired, checking or no suitable network. The selected profile and active VPN are preserved.

Results last one minute and reset when the network changes. Checks stop when the app leaves the foreground. Only the address and port are checked, without sending credentials. Protocol operation and authorization require an actual VPN connection.

## Diagnostics

Diagnostics is available in version 1.7.0. It helps explain subscription and connection failures without changing saved access.

1. Open **Diagnostics** on the home screen. **Allow direct checks** starts off.
   Enable it only if you want DNS/TCP checks on the underlying network and a
   direct subscription request when the selected profile is not connected.
   These servers can see your source IP. A matching connected profile can be
   checked through its proxy without enabling direct requests; failures do not
   fall back to a direct connection.
2. Tap **Run checks**. Read each result and suggested next step. **Stop** ends
   the run; an incomplete report is not a successful full check. Diagnostics
   does not change saved access, select a profile or start/stop VPN.
3. If a connection is required, **Connect and return** closes diagnostics and
   starts the normal connection flow. Reopen Diagnostics after connecting.
4. Choose **View report** at the top, then **Text** or **JSON**. The preview
   matches the exported file. **Share report** opens Android’s recipient
   chooser only when you tap it; there is no automatic sending. The report uses
   predefined codes and bounded numbers, excluding subscription URLs, tokens,
   UUIDs, profile names, server addresses and raw error messages.

A reachable port or successful proxy HTTPS request does not prove other apps’
traffic passes through Android TUN; that result remains **Not tested**. If
Android exposes a VPN whose owner or route cannot be confirmed, network checks
stop. On older Android this can also happen with Vikify connected. Keep VPN
protection if you need it. See [diagnostic behavior and limits](../DIAGNOSTICS.md).

## Update or remove access

The subscription card shows the profile count. Tap Update to refresh. Details contains the last update time and the actions for replacing the link and removing access. Android also schedules a daily background update, and the app refreshes stale data when it returns to the foreground.

Network failures preserve saved profiles. An invalid subscription link suspends new connections using that source. Ask your administrator for the replacement and tap Replace link. A failed replacement preserves the previous subscription.

Changed settings for the active profile take effect through Apply and reconnect. A running session uses its original settings.

Remove saved access deletes both the subscription and the old UUID from this device. If a VPN session is running, confirm Disconnect and remove.

## Build

Use the [Linux source-build guide](../FDROID_BUILD.md), including preparation of the pinned native components before Gradle. It describes debug and unsigned release builds; do not reuse the historical prebuilt-native Windows workflow.

For signed releases, use external credentials as described in [release signing](../RELEASE_SIGNING.md). Upgrades require the same package and signing identity. Verify native VPN traffic on an ARM64 device.
