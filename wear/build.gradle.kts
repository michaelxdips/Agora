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
        versionCode = 32
        versionName = "3.0.1-hermesx"
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
    val releaseSigning = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")

    buildTypes {
        release {
            signingConfig = releaseSigning
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
    implementation("androidx.compose.material3:material3")
    // Wear Material 3 — the real design system for this form factor: ScreenScaffold, Card, Button,
    // ListHeader, TimeText. Not hand-rolled "minimal" surfaces.
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-material:1.6.2")

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
}
