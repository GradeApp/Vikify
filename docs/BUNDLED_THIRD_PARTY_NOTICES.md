# Bundled third-party components (OSS / native)

This is an **inventory for compliance**, not legal advice. Operators must retain upstream LICENSE files where required and satisfy attribution for their distribution channel.

| Component | Form | Notes |
|-----------|------|--------|
| **AndroidX** (Compose, Lifecycle, Core, Activity, Security Crypto, etc.) | Maven AARs | Apache 2.0 (typical) — see Maven artifacts |
| **Kotlin** / **kotlinx.coroutines** | Runtime | Apache 2.0 |
| **Material 3 / Compose BOM** | Maven | Apache 2.0 |
| **CameraX** | Maven AARs | Apache 2.0 |
| **ZXing Core** | Maven JAR | Apache 2.0 |
| **tun2socks** (`native/tun2socks`, package `engine`) | Source-built AAR / Go JNI | MIT, Jason Lyu. Commit `c36137df92a9f9a698a1bae73518c445802df82d`; dependency patch in `native/patches`. |
| **libxray.so** (generated, arm64-v8a) | Source-built Xray 26.2.6 | MPL 2.0. Source in `native/xray-core`, upstream commit `12ee51e4bb1d02ece4ef4b7114efa2bcdc130995`. |
| **Go runtime and mobile bindings** | Pinned by `native/versions.json` and `go.mod` | BSD-3-Clause |
| **JSON profile templates** (`assets/profiles`) | Vikify configuration examples with credential placeholders | MIT |

### Action for operator

The release packages `assets/legal/native-notices.txt`, containing native
component and dependency license texts. Exact source revisions, toolchains and
the complete tun2socks dependency patch are included in `native/`. Both native
components are compiled from those sources; no checked-in AAR or SO is used.
The unused legacy tun2socks executable is no longer packaged. Upstream license
files and copyright headers are retained in the source trees.

1. The release website includes `android-notices.txt` with the 109 Maven coordinates identified in the release dependency report, upstream POM license information and the license text found in the cached artifacts. Refresh this file when the resolved graph changes.
2. For **tun2socks.aar** and **libxray.so**, include license texts and exact source revisions in public release bundles.
3. If you modify sources, comply with the corresponding copyleft terms.

Artwork provenance must be documented separately; do not infer third-party
image rights from the project's code license.
