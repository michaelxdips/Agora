package com.newoether.agora.autopilot.update

import com.newoether.agora.util.UpdateInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fork's update channel, proved without a network, a worker, or a device.
 *
 * What is asserted is the part this feature owns: the download URL is derived from the
 * offered version (never from an untrusted extra), the bus delivers exactly what the worker
 * offered, and the version derivation matches the release layout (`v<tag>` page +
 * `download/<tag>/<asset>` file).
 */
class UpdateChannelTest {

    private fun info(version: String) = UpdateInfo(version = version, url = "", body = "")

    @Test
    fun `the download url pins the offered version and the fdroid asset`() {
        val url = UpdateInstaller.apkDownloadUrl(info("3.0.5-hermesx"))
        assertEquals(
            "https://github.com/michaelxdips/Agora/releases/download/v3.0.5/app-fdroid-release.apk",
            url,
        )
    }

    @Test
    fun `the tag strips the fork suffix`() {
        val url = UpdateInstaller.apkDownloadUrl(info("3.1.0"))
        assertTrue(url.contains("/releases/download/v3.1.0/"))
    }

    @Test
    fun `the bus delivers the offered update`() = runTest {
        val offered = info("9.9.9-hermesx")
        val collected = mutableListOf<UpdateInfo>()
        val job = launch { UpdateCheckBus.offers.first().let { collected += it } }
        // A rendezvous needs its subscriber up before the emit, or the emission is dropped —
        // that is the documented contract the store exists to backstop (see UpdateCheckStore).
        // `runCurrent()` lets the launched collector subscribe before the offer below.
        runCurrent()
        assertTrue(UpdateCheckBus.offer(offered))
        job.join()
        assertEquals(listOf(offered), collected)
    }

    @Test
    fun `the byte cap is a sane multiple of a release apk`() {
        // Releases ship ~50 MB; the cap must admit that with headroom but stop a GB-scale
        // surprise. Asserted as a range so a deliberate bump still trips this test.
        assertTrue(UpdateInstaller.MAX_APK_BYTES >= 100L * 1024 * 1024)
        assertTrue(UpdateInstaller.MAX_APK_BYTES <= 500L * 1024 * 1024)
    }
}
