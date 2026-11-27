# Building Vikify from source for F-Droid

The Android package remains `vikify.vpn`. The production signing certificate
remains `570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0`.
The F-Droid recipe must use `Binaries` and `AllowedAPKSigningKeys` so only
verified reproducible APKs carrying that developer signature can be published.
No publisher private key is needed by the build server.

Current maintenance and provenance: [project history](PROJECT_HISTORY.md).
The external submission recipe pins the original release commit. Documentation
updates on `main` do not replace its tag, source archive, or APK.

## Toolchains

- Linux x86_64, OpenJDK 17.
- Go 1.26.1; Android SDK platform 36 and build-tools 36.0.0.
- Android NDK 28.2.13676358; ARM64, native min API 26.
- Gradle 8.13 and Android Gradle Plugin 8.13.2.
- Python 3 (standard library) and GNU patch for the native build and deterministic AAR/JAR packaging.
- A host C/C++ toolchain (`build-essential`) for compiling the Go/mobile generators.

The current F-Droid CI image is Debian Trixie. The recipe obtains OpenJDK 17
from the official Debian Bookworm and Bookworm security repositories and explicitly selects it for
Gradle. Do not assume `apt-get install openjdk-17-jdk-headless` succeeds against
Trixie repositories alone.

F-Droid sanitizes Gradle signing configuration before `prebuild`. The recipe
removes the now-incomplete local-keystore validation block from that temporary
unsigned build script. This block is not app runtime code. Ordinary publisher
release builds retain it; `Binaries` and `AllowedAPKSigningKeys` continue to
enforce the historical developer signature on any F-Droid publication.

Build 24 was originally built from the source archive without a `.git`
directory. AGP therefore wrote `generate_error_reason: NO_SUPPORTED_VCS_FOUND`
to `META-INF/version-control-info.textproto`. The recipe reproduces that
non-executable metadata through the `extractReleaseVersionControlInfo` task.
It does not replace compiled code or disable signature-copy verification.
The actual source commit remains pinned in the recipe and the Git checkout
is retained for F-Droid's source audit. Future releases should use a consistent
Git/archived-source build convention to avoid this release-specific adjustment.

Set `GO` to the full path of the pinned Go binary, `JAVA_HOME` to OpenJDK 17,
`ANDROID_HOME` to the SDK and `ANDROID_NDK_HOME` to its pinned NDK directory.

```sh
python3 scripts/build-native.py --prepare
./gradlew --no-daemon --max-workers=2 -PunsignedRelease=true :app:clean :app:assembleRelease
```

The preparation step downloads Go source modules from pinned module graphs,
verifies checked-in sums and uses Go's public checksum database for additional
transitive module metadata needed by gomobile. The build compiles the vendored Xray/tun2socks trees and the
Go/JNI binding. Its native phase disables module downloads. A documented patch
keeps local module paths relative in Go/JNI build information. The app does not
depend on `app/libs` or checked-in `jniLibs`. See `native/README.md` for exact
upstream commits and the complete local dependency patch.

The explicit `unsignedRelease` property prevents reading signing credentials
and removes the key requirement only for the unsigned build. Ordinary signed
release builds retain the historic-certificate check. No application ID suffix
or runtime feature change is used for F-Droid.

## Publisher signing and reproducibility

Build in two separate clean directories, with separate Go compilation caches.
Do not sign an incremental development build for publication. Compose can emit
equivalent but byte-different stability masks after incremental compilation.
Run the clean command above in an isolated release checkout/source directory;
it removes that checkout's generated `app/build` outputs before rebuilding.
Compare their unsigned APKs and generated native hashes. Sign the first using
`scripts/SignVerifiedApk.java`, the existing external properties file and the
pinned Android build-tools 34.0.0 apksigner.jar.
The signing helper checks the expected certificate
before signing and passes passwords via child process environment variables.

Run `apksigcopier compare` or `fdroidserver.common.verify_apks` against the
second clean unsigned build. A successful
signature-copy verification is required before claiming developer-signature
reproducibility. Do not publish an F-Droid-signed replacement APK: it would not
update installations from the publisher repository.

The current preparation report records actual results; these commands alone
are not evidence that F-Droid has accepted or independently built the app.

## Emulator builds

For debug testing, `-Pemulator=true` now compiles both Xray and the Go/JNI bridge
from source for x86_64. It does not consume the old downloaded emulator Xray or
the former multi-ABI prebuilt AAR. Release tasks reject the emulator property.
The native script also accepts `--abi x86_64` for toolchain-only verification.
The optional old emulator debug manifest/certificate fixtures remain debug-only.
