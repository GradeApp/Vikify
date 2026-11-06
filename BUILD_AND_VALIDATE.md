# Build and validate Vikify 1.5.0

Requires JDK 17+ and Android SDK 36. Normal APKs target ARM64 Android 8+.
The opt-in emulator build below runs native VPN tests on x86_64.

## Build and automated checks

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Add `--offline` when dependencies are already cached. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`. Release signing remains separate;
see [RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

Run instrumentation on a disposable emulator or test device: these tests replace
and remove Vikify's saved access. Do not run them against a user's installation.

```powershell
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
.\scripts\run-device-regression.ps1 -Adb adb -Serial DEVICE
```

Direct instrumentation also works when Gradle's UTP host dependencies are not
cached. Normally `:app:connectedDebugAndroidTest` is an alternative.
The helper starts each test class in its own instrumentation process: the UI
fixtures must not inherit the application's cached repository from integration
tests. Keep the device unlocked and its screen awake during UI checks; restore
any temporary screen settings afterwards. The live account test is opt-in and
is run separately below.

- JVM tests cover VLESS parsing, URL validation, unsupported formats, duplicate
  and partial imports, stable profile identity and credential changes.
- Android tests cover five generated Xray configurations, encrypted persistence,
  failed/cancelled replacement, revoked access, updates and UUID migration.
- The UI test exercises profile selection, a rejected replacement and removal
  through real Compose controls. It uses synthetic credentials and makes no
  subscription download. Screenshots are written to the test device's
  `Android/data/vikify.vpn/files/ui-audit/` directory.

## First-release acceptance

Use an administrator-provided **test** subscription; never paste its token or
credentials into reports. Main format: `https://host/sub/<token>`.

1. Fresh install: add a long link, edit it, cancel, then import. Verify all supplied
   profiles in server order, including optional Automatic and Backup server entries. On upgrade, previously saved UUID access remains
   available until successful import or confirmed removal.
2. Select each REALITY, XHTTP and WS profile on a physical ARM64 device. Grant
   VPN permission, check real browser traffic and disconnect from both the
   screen and notification. Check process/config cleanup.
3. Decline VPN permission, retry, reopen the activity, switch Wi-Fi/mobile data
   and test an unavailable server. Verify actual state and visible recovery.
4. Update a test subscription with reordered/removed profiles and changed
   credentials. Existing sessions retain their identity until explicit
   reconnection. A failed refresh or replacement retains cached profiles.
5. Return 404/410 for the source: new connections are disabled; a running
   connection can still be stopped. Verify server-side revocation separately.
6. Remove while connected: cancellation preserves access; confirmation stops
   the session before clearing subscription and legacy UUID.
7. Review RU/EN, narrow and landscape layouts, keyboard, 200% font size,
   TalkBack focus and disabled system animations.

## Native x86_64 emulator checks

Use disposable emulators. This suite replaces and removes saved access.
ARM translation cannot reliably execute the production Xray binary; prepare a
separate native emulator binary with a pinned SHA-256 instead:

```powershell
.\scripts\prepare-emulator-native.ps1
.\gradlew.bat -Pemulator=true :app:assembleDebug :app:assembleDebugAndroidTest :vpnprobe:assembleDebug :vpnprobe:assembleDebugAndroidTest
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE install -r vpnprobe/build/outputs/apk/debug/vpnprobe-debug.apk
adb -s DEVICE install -r vpnprobe/build/outputs/apk/androidTest/debug/vpnprobe-debug-androidTest.apk
.\scripts\run-device-regression.ps1 -Adb adb -Serial DEVICE
```

Run the authorized live account check separately. The helper reads Proxy's
SQLite DB in read-only mode and streams the token/UUID to app-private storage;
neither value appears in command arguments or console output.

```powershell
python scripts/load-test-subscription.py --db C:/Users/adesp/Desktop/Proxy/users.db --adb C:/Users/adesp/AppData/Local/Android/Sdk/platform-tools/adb.exe --serial DEVICE --user-id 3
adb -s DEVICE shell am instrument -w -r -e class vikify.vpn.subscription.LiveSubscriptionTest -e live true -e diagnostics true vikify.vpn.test/androidx.test.runner.AndroidJUnitRunner
```

Optional arguments: `-e transports xhttp,ws`, `-e cycles 3`, `-e lifecycle true`.
Use `-e transports tcp` to select the three REALITY profiles. The test fails
if no profile matches, a requested transport is absent, or cycles is zero.
Opt-in diagnostics also report certificate issuers on the public subscription
host and failed endpoints while retaining certificate validation.
The lifecycle scenario declines and grants Android's VPN permission, invokes
the notification's disconnect action, reconnects and removes active access.
The companion `vpnprobe` uses a different UID, checks Android's default VPN
network and performs DNS plus HTTPS through the TUN. Vikify's own SOCKS health
check alone cannot prove that other apps' traffic uses the VPN.

If this Windows host's Avast intercepts HTTPS, an explicit test-only option is
available: `scripts/prepare-emulator-native.ps1 -TrustWindowsHttpsScanner`.
It adds the **installed public root certificate** only to the emulator debug
variant and its probe. Certificate and hostname verification stay enabled.
No Avast setting is changed. This cannot make REALITY accept intercepted TLS.
Run the script without the option to remove the generated manifest override.
The emulator property is rejected for release tasks; normal builds include
neither the test root nor emulator assets. Build again **without** `-Pemulator`
before sharing an ARM64 APK: the two variants use the same output path.

Results and limitations: [emulator report](docs/EMULATOR_TEST_REPORT.ru.md).

The [physical phone report](docs/PHONE_TEST_REPORT.ru.md) covers installation,
all five profiles, real Wi-Fi loss and recovery, large text in landscape, and
frame timings on a Samsung Galaxy A03s. To repeat the landscape UI regression
on a fresh test installation, run `SubscriptionUiTest` with `-e rotation landscape`.

### First-tap profile selection on a phone

Install the two `vpnprobe` APKs built above and open the profile list in
`vikify.vpn` with a different profile selected. Use the live row action in
automated profile switches. It checks the label on the current selectable row
and verifies the selected name on the home screen. A detached row cannot
dispatch a tap at its former screen coordinates.

```powershell
adb -s DEVICE shell 'am instrument -w -r -e profile "Backup 2" -e class vikify.vpn.probe.ProfilePickerTest#selectProfileOnce vikify.vpn.probe.test/androidx.test.runner.AndroidJUnitRunner'
```

Use `#titleRespondsToOneTap` to exercise a single touchscreen tap on the
visible label. Use `#dismissedRowCannotBeActivated` to close the picker
between identifying and activating a row. Each case starts with the list open
and the target unselected. After connecting, run the traffic check separately.

```powershell
adb -s DEVICE shell am instrument -w -r -e class vikify.vpn.probe.TunnelTrafficTest vikify.vpn.probe.test/androidx.test.runner.AndroidJUnitRunner
```

## Scope and remaining work

Main VLESS-list subscriptions are implemented. Specialized `/reality` and
`/xhttp` sing-box JSON subscriptions have different DNS/routing semantics and
are rejected with an explanation. Their routing, deep links and automatic
failover remain in [the implementation plan](docs/PROXY_SUBSCRIPTIONS_PLAN.ru.md).
Proxy publication/revocation changes require separate server-side validation.

## Isolated UI checks on a phone

For the three-source import audit use `-PisolatedUiQa=true`. On a fresh QA
installation, run `scripts/load-test-subscription.py` with the usual database,
serial and user arguments and add `--import-audit`. Run the following test with
`-e source clipboard` to exercise the foreground Android clipboard and restore
its previous value. For camera and PNG, import through the actual camera or
system file picker first, then use `-e source verify` to check the stored account
in a fresh process. Start each path with an empty QA installation and re-seed
the private fixture after clearing it. Remove the QA packages after the audit.

```powershell
adb -s DEVICE shell am instrument -w -r -e class 'vikify.vpn.subscription.LiveImportAuditTest#importedAccount' -e source clipboard vikify.vpn.uiqa.test/androidx.test.runner.AndroidJUnitRunner
```

The [1.2.0 device report](docs/UI_OPTIMIZATION_REPORT.ru.md) includes the before/after frame timings, screenshots, signed phone APK and backup.

Build with `-PisolatedUiQa=true` to install the debug application and its tests as `vikify.vpn.uiqa` and `vikify.vpn.uiqa.test`. Use `scripts/run-device-regression.ps1 -Adb adb -Serial DEVICE -Package vikify.vpn.uiqa` on that disposable installation. The normal phone APK uses `vikify.vpn`.

For scene measurements, restart the isolated QA activity with `adb -s DEVICE shell am start -n vikify.vpn.uiqa/vikify.vpn.ui.MainActivity --ei flightStage N`. Force-stop the QA package before each measurement. Use 0 for the still scene, 1 to add the hummingbird, and 2 to add unrestricted fireflies. The ordinary APK always renders the complete scene.
