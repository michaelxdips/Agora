package com.newoether.agora.autopilot

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.newoether.agora.util.DebugLog
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Phase 6 P2/P4 — persona rule text: vendored file first, embedded fallback second.
 *
 * Order matters and is deliberate. The **vendored copy is authoritative**, because P4 says
 * "Reset-to-default restores vendored upstream text" and P2 says the update flow rewrites the
 * vendored copy only after the regression tests pass. The embedded constant is a **fallback for the
 * fetch-less build**, not a second source of truth: if the vendored file is missing or unreadable the
 * user still gets a working persona instead of a silent no-op.
 */
class PersonaRepository(
    private val context: Context,
    private val settings: PersonaSettings = PersonaSettings(context),
) {
    /** The rule text actually shipped for [id]: vendored file, else embedded fallback, else null. */
    fun defaultText(id: String): String? = vendoredText(id) ?: EMBEDDED[id]

    /** Vendored text straight off disk, or null when the file is absent/unreadable. */
    fun vendoredText(id: String): String? = runCatching {
        File(context.filesDir, "$VENDOR_DIR/$id/$FILE_NAME")
            .takeIf { it.isFile }
            ?.readText()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The block body injected into active memory: upstream frontmatter stripped, body verbatim. */
    fun defaultBody(id: String): String? = defaultText(id)?.let(PersonaStore::toBody)?.takeIf { it.isNotBlank() }

    fun bodies(): Map<String, String> =
        PersonaStore.IDS.mapNotNull { id -> defaultBody(id)?.let { id to it } }.toMap()

    /**
     * Copies the vendored files out of the APK assets into app storage on first run.
     *
     * Assets are read-only, and the update flow has to be able to replace the text at runtime, so the
     * authoritative copy has to live somewhere writable. Existing files are never overwritten here —
     * an update goes through the explicit, test-gated [installVendored] path.
     */
    fun seedVendoredFromAssets(assets: android.content.res.AssetManager) {
        val dir = File(context.filesDir, VENDOR_DIR)
        PersonaStore.IDS.forEach { id ->
            val target = File(dir, "$id/$FILE_NAME")
            if (target.isFile && target.readText().isNotBlank()) return@forEach
            runCatching {
                target.parentFile?.mkdirs()
                assets.open("$id/$FILE_NAME").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure {
                DebugLog.w("AutopilotPersonas", "persona vendored file missing in assets: $id")
            }
        }
    }

    /**
     * P2 update flow, last step: write the newly fetched text in and update the lock record.
     *
     * Only ever called after the persona regression tests pass (see `scripts/persona_update.sh`),
     * which is why it is a separate, explicit entry point rather than something the UI can trigger
     * directly.
     */
    fun installVendored(id: String, text: String) {
        val target = File(context.filesDir, "$VENDOR_DIR/$id/$FILE_NAME")
        target.parentFile?.mkdirs()
        target.writeText(text)
    }

    /**
     * The user's edited copy, or null when they never edited this persona.
     *
     * Suspending on purpose: the first version used `runBlocking` so the call site could stay simple,
     * which blocks the calling thread — and the caller is a Compose click handler, i.e. the UI thread.
     * A DataStore read on the main thread is a dropped frame at best and an ANR at worst.
     */
    suspend fun customText(id: String): String? = settings.customText(id)

    /** What the UI should show: the user's edit when present, otherwise the vendored default. */
    suspend fun effectiveText(id: String): String? = customText(id) ?: defaultText(id)

    /** "Reset to default" — drops the user's edit so the vendored upstream text wins again. */
    suspend fun resetToDefault(id: String) = settings.setCustomText(id, null)

    companion object {
        private const val VENDOR_DIR = "personas"
        private const val FILE_NAME = "SKILL.md"

        /**
         * Offline fallback — a faithful, shortened statement of the upstream rule set, used only when
         * the vendored file cannot be read. The vendored file stays authoritative; this is what keeps
         * a persona toggle from being a no-op on a build that shipped without it.
         */
        val EMBEDDED: Map<String, String> = mapOf(
            PersonaStore.ID_CAVEMAN to """
                Respond terse. All technical substance stay, only fluff die.
                Drop articles, filler, pleasantries, hedging. Fragments OK.
                Code, commands, file paths, exact error messages, numbers, security warnings: never compressed.
                Never drop not/never/no/only/except. Never add a word to sound caveman.
                No tool-call narration, no decorative tables, no emoji, no causal arrows.
                Pattern: [thing] [action] [reason]. [next step].
            """.trimIndent(),
            PersonaStore.ID_PONYTAIL to """
                You are a lazy senior developer. Lazy means efficient, not careless.
                Stop at the first rung that holds:
                1. Does this need to exist at all? Speculative need = skip it. (YAGNI)
                2. Already in this codebase? Reuse it.
                3. Stdlib does it? Use it.
                4. Native platform feature covers it? Use it.
                5. Already-installed dependency solves it? Use it.
                6. Can it be one line? One line.
                7. Only then: the minimum code that works.
                Read the touched code and trace the real flow first; the ladder shortens the solution, never the reading.
                Never simplify away input validation at trust boundaries, error handling that prevents data loss,
                security, accessibility, or anything explicitly requested.
            """.trimIndent(),
        )
    }
}

/**
 * Persona preferences: per-persona toggle plus the user's edited text.
 *
 * Same Hermes-private DataStore pattern as [AutopilotSettings] — no upstream settings schema is
 * extended (N3/N4). The toggles here are *intent*; the authoritative state is always the injection
 * channel, which is why the UI reads the store back rather than trusting these flags (P4).
 */
private val Context.personaDataStore by preferencesDataStore(name = "hermes_persona_settings")

class PersonaSettings(private val context: Context) {

    fun enabled(id: String): Flow<Boolean> = context.personaDataStore.data
        .map { it[keyEnabled(id)] ?: false }

    suspend fun isEnabled(id: String): Boolean = enabled(id).first()

    suspend fun setEnabled(id: String, value: Boolean) {
        context.personaDataStore.edit { it[keyEnabled(id)] = value }
    }

    suspend fun enabledMap(): Map<String, Boolean> =
        PersonaStore.IDS.associateWith { isEnabled(it) }

    /**
     * The user's edited text, or null when they never edited this persona.
     *
     * Suspending, not `runBlocking`: the callers are Compose click handlers, and blocking a DataStore
     * read on the UI thread is a dropped frame at best and an ANR at worst.
     */
    suspend fun customText(id: String): String? =
        context.personaDataStore.data.first()[keyCustom(id)]?.takeIf { it.isNotBlank() }

    suspend fun setCustomText(id: String, text: String?) {
        context.personaDataStore.edit { prefs ->
            if (text.isNullOrBlank()) prefs.remove(keyCustom(id)) else prefs[keyCustom(id)] = text
        }
    }

    private fun keyEnabled(id: String): Preferences.Key<Boolean> =
        booleanPreferencesKey("${id}_enabled")

    private fun keyCustom(id: String): Preferences.Key<String> =
        stringPreferencesKey("${id}_custom")
}
