package com.newoether.agora.wear

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the things a JVM test cannot reach.
 *
 * The audit's finding was blunt: **`wear/src/androidTest` did not exist**, so the watch module's
 * platform-facing claims — the keystore round-trip, the listener services being resolvable from the
 * manifest, the release build keeping the entry points R8 must not rename — were asserted by nothing
 * at all. Every one of them fails silently when it breaks: a keystore that cannot decrypt looks like
 * "not configured", and a listener the manifest cannot resolve looks like "the phone never pushed".
 *
 * These tests need a device or an emulator (`connectedDebugAndroidTest`). They are the reason the
 * instrumentation source set exists; see `STATUS.md` for whether they have been run on hardware yet.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
@RunWith(AndroidJUnit4::class)
class WearPlatformInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var store: WearConfigStore

    @Before
    fun setUp() {
        store = WearConfigStore(context)
        context.filesDir.resolve(WearConfigStore.FILE_NAME).delete()
    }

    // ---------- the keystore round-trip ----------

    @Test
    fun aConfigSurvivesTheKeystoreRoundTrip() {
        // The whole point of `WearCrypto`: the key is in the Android keystore, not in the file. If the
        // keystore entry cannot be created or the GCM parameters do not round-trip on this device, the
        // user sees "not configured" on a watch that *is* configured — and no JVM test can see it,
        // because the keystore does not exist off-device.
        val config = WearConfig(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-instrumented-not-a-real-key",
            model = "gpt-4o-mini",
        )

        store.write(config)
        val read = store.read()

        assertNotNull("the config did not survive the keystore round-trip", read)
        assertEquals(config.baseUrl, read!!.baseUrl)
        assertEquals(config.apiKey, read.apiKey)
        assertEquals(config.model, read.model)
        assertEquals(WearConfig.CURRENT_VERSION, read.version)
    }

    @Test
    fun theConfigFileIsNotReadableAsPlaintext() {
        // A file that contains the key in the clear is a key on a lost watch. Asserted on the bytes,
        // because `store.read()` succeeding is exactly what a plaintext file would also do.
        val config = WearConfig(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-must-not-appear-in-the-file",
            model = "gpt-4o-mini",
        )
        store.write(config)

        val bytes = context.filesDir.resolve(WearConfigStore.FILE_NAME).readBytes()

        assertTrue("the config file is empty", bytes.size > 12)
        val asText = String(bytes, Charsets.ISO_8859_1)
        assertTrue("the API key is in the file in the clear", !asText.contains("sk-must-not-appear"))
        assertTrue("the base URL is in the file in the clear", !asText.contains("api.openai.com"))
    }

    @Test
    fun aTruncatedConfigFileIsRejectedRatherThanPartiallyDecoded() {
        // A write killed halfway (the platform kills a watch without warning) must not produce a config
        // the app half-believes. `WearCrypto.decrypt` returns null and `read()` returns null, which the
        // setup gate reads as "not configured" — the honest state.
        val config = WearConfig(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-x",
            model = "m",
        )
        store.write(config)
        val file = context.filesDir.resolve(WearConfigStore.FILE_NAME)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))

        assertEquals(null, store.read())
    }

    // ---------- the manifest's listener services ----------

    @Test
    fun everyListenerServiceResolvesFromTheManifest() {
        // The platform instantiates these by name. A typo, a missing `<service>` block, or an R8 rule
        // that renames the class means the push never arrives and nothing on either screen says why.
        val packageManager = context.packageManager
        listOf(
            "com.newoether.agora.wear.ConfigListenerService",
            "com.newoether.agora.wear.MemoryListenerService",
            "com.newoether.agora.wear.PairingAckListenerService",
        ).forEach { name ->
            val resolved = runCatching {
                packageManager.getServiceInfo(android.content.ComponentName(context.packageName, name), 0)
            }.getOrNull()
            assertNotNull("$name is not declared in the manifest", resolved)
            assertTrue("$name must be exported for the Data Layer to reach it", resolved!!.exported)
        }
    }

    @Test
    fun theLauncherActivityIsDeclaredAndExported() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val resolved = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }

        assertTrue(
            "no launcher activity for ${context.packageName}: $resolved",
            resolved.contains("com.newoether.agora.wear.WearMainActivity"),
        )
    }

    @Test
    fun theStandaloneMetadataIsPresent() {
        // `com.google.android.wearable.standalone` is what tells the Play Store (and the platform) that
        // this watch app works without a phone. Without it the watch build is treated as a companion app.
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        val value = applicationInfo.metaData?.get("com.google.android.wearable.standalone")

        assertEquals("the watch app must declare itself standalone", true, value)
    }

    // ---------- the encrypted memory cache ----------

    @Test
    fun theMemoryCacheSurvivesAWriteAndClearsOnEmpty() {
        // The cache is written on every push and read on every question. An empty snapshot must clear
        // it: a user who deleted their memory must not keep being answered from it.
        val cache = WearMemoryCache(context)

        cache.write("- user lives in Pemalang", 1_000L)
        assertEquals("- user lives in Pemalang", cache.read())

        cache.write("", 2_000L)
        assertEquals("an empty snapshot must clear the cache", "", cache.read())
    }
}
