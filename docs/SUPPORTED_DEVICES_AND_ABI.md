# Supported devices and ABI

## ABI

The release ships **native code only for `arm64-v8a`** (`ndk.abiFilters` in `app/build.gradle.kts`).

| ABI | Supported |
|-----|-----------|
| arm64-v8a | Yes |
| armeabi-v7a | **No** — APK will not install or will miss native libs |
| x86_64 / x86 | **No** |

## Devices

- **Phones and tablets** running Android **8.0 (API 26)** or newer on **ARM64** are in scope.  
- Samsung Galaxy A03s (SM-A037F, Android 13, API 33) passed the five-profile subscription and VPN traffic checks on the physical device. See the [phone test report](PHONE_TEST_REPORT.ru.md).
- **Emulators:** the normal APK requires ARM64. A separate, debug-only
  `-Pemulator=true` build packages the official Android x86_64 Xray executable
  for native testing on Intel/AMD emulators. It does not change release support.
  See [build instructions](../BUILD_AND_VALIDATE.md) and the
  [Android 9/16 test report](EMULATOR_TEST_REPORT.ru.md).

## User-facing note (for release notes)

> Vikify installs only on **64-bit ARM** devices (most modern phones). It does not support 32-bit-only ARM or x86 tablets/emulators without an ARM64-compatible image.

## Operator test matrix (minimum)

At least one device per: Samsung (One UI), Google Pixel, one additional OEM (e.g. Xiaomi/Motorola), Android **14** and **10** if still in your support promise.
