# Tink / security-crypto optional compile-only annotations
-dontwarn com.google.errorprone.annotations.**
# Optional nullability / thread-safety metadata in dependencies, not runtime APIs.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# EncryptedSharedPreferences / Tink (runtime)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# tun2socks AAR (Go JNI)
-keep class engine.** { *; }

# VPN service entry points
-keep class vikify.vpn.vpn.VikifyVpnService { *; }

# Profile templates are parsed with org.json; keep model enums used in intents
-keepclassmembers enum vikify.vpn.profile.ProfileType { *; }

-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod

# Kotlin metadata (sealed / enums / Compose stability)
-keep class kotlin.Metadata { *; }

-dontwarn kotlinx.coroutines.debug.**

-keepclasseswithmembernames class * {
    native <methods>;
}
