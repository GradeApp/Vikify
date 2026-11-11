# Native source provenance

Vikify builds its native runtime from these source trees. No prebuilt Xray or
tun2socks `.so`, `.aar` or `.jar` is used.

| Component | Exact upstream source | License | Local changes |
|---|---|---|---|
| Xray-core 26.2.6 | https://github.com/XTLS/Xray-core/tree/12ee51e4bb1d02ece4ef4b7114efa2bcdc130995 | MPL-2.0; `xray-core/LICENSE` | None |
| tun2socks | https://github.com/xjasonlyu/tun2socks/tree/c36137df92a9f9a698a1bae73518c445802df82d | MIT; `tun2socks/LICENSE` | `patches/tun2socks-dependencies.patch`, pinning Go mobile binding dependencies |

`versions.json` pins the compiler, NDK, API level and Go mobile version.
The `go.mod` and `go.sum` files pin and authenticate transitive sources.
`scripts/build-native.py --prepare` fetches those sources. The normal build
sets `GOPROXY=off` and requires the pinned toolchains.

The build generates Go/Java JNI bindings and compiles the tun2socks AAR and
Xray executable. Generated AAR/JAR ZIP entries use fixed ordering and dates.
Go builds use trimpath, omit build IDs and VCS metadata, and strip debug paths.
The small `patches/gomobile-relative-modules.patch` changes the build generator
to reference local source modules through stable relative symlinks. This avoids
embedding the absolute checkout directory in Go build information. It does not
modify the mobile runtime or remove dependency provenance. The generator is
compiled from the checksum-verified mobile module and that public patch.
The unused legacy tun2socks executable is not packaged. Upstream copyright
headers and licenses are retained. All vendored source and the local patch
are included in the application source archive.
