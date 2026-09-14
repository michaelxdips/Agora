package com.newoether.agora.autopilot

import android.content.Context
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Phase 6 P2 — the persona update flow.
 *
 * Fetch the pinned upstream skill file → verify it parses as a persona → run the persona regression
 * tests → only then update the lock and the vendored copy. The order is the whole feature: an update
 * that lands before the tests pass is how a persona silently stops being a persona.
 *
 * Network fetch is delegated to `git`/`curl` via the same shell the agent uses, so the in-app button
 * and the documented agent command cannot drift apart. Failure is always non-fatal and reported as a
 * string the UI shows verbatim — never a crash, never a silent success.
 */
object PersonaUpdater {

    private const val TAG = "AutopilotPersonas"

    /** Update check as the UI runs it: no test gate available in-app, so it only *reports*. */
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val repo = PersonaRepository(context)
        val lock = readLock(context) ?: return@withContext "upstream.lock not readable"
        val lines = mutableListOf<String>()
        lock.forEach { entry ->
            val id = entry["id"] ?: return@forEach
            val ref = entry["ref"] ?: return@forEach
            val repoUrl = entry["repo"] ?: return@forEach
            val sourcePath = entry["source_path"] ?: return@forEach
            val vendored = repo.vendoredText(id) ?: repo.defaultText(id)
            val remote = fetch(repoUrl, ref, sourcePath)
            lines += when {
                remote == null -> "$id: fetch failed (offline?) — kept vendored $ref"
                vendored == null -> "$id: no vendored copy — install with scripts/persona_update.sh"
                sha256(PersonaStore.toBody(remote)) == sha256(PersonaStore.toBody(vendored)) ->
                    "$id: up to date ($ref)"
                else -> "$id: update available at $ref — run scripts/persona_update.sh"
            }
        }
        lines.joinToString("\n").ifBlank { "no personas in upstream.lock" }
    }

    /** Raw upstream file at the pinned ref, or null when the fetch fails. */
    fun fetch(repoUrl: String, ref: String, sourcePath: String): String? = runCatching {
        val url = repoUrl.trimEnd('/')
            .replace("github.com", "raw.githubusercontent.com") + "/$ref/$sourcePath"
        val process = ProcessBuilder("curl", "-sSL", "--max-time", "20", url)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(25, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        text.takeIf { it.isNotBlank() && it.contains("---") }
    }.getOrElse {
        DebugLog.w(TAG, "persona fetch failed: ${it.message}")
        null
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * The lock file as data.
     *
     * Deliberately not a JSON parser: `upstream.lock` is a flat record read by one function, and
     * pulling a schema into the app for it would be the over-engineering the persona being shipped
     * exists to prevent.
     */
    private fun readLock(context: Context): List<Map<String, String>>? = runCatching {
        val candidates = listOf(
            File(context.filesDir, "personas/upstream.lock"),
            File(context.filesDir, "upstream.lock"),
        )
        val text = candidates.firstOrNull { it.isFile }?.readText() ?: return null
        text.split("{").drop(1).mapNotNull { chunk ->
            val body = chunk.substringBefore("}")
            val fields = Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").findAll(body)
                .associate { it.groupValues[1] to it.groupValues[2] }
            fields.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}
