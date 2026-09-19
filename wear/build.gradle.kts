plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("local.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.reader())
}

android {
    namespace = "com.newoether.agora.wear"
    compileSdk = 36

    // OkHttp's platform detection calls `android.util.Log.isLoggable` at class-init. Without this the
    // whole client is unusable from a JVM unit test ("Method isLoggable in android.util.Log not
    // mocked"), which is why WearChatClient had no tests at all until now. Returning default values
    // lets the real OkHttp code path run off-device; the device-side tests still run it for real.
    testOptions.unitTests.isReturnDefaultValues = true

    defaultConfig {
        // Data Layer requires the phone and watch apps to share an applicationId. Same keystore too,
        // or the phone and watch installs are not the same identity and the Data Layer refuses to
        // pair them.
        applicationId = "com.hermes.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 34
        versionName = "3.0.3-hermesx"
        // HERMES INTEGRATION POINT: the instrumented source set. Before this there was no
        // `wear/src/androidTest` at all, so the module's platform-facing claims (keystore round-trip,
        // listener services resolvable from the manifest, R8 keeping the entry points) were asserted by
        // nothing. Run with `./gradlew :wear:connectedDebugAndroidTest` on a device or emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "."))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    val hasKeystore = keystoreProperties.getProperty("storeFile", ".").let { it != "." }
    // Fail-closed, same rule as the phone module (see its HERMES INTEGRATION POINT): a release build
    // with no keystore used to be signed with the debug key, and a watch APK signed with the wrong
    // key is a watch that cannot be updated — the Data Layer also refuses to pair it with the phone.
    if (hasKeystore) {
        buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
    } else {
        gradle.taskGraph.whenReady {
            val releaseTasks = allTasks.filter { task ->
                val name = task.name
                (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package")) &&
                    name.contains("Release")
            }
            if (releaseTasks.isNotEmpty()) {
                throw GradleException(
                    "Release signing is not configured: no keystore in local.properties " +
                        "(storeFile/storePassword/keyAlias/keyPassword), so " +
                        "${releaseTasks.map { it.name }.sorted()} would have been signed with the " +
                        "debug key. Fill in the release section of local.properties, or build a debug " +
                        "variant instead."
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // Keep the release APK small: this is a watch, and an unused locale or density is dead weight.
    //
    // O5 revisited this, and the decision is to keep the filter **and** the strings resource-based:
    // every user-visible sentence in the module now lives in `res/values/strings.xml` (it did not —
    // the action labels and status lines were string literals inside the composables, so the module
    // could not have been translated even if a translation existed). Shipping one locale is still the
    // right size for a 2.8 MB watch APK; adding one is now a `values-<lang>/strings.xml` plus an entry
    // here, which is the whole point of moving them.
    androidResources {
        localeFilters += listOf("en")
    }
}

dependencies {
    // Deliberately minimal. Everything the watch does is: one text field, one voice intent, one HTTP
    // call, one encrypted file. Anything else — image generation, conversation trees, sandbox, MCP,
    // skills, Room, llama.cpp — is absent, not disabled, so it cannot cost build time or battery.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    // Wear Material 3 — the real design system for this form factor: ScreenScaffold, Card, Button,
    // ListHeader, TimeText. Not hand-rolled "minimal" surfaces.
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    // HERMES INTEGRATION POINT (mission 2): `androidx.wear.compose:compose-material:1.6.2` and
    // `androidx.compose.material3:material3` were removed. Evidence: zero imports of either in
    // wear/src (grep 'androidx.wear.compose.material\.' and 'androidx.compose.material3' → empty),
    // neither is a manifest/reflection entry point, and :wear:assembleRelease + :wear:testDebugUnitTest
    // stay green without them. Both dragged whole component sets onto a 2 MB watch APK.
    implementation("androidx.compose.foundation:foundation")

    // Data Layer only: the one-time credential push, the read-only memory snapshot and pairing.
    implementation(libs.play.services.wearable)
    // `await()` on the Data Layer Tasks — without this the Task returned by sendMessage has no
    // coroutine bridge. Same library the phone module already uses for the same reason.
    implementation(libs.coroutines.play.services)

    // play-services-basement drags in androidx.fragment:1.1.0, which is below the 1.3.0 floor the
    // ActivityResult APIs require (lintVitalRelease fails the release build without this). Constrained
    // rather than lint-suppressed: the real defect is a stale transitive version, and pinning the floor
    // fixes it for every consumer of this module instead of muting the check.
    implementation("androidx.fragment:fragment:1.8.5")

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // HERMES INTEGRATION POINT: the instrumented source set's runner and assertions. Kept to the two
    // libraries the tests actually use; nothing here reaches the release APK (androidTest is a separate
    // variant).
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.0")
}
