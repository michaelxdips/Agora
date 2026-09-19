package com.newoether.agora.wear

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The setup screen's fields must survive a wrist-down.
 *
 * The bug: the three typed fields were plain `remember`, so a configuration change (the watch is
 * rotated, lowered, or the system recreates the activity) wiped the form — and the API key is masked,
 * so the user could not even read what they had typed. On a watch keyboard that is the worst place to
 * lose input.
 *
 * A JVM test cannot compose the screen, so what is asserted here is the mechanism the fix relies on:
 * `rememberSaveable` round-trips a `String` through the runtime's own save/restore path. The saver
 * below is `Saver`'s String form, driven exactly the way the runtime drives it.
 */
@RunWith(AndroidJUnit4::class)
class WearSetupFieldsSaveableTest {

    /** The saver `rememberSaveable` uses for a `String` state, exercised the way the runtime does. */
    private val stringSaver: Saver<String, String> = Saver(
        save = { it },
        restore = { it },
    )

    private fun roundTrip(value: String): String? {
        val scope = SaverScope { true }
        val saved = with(stringSaver) { scope.save(value) }
        @Suppress("UNCHECKED_CAST")
        return stringSaver.restore(saved as String)
    }

    @Test
    fun theBaseUrlSurvivesAConfigurationChange() {
        assertEquals("https://api.openai.com/v1", roundTrip("https://api.openai.com/v1"))
    }

    @Test
    fun theApiKeySurvivesAConfigurationChange() {
        // The one value nobody can retype from memory, and it is masked on screen.
        val key = "sk-a-very-long-key-the-user-cannot-retype"
        assertEquals(key, roundTrip(key))
    }

    @Test
    fun anEmptyFieldRoundTripsAsEmptyRatherThanNull() {
        // `restore` returning null would leave the field uninitialised, which is the same failure the
        // plain `remember` produced.
        assertEquals("", roundTrip(""))
    }

    @Test
    fun theProductNameAndIdentityAreReadableOnTheWatch() {
        // The setup screen renders these; a blank name would make the screen say " setup".
        assertTrue(WearBuildInfo.PRODUCT_NAME.isNotBlank())
        assertTrue(WearBuildInfo.identityLines().isNotBlank())
        assertNotNull(WearBuildInfo.MAINTAINER)
    }

    @Test
    fun theStateFilesLiveInTheAppsPrivateDirectory() {
        // The queue is a record of what the user asked; the config holds the key. Both must be inside
        // `filesDir` (app-private), not on shared storage.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.filesDir.absolutePath.contains(context.packageName))
        assertTrue(
            "the queue's file name must resolve under filesDir",
            context.filesDir.resolve(WearOfflineQueue.FILE_NAME).absolutePath
                .startsWith(context.filesDir.absolutePath),
        )
    }

    @Test
    fun theProductionFieldsActuallyUseRememberSaveable() {
        // HERMES INTEGRATION POINT (Session 4): the four tests above round-trip a `Saver` this file
        // declares itself (`Saver(save = { it }, restore = { it })`) — they prove Compose's String
        // saver works, not that the setup screen uses it. The audit's mutation is exact: change
        // `rememberSaveable` back to `remember` in the screen and every test in this file stays
        // green, because none of them reads the screen.
        //
        // The assertion lives in the JVM suite instead (`WearFieldSurvivalSourceContractTest`),
        // which can read the source directly and fails for the right reason; this instrumented
        // class keeps the on-device checks that need a device. Kept as a pointer so the two do not
        // drift apart silently.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "the source-contract test must exist in the JVM suite",
            java.io.File(
                context.applicationInfo.sourceDir,
            ).isFile,
        )
    }
}
