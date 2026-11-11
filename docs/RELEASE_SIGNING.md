# Release signing for Vikify

Vikify APKs for direct distribution use the signing identity from the 1.0.1 release. Its public certificate SHA-256 is:

```text
570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0
```

The historical private key is in the sibling operations directory at `VikifyOps/keystore/vikify-release.jks`. The matching private configuration is `VikifyOps/local-config/keystore.properties`. Both historical release APKs and the 1.0.1 AAB match this certificate. Keep an encrypted, independently recoverable backup of the key and its credentials outside the source tree.

## Build

For 1.6.1 and later use the [Linux source build](FDROID_BUILD.md), then sign the
verified unsigned APK with `scripts/SignVerifiedApk.java` and the existing
external properties file. The helper refuses a different certificate. F-Droid
receives the public signed APK and source, never the private key. Release
metadata uses `Binaries` and `AllowedAPKSigningKeys` to require the publisher
signature and a successful reproducibility check.

The old `scripts/build-signed-release.ps1` describes the Windows/prebuilt
workflow through 1.6.0 and is retained for historical releases. A direct signed
Gradle build remains possible on Linux using `VIKIFY_KEYSTORE_PROPERTIES` or the
four `VIKIFY_RELEASE_*` variables; it also checks the historical certificate.

The production policy defaults to `https://94043.net/vikify/privacy.html`. The app also includes the policy for offline access. Use `-PrivacyPolicyUrl` if the publisher moves the document to another verified HTTPS address. For an isolated test build, `-IsolatedQa` produces the package `vikify.vpn.releaseqa`. The distribution package is `vikify.vpn`.

## Verify and retain

Use `apksigner verify --verbose --print-certs` for the APK, `jarsigner -verify` and `keytool -printcert -jarfile` for the AAB, and `bundletool validate` for the bundle structure. Keep R8 mapping and native symbols privately for each versionCode. If the project joins Play App Signing, choose the app signing identity and upload key deliberately so direct and Play updates remain compatible.

## Build 14 and debug installations

Build 14 used a different certificate beginning with `e0733734`. Build 15 supersedes it. A debug install or a build 14 install with this other certificate requires a fresh install and subscription reimport. Earlier production installations signed with the historical key can update in place. The provisional PKCS12 remains separate from the VikifyOps key.
