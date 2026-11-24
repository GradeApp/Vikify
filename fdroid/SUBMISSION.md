# Current inclusion-request status — 2026-11-24

[!58350](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58350#note_4300905331)
was **closed** on November 23: unique features were not established.
The application is distributed through the publisher's own repository and is
not included in the main F-Droid catalog. Build 24 is now the stable publisher
release; its APK, source tag and signing certificate are unchanged.

See [the comparison and readiness criteria](../docs/FDROID_COMPARISON.md)
before requesting reconsideration. A duplicate request for unchanged 1.6.1
has not been submitted. Hosted CI remains unexecuted; the previous local
container verification is documented in [the CI report](../docs/FDROID_CI_STATUS.md).

## Historical November 20 submission material

The original draft below is preserved for audit. Its open-draft status and
suggested wording are historical, not instructions to submit it unchanged.

# Inclusion request: Vikify

Updated 2026-11-20. [Draft merge request !58350](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58350)
is open for maintainer-assisted CI and independent review. This is not an
accepted listing. The reviewed source and matching developer-signed APK are
public; the recipe is in the public GradeApp1/fdroiddata branch
`codex/vikify-1.6.1`.

Current result: the official F-Droid buildserver container rebuilt the APK
successfully and verified its developer signature locally. The public fork's
hosted pipeline is blocked before job creation by GitLab account verification.
See [the current CI report](../docs/FDROID_CI_STATUS.md) for evidence and links.

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
- Complete hosted F-Droid CI and resolve maintainer feedback before acceptance.
  The draft merge request requests help running CI while GitLab's additional
  account verification prevents jobs from starting in the public fork.

The November 20 recipe installs OpenJDK 17 from official Debian Bookworm
repositories because the current F-Droid CI image uses Debian Trixie. It pins
the Gradle JVM to the same major version as the verified developer build.
It also installs `build-essential` to compile the Go/mobile host tools from source.
The reviewed app commit, signed APK and signing key remain unchanged.
F-Droid's automatic signing-config cleanup leaves an incomplete local-keystore
validation block in the upstream Gradle file. The recipe removes that unused
block only from its temporary unsigned build. The publisher release guard and
the `AllowedAPKSigningKeys` check remain in place for their respective paths.
The recipe also reproduces build 24's non-executable AGP VCS metadata, which
records that the publisher build came from a source archive without `.git`.
The F-Droid checkout is retained, its exact commit is pinned, and every runtime
component is rebuilt from source before the signature-copy comparison.

The source/metadata preparation does not change existing stable downloads or
the publisher's F-Droid repository. The draft request does not claim a successful
hosted pipeline or acceptance by the main catalog.
