package vikify.vpn.profile

enum class ProfileType(val assetName: String) {
    MAIN_REALITY("main_reality.json"),
    RESERVE_APPLE_REALITY("reserve_apple_reality.json"),
    RESERVE_MICROSOFT_REALITY("reserve_microsoft_reality.json"),
    FALLBACK_WS_TLS("fallback_ws_tls.json")
}
