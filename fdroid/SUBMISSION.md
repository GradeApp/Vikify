# Inclusion request: Vikify

This is a prepared submission, not an open merge request or an accepted listing.
Copy `metadata/vikify.vpn.yml` into a branch of
https://gitlab.com/fdroid/fdroiddata after the publication gates below are met.

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

## Publication gates before submitting

- Artwork review completed: the publisher confirmed the older project artwork,
  the unknown-origin launcher icon was replaced with a new generated design,
  and generated background/hummingbird sources and provenance are included
  under `artwork/`. Project artwork is MIT to the extent rights apply.
- Publish the exact reviewed source snapshot and tag `vikify-v1.6.1-build24` to
  the source repository. Replace the recipe's tag with the resulting immutable
  commit SHA if requested by reviewers.
- Publish the matching signed APK at the `Binaries` URL and verify anonymous
  HTTP access and SHA-256. That URL is planned until this publication occurs.
- Attach the completed Galaxy A03s report and source/build verification records.
- Confirm maintenance commitment and describe project history to reviewers.
  Do not invent usage figures or claim that an independent publisher repository
  is an existing main-catalog listing.
- Run the F-Droid CI pipeline and resolve maintainer feedback before acceptance.

The source/metadata preparation does not change existing stable downloads or
the publisher's F-Droid repository. No GitLab issue or merge request has been sent.
