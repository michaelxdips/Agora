# Hermes watch — R8 rules.
#
# The watch app has no reflection, no Room, no serialization of polymorphic types, so the only thing
# R8 needs is to keep the entry points the platform resolves by name.

# Data Layer listeners are instantiated by the system from the manifest.
-keep class com.newoether.agora.wear.ConfigListenerService { *; }
-keep class com.newoether.agora.wear.MemoryListenerService { *; }
-keep class com.newoether.agora.wear.WearMainActivity { *; }

# kotlinx.serialization keeps its generated serializers; the models here are small and stable.
-keepclassmembers class com.newoether.agora.wear.WearConfig { *; }
-keepclassmembers class com.newoether.agora.wear.WearOfflineQueue$Entry { *; }
