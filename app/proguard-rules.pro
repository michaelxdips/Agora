# Hermes fork — phone R8 rules.
#
# Why this file grew: the phone release shipped with `isMinifyEnabled = false` for the whole
# life of the fork, so every rule below was *unexercised*. Flipping R8 on (Session 4) without
# these rules would rename or strip classes the platform and the C++ layer resolve **by name**,
# and every one of those failures is silent at build time and fatal at runtime:
#
#  * JNI `FindClass` / `GetFieldID` / `GetMethodID` in `app/src/main/cpp/llama_chat_template.cpp`
#    and `llama_chat_callbacks.cpp` look up Kotlin classes and members by their *literal* names.
#    R8 has no static edge from C++ to Java, so nothing keeps them unless declared here.
#  * The platform instantiates manifest components (Application, Activity, Services, Receivers,
#    ContentProvider) by name.
#  * WorkManager instantiates `CoroutineWorker` subclasses by name from its database.
#
# Each rule below was derived by reading the C++ sources and the manifests, not guessed; the
# comment names the call site so a future edit can re-check it.

# ── kotlinx.serialization (pre-existing) ─────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.** { kotlinx.serialization.KSerializer serializer(...); }

# ── Room (pre-existing) ──────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ── OkHttp & Okio (pre-existing) ─────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── DataStore (pre-existing) ─────────────────────────────────────────────────
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# ── JSch (pre-existing) ──────────────────────────────────────────────────────
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ── Compose (pre-existing) ───────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── HERMES INTEGRATION POINT: JNI surface ────────────────────────────────────
#
# `llama_chat_template.cpp:32-38` does `FindClass("com/newoether/agora/api/ChatTemplateGrammarTrigger")`
# and `FindClass("com/newoether/agora/api/LlamaChatTemplateResult")`; the constructors, fields and
# (for the result) getters are then fetched with `GetMethodID` / `GetFieldID` using literal names:
#   LlamaChatTemplateResult: <init>(…), grammarLazy, format, grammarTriggers, preservedTokens, parser
#   ChatTemplateGrammarTrigger: <init>(ILjava/lang/String;I)V, type, value, token
#   ChatTemplateMessage / ChatTemplateTool / ChatTemplateToolCall: their array-typed fields are
#   reached by signature from the request object, so their member names must survive too.
-keep class com.newoether.agora.api.LlamaChatTemplateResult { *; }
-keep class com.newoether.agora.api.ChatTemplateGrammarTrigger { *; }
-keep class com.newoether.agora.api.ChatTemplateMessage { *; }
-keep class com.newoether.agora.api.ChatTemplateTool { *; }
-keep class com.newoether.agora.api.ChatTemplateToolCall { *; }
-keep class com.newoether.agora.api.LlamaChatTemplateRequest { *; }

# `llama_chat_callbacks.cpp:67-81` fetches the callback object's class with `GetObjectClass` and then
# `GetMethodID(clazz, "onText", "(Ljava/lang/String;)Z")` and friends. The callbacks are anonymous
# objects implementing this interface inside `LlamaChatEngine`, so keeping the interface's method
# names is what keeps the anonymous classes' overrides resolvable (R8 renames interface methods and
# their overrides together; the lookup is by the literal name on the *object's* class).
-keep interface com.newoether.agora.api.NativeChatCallback { *; }
-keep class * implements com.newoether.agora.api.NativeChatCallback { *; }

# `nativeInitializeBackends` and every other `external` function: the JVM resolves these by the
# mangled `Java_com_newoether_agora_api_*` symbol, which is derived from the *declaring class and
# method name*. Renaming either makes `UnsatisfiedLinkError` on first use.
-keepclasseswithmembers class com.newoether.agora.api.LlamaEngine { *; }
-keepclasseswithmembers class com.newoether.agora.api.LlamaChatEngine { *; }
-keepclassmembers class * { native <methods>; }

# ── HERMES INTEGRATION POINT: platform entry points ──────────────────────────
#
# Instantiated by name from `AndroidManifest.xml`; R8 has no edge to them.
-keep class com.newoether.agora.AgoraApplication { *; }
-keep class com.newoether.agora.MainActivity { *; }
-keep class com.newoether.agora.sandbox.SandboxDocumentsProvider { *; }
-keep class com.newoether.agora.service.AgoraForegroundService { *; }
-keep class com.newoether.agora.service.BootReceiver { *; }
-keep class com.newoether.agora.automation.AutomationAlarmReceiver { *; }
-keep class com.newoether.agora.autopilot.wearsync.PairingListenerService { *; }
-keep class com.newoether.agora.autopilot.update.UpdateDownloadAction { *; }

# ── HERMES INTEGRATION POINT: WorkManager workers ────────────────────────────
#
# WorkManager persists a class *name* and re-instantiates it after process death, app update and
# reboot. A renamed worker is a job that silently never runs again.
-keep class * extends androidx.work.ListenableWorker { *; }

# ── HERMES INTEGRATION POINT: reflection the fork itself performs ────────────
#
# `AppContainer` loads the per-flavor sandbox factory by name:
#   Class.forName("com.newoether.agora.sandbox.FdroidSandboxManagerFactory")   (fdroid)
#   Class.forName("com.newoether.agora.sandbox.PlaySandboxManagerFactory")     (play)
# R8 sees no static edge from a string. Each flavor's class is kept by the shared rule below; the
# missing class in the other flavor is simply absent, which the caller already handles.
-keep class com.newoether.agora.sandbox.FdroidSandboxManagerFactory { *; }
-keep class com.newoether.agora.sandbox.PlaySandboxManagerFactory { *; }
