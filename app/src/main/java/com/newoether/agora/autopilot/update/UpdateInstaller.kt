package com.newoether.agora.autopilot.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.autopilot.AutopilotNotifier
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.UpdateChecker
import com.newoether.agora.util.UpdateInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a fork release APK and installs it through a [PackageInstaller] session.
 *
 * Why this class exists: the in-app update check ([UpdateChecker]) ends at a dialog whose
 * "View release" button opens a browser. The user then downloads the APK by hand, finds it in
 * Downloads, taps it, and confirms an "unknown app" install — four manual steps, each a reason
 * to stay on an old build. This closes the loop inside the app: download → session install →
 * system confirmation, with progress on the existing autopilot channel.
 *
 * Why [PackageInstaller] and not `ACTION_VIEW` + `FileProvider`: a view intent needs
 * `REQUEST_INSTALL_PACKAGES` on API 26+ to install an APK from another app's file, which is a
 * Play-policy red flag for a client that is not a store. A `PackageInstaller` session is the
 * platform's own install path — the system still shows its confirmation UI, so nothing is
 * silent.
 *
 * Correction (Session 5 audit): the earlier text here claimed "no extra permission is declared
 * and no manifest line is touched". That was wrong — Google's documented requirement for
 * `PackageInstaller` on API 26+ **is** `REQUEST_INSTALL_PACKAGES`, and without it the platform
 * never treats this app as a trusted installer, so the confirmation intent is never offered and
 * the flow stalls after the download. The permission is declared in the **fdroid flavor
 * manifest only** (`app/src/fdroid/AndroidManifest.xml`); the play flavor never carries it,
 * because Play builds must not self-update outside Play.
 *
 * Policy note: this path runs only on builds whose update channel is the fork's own GitHub
 * Releases (fdroid flavor). Play builds must update through Play; the gate is enforced at the
 * offer ([UpdateChannel.isForkReleaseChannel] in `UpdateCheckWorker`) and again at the download
 * (`UpdateDownloadWorker`), so a stored offer cannot smuggle a sideload onto a Play build.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object UpdateInstaller {

    private const val TAG = "HermesUpdate"

    /** APKs are staged here, inside the app's own cache — never shared storage. */
    private const val STAGED_APK_NAME = "hermes-update.apk"

    /** Upper bound on one download; releases are ~50 MB, this is 4x headroom. */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    /** Reasons [installStaged] refuses to run, for the UI to explain instead of failing blind. */
    enum class GateReason { NOT_A_FORK_RELEASE_BUILD, NO_UPDATE_INFO, DOWNLOAD_FAILED, SESSION_FAILED }

    /**
     * Which release asset is this build allowed to install.
     *
     * The GitHub `releases/latest` payload names only the tag page ([UpdateInfo.url]); the APK
     * itself lives at `releases/download/<tag>/<asset>`. The asset name is flavor-fixed: the
     * fdroid release job uploads `app-fdroid-release.apk` (see `.github/workflows/build.yml`).
     */
    fun apkDownloadUrl(info: UpdateInfo, assetName: String = "app-fdroid-release.apk"): String {
        val tag = "v" + info.version.substringBefore('-')
        val base = "https://github.com/${UpdateChecker.RELEASES_REPOSITORY}/releases/download/$tag"
        return "$base/$assetName"
    }

    /**
     * Downloads [url] to the app cache with a byte cap. Returns the staged file, or null with
     * the reason logged. Runs on the caller's thread — call from IO.
     */
    fun downloadToCache(context: Context, url: String, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File? {
        val out = File(context.cacheDir, STAGED_APK_NAME)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream")
                connect()
            }
            if (connection.responseCode !in 200..299) {
                DebugLog.w(TAG, "update download HTTP ${connection.responseCode}")
                return null
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            if (total > MAX_APK_BYTES) {
                DebugLog.w(TAG, "update asset too large: $total")
                return null
            }
            val tmp = File(context.cacheDir, "$STAGED_APK_NAME.tmp")
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        downloaded += n
                        if (downloaded > MAX_APK_BYTES) {
                            tmp.delete()
                            DebugLog.w(TAG, "update download exceeded cap")
                            return null
                        }
                        output.write(buf, 0, n)
                        onProgress(downloaded, total)
                    }
                }
            }
            if (!tmp.renameTo(out)) {
                tmp.delete()
                DebugLog.w(TAG, "update stage rename failed")
                return null
            }
            return out
        } catch (error: IOException) {
            DebugLog.w(TAG, "update download failed: ${error.javaClass.simpleName}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Streams a staged APK into a [PackageInstaller] session and commits it. The system shows
     * its own install confirmation — this never installs silently.
     *
     * HERMES INTEGRATION POINT (Session 5 audit): the commit used to hand the system a
     * `PendingIntent` targeting `MainActivity`, and nothing in the app ever read
     * `PackageInstaller.EXTRA_STATUS`. That is the documented contract on API 26+: a normal app
     * installing an update gets `STATUS_PENDING_USER_ACTION`, and **the app must launch the
     * confirmation activity itself** (`Intent.EXTRA_INTENT`). The system does not show it for us.
     * With a bare activity PendingIntent the flow stalled after a ~50 MB download and the
     * confirmation never appeared. The receiver below is that missing half: it launches the
     * system's confirmation intent, and reports a real outcome.
     *
     * @return `null` when the commit was handed to the system (confirmation pending or complete);
     *   a [GateReason] when the session could not even be committed.
     */
    fun installStaged(context: Context, apk: File): GateReason? {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            sessionId = installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
            )
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("hermes-update", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, UpdateInstallResultReceiver::class.java)
                    .setAction(UpdateInstallResultReceiver.ACTION_INSTALL_RESULT)
                    .putExtra(UpdateInstallResultReceiver.EXTRA_VERSION_FOR_UI, currentVersionOrEmpty(context))
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val statusReceiver = PendingIntent.getBroadcast(context, 4201, intent, flags)
                session.commit(statusReceiver.intentSender)
            }
            return null
        } catch (error: Exception) {
            DebugLog.w(TAG, "update install session failed: ${error.javaClass.simpleName}")
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            return GateReason.SESSION_FAILED
        }
    }

    private fun currentVersionOrEmpty(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

    /** Posts download/install progress on the existing autopilot channel (no new channel). */
    fun notifyProgress(context: Context, title: String, text: String, progress: Int = -1) {
        if (!AutopilotNotifier.canNotify(context)) return
        // See UpdateDownloadAction.notifyOffer: the channel must exist before anything is posted
        // on it, or the system drops the notification (Session 5 audit).
        AutopilotNotifier.ensureChannelForUpdates(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 4202, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, AutopilotNotifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(progress < 0)
            .setOngoing(progress in 0..99)
        if (progress in 0..100) builder.setProgress(100, progress, false)
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_ID, builder.build())
        }
    }

    fun dismissProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_UPDATE_ID) }
    }

    private const val NOTIFICATION_UPDATE_ID = 4102
}
