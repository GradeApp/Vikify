# Bundled third-party components (OSS / native)

This is an **inventory for compliance**, not legal advice. Operators must retain upstream LICENSE files where required and satisfy attribution for their distribution channel.

| Component | Form | Notes |
|-----------|------|--------|
| **AndroidX** (Compose, Lifecycle, Core, Activity, Security Crypto, etc.) | Maven AARs | Apache 2.0 (typical) — see Maven artifacts |
| **Kotlin** / **kotlinx.coroutines** | Runtime | Apache 2.0 |
| **Material 3 / Compose BOM** | Maven | Apache 2.0 |
| **CameraX** | Maven AARs | Apache 2.0 |
| **ZXing Core** | Maven JAR | Apache 2.0 |
| **tun2socks** (`app/libs/tun2socks.aar`, package `engine`) | AAR / Go JNI | MIT, Jason Lyu. Bundled source checkout identifies commit `c36137df92a9f9a698a1bae73518c445802df82d`. |
| **libxray.so** (jniLibs, arm64-v8a) | Xray 26.2.6, commit `12ee51e` | MPL 2.0. Source at https://github.com/XTLS/Xray-core/tree/12ee51e |
| **JSON profile templates** (`assets/profiles`) | App assets | Operator content; not third-party OSS by default |

### Action for operator

The release packages `assets/legal/native-notices.txt`, containing the main
native component licenses, Go's license and notices for 53 pinned dependency
versions. The source checkout for the Go JNI AAR has local go.mod/go.sum changes.
Keep that checkout and the binary module manifests for reproducible rebuilds.

1. The release website includes `android-notices.txt` with the 109 Maven coordinates identified in the release dependency report, upstream POM license information and the license text found in the cached artifacts. Refresh this file when the resolved graph changes.
2. For **tun2socks.aar** and **libxray.so**, keep vendor license text alongside your private release bundle.  
3. If you modify sources, comply with the corresponding copyleft terms.
