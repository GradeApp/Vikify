# Maintenance and project history

Vikify is maintained and published by **VictoriaV** through GradeApp/Vikify.
The publisher supports inclusion in the F-Droid main catalog and intends to
maintain the app, review reproducible bug reports, and provide compatibility
and security fixes. No fixed response time or commercial support SLA is promised.

Use [GitHub issues](https://github.com/GradeApp/Vikify/issues) for ordinary bug
reports, including the app version, Android version, and steps to reproduce.
Do not post subscription URLs, access tokens, UUIDs, keys, or browsing records.
For sensitive reports, contact **vikify@proton.me**.

## Verifiable milestones

- The retained local Git repository contains an application snapshot dated
  **2026-04-17**, commit `aad7855`. This is local historical evidence, not a claim
  that continuous public development history is available.
- In early November 2026 the public repository was reorganized and separated
  from an old Xray fork. The retained public history was consolidated. This is
  why its current application history starts with a November snapshot. Original
  third-party license notices and source attribution are retained.
- **2026-11-06:** public application snapshot `ca630c5`, including the independent
  publisher F-Droid repository documentation. Release 1.6.0 was distributed on
  the website and GitHub; build 23 is available from the publisher repository.
- **2026-11-11:** candidate 1.6.1, build 24, replaced prebuilt native application
  dependencies with source builds and documented artwork provenance. Local
  reproducibility and upgrade/traffic tests on a Galaxy A03s passed.
- **2026-11-13 (America/Los_Angeles):** exact candidate source and the signed APK
  were published under `vikify-v1.6.1-build24`, commit
  `d1707cbe1eb7041dafd39a0f7938bf18226b52cc`.
- **2026-11-20:** public downloads, source hashes, metadata, APK scans and the
  signature-copy check were verified again. The publisher authorized the
  F-Droid CI and submission process, contingent on successful checks.

No installation-count or audience-size claim is made. Local developer-device
testing is not presented as evidence of a large or independently verified user
base. Reviewers can assess the available history under the inclusion policy.

## What Vikify provides

Vikify is a usable client for independently operated VLESS servers, with HTTPS,
plain/Base64 and single-link import; clipboard, camera QR and image QR input;
profile descriptions, reachability indication, encrypted local storage, explicit
VPN consent, and an English/Russian Compose interface. It does not bundle server
access or require an account with the publisher. Xray-core and tun2socks are
upstream network engines, with pinned sources and their original licenses.

## Release and update commitments

Production updates retain package `vikify.vpn` and the historical signing
certificate. The private signing key is never uploaded to CI or F-Droid.
F-Droid metadata uses `Binaries` and `AllowedAPKSigningKeys` so an APK is eligible
for publication only when the independent rebuild matches the publisher-signed
APK. A failed reproduction must be fixed; it must not be bypassed with a new key.

Published APKs and release tags are immutable. Fixes affecting a distributed APK
require a new versionCode and a new signed release. Documentation and external
F-Droid recipe improvements can be made without replacing the published APK.
