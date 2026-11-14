# DuoLink / VORTEX release shrinking rules.

# Wire models: keep names and fields so JSON payloads never drift between
# the phone and the desktop after R8 rewrites the class.
-keep class com.phonepc.pairing.data.** { *; }

# THE wire envelope. Gson serializes this by reflection, so R8 renaming
# v/type/id/ts/payload silently breaks the desktop's envelope validation
# ("Unsupported protocol version undefined"). Keep it verbatim.
-keep class com.phonepc.pairing.network.Protocol$Envelope { *; }
-keep class com.phonepc.pairing.network.Protocol { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp / OkIO ship their own consumer rules; these are optional-platform
# dependencies that are absent on Android and only referenced in dead branches.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
