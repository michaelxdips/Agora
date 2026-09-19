# Hermes watch — R8 rules.
#
# The watch app has no reflection, no Room, no serialization of polymorphic types, so the only thing
# R8 needs is to keep the entry points the platform resolves by name.

# Data Layer listeners are instantiated by the system from the manifest.
-keep class com.newoether.agora.wear.ConfigListenerService { *; }
-keep class com.newoether.agora.wear.MemoryListenerService { *; }
-keep class com.newoether.agora.wear.WearMainActivity { *; }
# HERMES INTEGRATION POINT: PairingAckListenerService was missing from this list. It is declared
# exported in the wear manifest and the system resolves it by name from MESSAGE_RECEIVED, so R8
# renamed or stripped it in release builds — the phone's answer to a pairing request was delivered to
# nothing, and the watch sat on "Waiting for the phone…" until the 20 s timeout. Debug builds do not
# minify, so every test of the pairing path passed while the shipped APK could not pair.
-keep class com.newoether.agora.wear.PairingAckListenerService { *; }

# kotlinx.serialization keeps its generated serializers; the models here are small and stable.
#
# HERMES INTEGRATION POINT: `-keepclassmembers` keeps the *members* of the class named, not the
# generated `WearConfig$$serializer` companion that R8 has to find by reflection at runtime. The
# phone's `app/proguard-rules.pro` carries the two rules that matter
# (`-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }` and the
# `Companion` / `serializer(...)` pair); the watch had neither, so a release build could fail to
# deserialize its own config file and the queue with `SerializationException`. Added rather than
# substituted — the existing member keeps stay.
-keep,includedescriptorclasses class com.newoether.agora.wear.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.wear.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.wear.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.newoether.agora.wear.WearConfig { *; }
-keepclassmembers class com.newoether.agora.wear.WearOfflineQueue$Entry { *; }
