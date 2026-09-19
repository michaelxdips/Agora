package com.newoether.agora.autopilot.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Session-5 install-handshake contract, pinned at the source level.
 *
 * The bug these tests describe (found in the Session-5 audit): `UpdateInstaller.installStaged`
 * committed the `PackageInstaller` session with a `PendingIntent` targeting `MainActivity`, and
 * **nothing in the app ever read `PackageInstaller.EXTRA_STATUS`**. On API 26+ a normal app gets
 * `STATUS_PENDING_USER_ACTION` and must launch the system's confirmation activity itself
 * (`Intent.EXTRA_INTENT`) — the system does not show it. With a bare activity PendingIntent the
 * flow stalled after a ~50 MB download: the bytes were staged, the confirmation never appeared,
 * and the worker had already reported success.
 *
 * Why source-level assertions instead of a behavioural test: the handshake is delivered by the
 * platform (PackageInstallerService) to a real receiver on a real device, and this module's JVM
 * tests have neither. What *can* be checked without a device is the shape of the contract — that
 * the receiver exists, is registered, reads the status, launches the confirmation, and that the
 * permission the platform requires is declared in the flavor that needs it. The instrumented
 * suite is where a live install would be exercised; this test at least fails if the handshake is
 * removed again.
 */
class UpdateInstallHandshakeContractTest {

    private fun source(relative: String): String {
        val direct = File(relative)
        if (direct.isFile) return direct.readText()
        val fromModule = File("../$relative")
        assertTrue("$relative must be readable from the module dir", fromModule.isFile)
        return fromModule.readText()
    }

    @Test
    fun `the installer commits to a receiver, not a bare activity`() {
        val installer = source("src/main/java/com/newoether/agora/autopilot/update/UpdateInstaller.kt")

        assertTrue(
            "the status callback must go to a receiver that can act on it",
            installer.contains("PendingIntent.getBroadcast"),
        )
        assertTrue(
            "the commit must be handed the receiver's intent sender",
            installer.contains("session.commit(statusReceiver.intentSender)"),
        )
        assertTrue(
            "the old shape (an activity PendingIntent that read nothing) must be gone",
            !installer.contains("PendingIntent.getActivity(context, 4201"),
        )
    }

    @Test
    fun `the result receiver handles the pending-user-action branch by launching the confirmation`() {
        val receiver = source(
            "src/main/java/com/newoether/agora/autopilot/update/UpdateInstallResultReceiver.kt",
        )

        assertTrue(
            "STATUS_PENDING_USER_ACTION must be handled",
            receiver.contains("PackageInstaller.STATUS_PENDING_USER_ACTION"),
        )
        assertTrue(
            "the confirmation activity must be launched from the delivered intent",
            receiver.contains("Intent.EXTRA_INTENT") && receiver.contains("startActivity"),
        )
        assertTrue(
            "success must be reported, not swallowed",
            receiver.contains("PackageInstaller.STATUS_SUCCESS"),
        )
        assertTrue(
            "a failure status must be reported, not swallowed",
            receiver.contains("PackageInstaller.EXTRA_STATUS"),
        )
    }

    @Test
    fun `the result receiver is registered in the manifest`() {
        val manifest = source("src/main/AndroidManifest.xml")

        assertTrue(
            "an unregistered receiver never receives the platform callback",
            manifest.contains("UpdateInstallResultReceiver"),
        )
    }

    @Test
    fun `REQUEST_INSTALL_PACKAGES is declared in the fdroid flavor only`() {
        val fdroid = source("src/fdroid/AndroidManifest.xml")
        val main = source("src/main/AndroidManifest.xml")
        val play = File("src/play/AndroidManifest.xml")
            .takeIf { it.isFile }
            ?: File("../app/src/play/AndroidManifest.xml")

        assertTrue(
            "the flavor that self-updates must hold the permission the platform requires",
            fdroid.contains("android.permission.REQUEST_INSTALL_PACKAGES"),
        )
        assertTrue(
            "the permission must not be in `main` — the play flavor inherits it",
            !main.contains("android.permission.REQUEST_INSTALL_PACKAGES"),
        )
        if (play.isFile) {
            assertTrue(
                "a play build must never be a trusted installer",
                !play.readText().contains("android.permission.REQUEST_INSTALL_PACKAGES"),
            )
        }
    }

    @Test
    fun `both update notifications create their channel before posting`() {
        val action = source("src/main/java/com/newoether/agora/autopilot/update/UpdateDownloadAction.kt")
        val installer = source("src/main/java/com/newoether/agora/autopilot/update/UpdateInstaller.kt")

        assertTrue(
            "the offer notification must ensure its channel exists",
            action.contains("ensureChannelForUpdates"),
        )
        assertTrue(
            "the progress notification must ensure its channel exists",
            installer.contains("ensureChannelForUpdates"),
        )
        assertTrue(
            "the body tap must do something (it says \"Tap to download\")",
            action.contains("setContentIntent(downloadPending)"),
        )
    }

    @Test
    fun `the download worker enforces the flavor gate`() {
        val worker = source("src/main/java/com/newoether/agora/autopilot/update/UpdateDownloadWorker.kt")

        assertTrue(
            "the download half must enforce the same gate as the check half",
            worker.contains("UpdateChannel.isForkReleaseChannel"),
        )
    }

    @Test
    fun `the install-staged contract documents the real permission requirement`() {
        val installer = source("src/main/java/com/newoether/agora/autopilot/update/UpdateInstaller.kt")

        assertTrue(
            "the corrected policy note must name the permission",
            installer.contains("REQUEST_INSTALL_PACKAGES"),
        )
        assertTrue(
            "the dangling gateReason reference must be gone",
            !installer.contains("UpdateInstaller.gateReason"),
        )
    }
}
