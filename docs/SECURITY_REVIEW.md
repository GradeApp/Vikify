# Security review — Vikify VPN (private release baseline)

Historical baseline: the implementation and Android service classification
have changed since this review. Do not treat the tables below as the current
manifest audit. For the 1.6.1 source-build verification see
[F-Droid preparation](FDROID_MAIN_PREPARATION.ru.md), the current manifest and
[data inventory](DATA_INVENTORY.md).

## Summary

Lean VPN client: local UUID storage (encrypted), foreground VPN service, Xray + tun2socks in-process, no third-party analytics in Gradle dependencies.

## Manifest & platform

| Control | Status |
|---------|--------|
| `INTERNET` | Required for VPN payload |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Declared; service uses `foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = VPN connection |
| `POST_NOTIFICATIONS` | API 33+; requested in UI path before starting FGS where applicable |
| `BIND_VPN_SERVICE` on `VikifyVpnService` | Correct |
| `android.net.VpnService` intent-filter | Present |
| `exported=false` on VPN service | Reduces external `startService` surface; app starts service explicitly |
| `allowBackup=false` | Reduces backup exfiltration surface |
| `usesCleartextTraffic=false` | Default hardening; cleartext not used for app-controlled HTTP in templates (TLS/Reality paths) |

### Android 14+ foreground services

- Service calls `startForeground` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+ where required, matching manifest.  
- **Conservative note:** policy for `specialUse` on Play is strict; for **private** distribution the engineering requirement is **runtime correctness** (no `MissingForegroundServiceTypeException`) and honest subtype. This app’s subtype describes VPN.  
- If Google later documents a **more specific** FGS type exclusively for `VpnService` without changing behavior, migration should be evaluated against official docs — not done preemptively here to avoid incorrect declarations.

## Storage

- UUID: `EncryptedSharedPreferences` (AndroidX Security Crypto). Deprecation warnings for `MasterKey` / `EncryptedSharedPreferences` are tracked as technical debt (no weakening of encryption in this phase).

## Logging

- Release: VPN failure logs to logcat are **generic**; detailed errors DEBUG-only or mapped to user strings for UI.  
- tun2socks: release does not emit extra `Log.e` lines (see codebase).

## R8 / release binary

- `minifyEnabled` + `shrinkResources` enabled for release.  
- Keep rules cover VPN service, JNI `engine.**`, crypto library, `ProfileType` enum used across IPC/UI boundaries.  
- Validate on real device after each ProGuard change.

## Supply chain

- **Bundled components:** see `docs/BUNDLED_THIRD_PARTY_NOTICES.md`.  
- Distribute APK/AAB over authenticated channels; publish checksums.

## Residual risks (accepted / monitored)

| Risk | Mitigation |
|------|------------|
| Compromised release signing key | PRIVATE_UPDATE_POLICY.md |
| OEM killing VPN background | User education; no silent bypass |
| Profile JSON in `filesDir` while connected | Short-lived; deleted on teardown |
