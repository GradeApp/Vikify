# Current F-Droid status — 2026-11-24

Request [!58350](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58350#note_4300905331)
was closed by a maintainer on 2026-11-23 because unique features were not
established. The last [hosted pipeline](https://gitlab.com/GradeApp1/fdroiddata/-/pipelines/3095986517)
failed before starting any jobs; no hosted build or technical rejection is inferred.
The local official-container verification below remains valid for the unchanged APK.

1.6.1 build 24 is now the stable publisher release on the website, GitHub and
the independent F-Droid repository. Galaxy A03s was checked again on November 24;
see [the stable report](RELEASE_1.6.1_BUILD24.ru.md).
No new main-catalog request has been sent: the
[differentiation gate](FDROID_COMPARISON.md) is not yet met.
GitLab identity verification alone does not resolve the closure reason.

## Historical verification report — November 20

The following report is preserved as dated evidence. Its statements about an
open draft described November 20 and are superseded by the current status above.

# F-Droid verification status — 2026-11-20

Vikify 1.6.1, versionCode 24, was successfully rebuilt locally using the official
F-Droid Debian Trixie buildserver container and current fdroidserver. The rebuild
passed F-Droid's developer-signature comparison. **The hosted GitLab pipeline
has not run its jobs and must not be described as passing.**
[Draft request !58350](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58350)
is open for maintainer-assisted CI and policy review; main-catalog acceptance
has not been granted.

## Verified build

- Source commit: `d1707cbe1eb7041dafd39a0f7938bf18226b52cc`.
- Container: `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`.
- Image digest: `sha256:1ea8bef1820bff06c2974e31b4fc4c79c364cd24231b86e71fa4b926797e0dac`.
- fdroidserver: `fc2c86473e66a44fc641f403c338a8b2cccfad88`.
- fdroiddata configuration: `aed9e85030fef1f8a33e5e2c928a0ef5268229fb`.
- Metadata formatting and lint: passed.
- Source scan and complete `fdroid build --test --on-server`: passed.
- Final signed APK scan and `checkupdates --auto`: passed.
- Developer APK comparison and allowed signing certificate: passed.
- Unsigned APK SHA-256: `63235b38cf360bb98af00d845992ffe417353b1b2b3df00d71f5c692ffa14997`.
- Publisher APK SHA-256: `204997f77f00c632b11bc0c267cdae8679e7f988539c518389329bb50ca6fd64`.
- Publisher certificate SHA-256: `570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0`.

The unsigned APK also matches the earlier independent Linux builds byte for
byte. The private publisher key was not supplied to the container or GitLab.
Package ID, versionCode, source tag, source archive and published APK are
unchanged. Galaxy A03s upgrade and traffic results remain the November 11
checks documented in [the preparation report](FDROID_MAIN_PREPARATION.ru.md);
no new device run is claimed for November 20.

## Recipe fixes

1. Install OpenJDK 17 from official Debian Bookworm and Bookworm security
   repositories; the current Trixie image does not provide it by default.
2. Install `build-essential` for compiling the Go/mobile host generators.
3. Remove the unused keystore-validation block left incomplete by F-Droid's
   automatic signing-configuration cleanup. Publisher builds retain their guard.
4. Reproduce the non-executable AGP VCS metadata from the original archive-based
   build. The full Git checkout and pinned source commit remain available for
   audit; compiled code is not replaced or patched in the APK.
5. Apply the current fdroidserver metadata formatter.
6. Synchronize `AutoName` with the application's `Vikify VPN` label so
   `checkupdates --auto` leaves the metadata unchanged. This final metadata-only
   correction does not change any build instructions or APK contents.

Details are in [the build guide](FDROID_BUILD.md) and the
[submission recipe](../fdroid/metadata/vikify.vpn.yml).

## Hosted CI and next step

The public [fdroiddata branch](https://gitlab.com/GradeApp1/fdroiddata/-/tree/codex/vikify-1.6.1)
contains only the new `metadata/vikify.vpn.yml` file. Its initial
[pipeline](https://gitlab.com/GradeApp1/fdroiddata/-/pipelines/3095963251)
was stopped before any jobs were created: GitLab requests additional account
identity verification. It reports no YAML configuration error.

The account owner can complete GitLab's verification and rerun the pipeline.
Alternatively, F-Droid's new-app template provides for asking maintainers to
run CI when GitLab verification prevents using the fork's runners. That route
requires opening a clearly marked draft request before hosted CI succeeds.
The publisher authorized that draft-only route, and
[!58350](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58350) now explicitly
requests maintainer-assisted CI. It remains a draft pending hosted checks and
manual review; opening it does not establish that CI passed.

Manual acceptance is a separate decision. The available development history
and maintenance intention are documented in [PROJECT_HISTORY.md](PROJECT_HISTORY.md);
continuous public development over three months or a large user base is not
claimed without evidence.

## Existing non-fatal compiler warnings

- F-Droid writes `ndk.dir` to temporary local properties; AGP deprecates that
  mechanism. The app also pins `android.ndkVersion` to the same r28c version.
- AndroidX `EncryptedSharedPreferences`/`MasterKey` and Android's status-bar
  color setter are deprecated APIs. Migrating encrypted storage requires a
  separately tested data-preserving app update; no storage migration is hidden
  in this recipe-only change.
- Kotlin reports two intermediate `errorRes` assignments as unused.

These warnings did not prevent the successful build or binary/signature
verification. They are recorded rather than suppressed.
