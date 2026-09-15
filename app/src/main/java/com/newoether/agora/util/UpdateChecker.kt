package com.newoether.agora.util

import com.newoether.agora.autopilot.HermesBuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val url: String,
    val body: String
)

/**
 * Checks for a newer release of **this fork**.
 *
 * HERMES INTEGRATION POINT: this upstream file is modified by the fork — see
 * `UPSTREAM_TOUCHPOINTS.md` entry #10 for the budget and the reason.
 *
 * ## The defect this file used to have
 *
 * It queried `api.github.com/repos/newo-ether/Agora/releases/latest` — upstream's releases, not the
 * fork's. On top of that, the fork's own `versionName` is `3.0.0-hermesx` while upstream's newest tag is
 * `v2.1.0`, so the comparison returned "up to date" and the wrong-repository half was never even
 * reachable in practice. Both halves were wrong in the same direction: a fork that cannot be told about
 * its own releases, and would have offered the *upstream* APK if it ever could.
 *
 * ## What it does now
 *
 * Queries the fork's own repository. A repository with no releases yet (this fork has none) returns
 * `null`, which is the honest answer — "no update available" is true, and the About screen already
 * falls back to its up-to-date message. Creating a release on the fork is what turns the check on;
 * no code change will be needed.
 *
 * `compare` is public so the arithmetic can be tested without a network call. It was private before,
 * and the absence of a test is exactly how the repository defect survived.
 */
object UpdateChecker {

    /**
     * The repository whose releases this build can install.
     *
     * The fork's own: its `applicationId`, its signing identity and its feature set differ from
     * upstream's, so an upstream APK offered as "the update" would be a wrong install rather than a
     * missed one. Derived from [HermesBuildInfo] — the same constant the About screen shows — so the
     * two cannot drift into disagreeing about where this build comes from.
     */
    val RELEASES_REPOSITORY: String = HermesBuildInfo.FORK_REPO

    /** Web URL for the release notes, shown in the update dialog. */
    val RELEASES_URL: String = "https://github.com/$RELEASES_REPOSITORY"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GitHubRelease(
        val tag_name: String,
        val html_url: String,
        val body: String? = null
    )

    /**
     * Check GitHub for a newer release of the fork. Returns [UpdateInfo] if an update is available, or
     * null if the current version is up-to-date, the fork has no releases, or the check fails.
     *
     * A failed check is silent by design (offline is normal, and the About screen says so) but the
     * failure mode is *always* null, never a fabricated "up to date" with a version attached.
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$RELEASES_REPOSITORY/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }

            val body = response.body.string()
            response.close()

            val release = json.decodeFromString<GitHubRelease>(body)
            val latestVersion = release.tag_name.removePrefix("v")

            if (compare(latestVersion, currentVersion) > 0) {
                UpdateInfo(
                    version = latestVersion,
                    url = release.html_url,
                    body = release.body.orEmpty()
                )
            } else {
                null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a failed check: swallowing it would report "up to date" for work
            // that was abandoned, and would keep the coroutine alive past its cancellation.
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compare two version strings (e.g. "1.0.10" vs "1.0.9").
     *
     * Returns positive if [a] > [b], negative if [a] < [b], 0 if equal.
     *
     * Segments are compared numerically where both are numeric and lexically otherwise, so a fork
     * suffix (`3.0.0-hermesx`) or a release candidate (`1.2.3-rc1`) is ordered rather than silently
     * collapsed to `0`. The old implementation used `toIntOrNull() ?: 0`, which made every non-numeric
     * segment equal to a zero and therefore invisible to the comparison — the kind of arithmetic that
     * looks harmless until a version depends on it.
     *
     * Public because it is pure logic with no Android dependency and no test existed for it.
     */
    fun compare(a: String, b: String): Int {
        val partsA = a.split(".")
        val partsB = b.split(".")
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { "0" }
            val vb = partsB.getOrElse(i) { "0" }
            val na = va.toIntOrNull()
            val nb = vb.toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na - nb
                // A numeric segment outranks a non-numeric one: "3.0.1" is newer than "3.0.0-rc".
                na != null -> 1
                nb != null -> -1
                else -> va.compareTo(vb)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
