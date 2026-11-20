# Inclusion request: Vikify

Updated 2026-11-20. This is a prepared submission, not an open merge request or
an accepted listing. The reviewed source and matching developer-signed APK are
now public. Copy `metadata/vikify.vpn.yml` into a branch of
https://gitlab.com/fdroid/fdroiddata when submitting for independent review.

## Suggested merge request title

New app: Vikify — VLESS VPN client (developer-signed reproducible build)

## Suggested description

Vikify is an Android VPN client maintained by VictoriaV. It imports user-supplied
HTTPS VLESS subscriptions, plain/Base64 VLESS lists and individual VLESS links.
Clipboard, camera QR and image QR import are available. Users can choose and
inspect profiles; server endpoint reachability is distinguished from a verified
working VPN. English and Russian interfaces are included.

The application uses Android VpnService, Xray-core and tun2socks. It is functional
without an account with the publisher and supports independently operated VLESS
servers. No working server credentials, proprietary advertising, analytics,
Google Play Services or Firebase SDKs are bundled. Server access is supplied by
the user. The privacy policy documents subscription fetches, direct endpoint
reachability checks and tunnel connectivity checks.

- Source repository: https://github.com/GradeApp/Vikify
- Website: https://94043.net/vikify/
- Privacy policy: https://94043.net/vikify/privacy-en.html
- Application ID: `vikify.vpn` (unchanged for existing installations)
- Proposed version: `1.6.1`, versionCode `24`
- Reviewed source commit: `d1707cbe1eb7041dafd39a0f7938bf18226b52cc`
- Source tag: `vikify-v1.6.1-build24`
- Candidate release: https://github.com/GradeApp/Vikify/releases/tag/vikify-v1.6.1-build24
- Developer-signed APK: https://github.com/GradeApp/Vikify/releases/download/vikify-v1.6.1-build24/Vikify-1.6.1-build24-arm64-release.apk
- APK SHA-256: `204997f77f00c632b11bc0c267cdae8679e7f988539c518389329bb50ca6fd64`
- Minimum Android: 8.0 / API 26; target 36; ARM64 only; no root required
- Own code: MIT; Xray-core: MPL-2.0; tun2socks: MIT; Go/mobile: BSD-3-Clause

The source tree includes exact Xray and tun2socks revisions and a complete
dependency patch. Go/mobile is pinned by checksum-verified module metadata.
Its small, published generator patch replaces absolute local-module paths with
stable relative paths; it does not alter the mobile runtime or hide dependency
information. No prebuilt native application libraries are used.

Please retain the developer-signature-only configuration (`Binaries` and
`AllowedAPKSigningKeys`). The signing certificate is
`570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0`.
The private key is not supplied to F-Droid. Publishing with a new F-Droid key
would break updates for current website/GitHub/publisher-repository users.

Local verification produced byte-identical unsigned APKs in two separate Linux
directories with separate Go compilation caches. F-Droid server's signature-copy
verification succeeded against the developer-signed APK and the second build.
Source and APK scanners reported zero problems; metadata lint passed. These
checks do not substitute for the F-Droid build server pipeline or manual review.

## Completed publication checks

- Artwork review completed: the publisher confirmed the older project artwork,
  the unknown-origin launcher icon was replaced with a new generated design,
  and generated background/hummingbird sources and provenance are included
  under `artwork/`. Project artwork is MIT to the extent rights apply.
- Exact reviewed source published on `codex/fdroid-main-1.6.1`, tagged
  `vikify-v1.6.1-build24`. The submission recipe pins the immutable commit above.
- Matching signed APK, source archive and preparation records published in the
  candidate release. Anonymous downloads returned HTTP 200; all archive
  contents, sizes and SHA-256 checksums matched the locally reviewed artifacts.
- The source manifest fetched anonymously at the immutable commit matches the
  reviewed local manifest byte for byte.
- Galaxy A03s and source/build verification records are included with the
  prepared submission. The package dated 2026-11-13 supersedes the earlier
  pre-publication submission draft; the APK and source archive are unchanged.

## Remaining review steps

- Maintenance intention, support contacts and the available project history are
  documented in [PROJECT_HISTORY.md](../docs/PROJECT_HISTORY.md). The publisher
  supports inclusion and ongoing maintenance. No user-count claim is made;
  consolidated public history and local historical evidence are distinguished.
- Run the F-Droid CI pipeline and resolve maintainer feedback before acceptance.

The November 20 recipe installs OpenJDK 17 from official Debian Bookworm
repositories because the current F-Droid CI image uses Debian Trixie. It pins
the Gradle JVM to the same major version as the verified developer build.
The reviewed app commit, signed APK and signing key remain unchanged.

The source/metadata preparation does not change existing stable downloads or
the publisher's F-Droid repository. No GitLab issue or merge request has been sent.
