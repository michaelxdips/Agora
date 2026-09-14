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

    defaultConfig {
        // Data Layer requires the phone and watch apps to share an applicationId. Same keystore too,
        // or the phone and watch installs are not the same identity and the Data Layer refuses to
        // pair them.
        applicationId = "com.hermes.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 31
        versionName = "2.1.0"
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

    // Data Layer only: the one-time credential push and the read-only memory snapshot.
    implementation(libs.play.services.wearable)

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
