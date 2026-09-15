package com.newoether.agora.autopilot

/**
 * The persona injection channel — Phase 6 P1.
 *
 * A persona is a **delimited block inside Agora's own active-memory store** (`active_memory.md`),
 * which is the file `GenerationRequestBuilder` already feeds into the resolved system prompt. Riding
 * that native channel is what keeps personas at zero upstream touchpoints: nothing in Agora has to
 * know personas exist.
 *
 * Pure Kotlin (no Android) so every block operation is unit-testable on the JVM, which matters more
 * than usual here: a half-removed block would silently poison every later prompt.
 *
 * Block shape, one per persona:
 * ```
 * <!-- HERMES:PERSONA:CAVEMAN:START -->
 * …rule text…
 * <!-- HERMES:PERSONA:CAVEMAN:END -->
 * ```
 * The markers make find / replace / **remove** deterministic — removal is what makes "toggle OFF =
 * zero trace" verifiable rather than hopeful.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object PersonaStore {
    const val ID_CAVEMAN = "caveman"
    const val ID_PONYTAIL = "ponytail"

    /** Fixed order, so the same enabled set always produces byte-identical active memory. */
    val IDS = listOf(ID_CAVEMAN, ID_PONYTAIL)

    fun label(id: String): String = id.uppercase()

    fun startMarker(id: String): String = "<!-- HERMES:PERSONA:${label(id)}:START -->"

    fun endMarker(id: String): String = "<!-- HERMES:PERSONA:${label(id)}:END -->"

    /** Prefix shared by both markers; used to detect any persona residue. */
    const val MARKER_PREFIX = "<!-- HERMES:PERSONA:"

    /**
     * The exact separator written between the user's memory text and a persona block.
     *
     * It is a named constant because [removeBlock] must consume precisely this string: that is what
     * makes `upsertBlock` and `removeBlock` exact inverses, and therefore what makes "toggle off =
     * zero trace" a property of the code rather than a hope.
     */
    private const val SEPARATOR = "\n\n"

    /** The block as it is written into the store. Body is trimmed so the result is deterministic. */
    fun block(id: String, body: String): String =
        startMarker(id) + "\n" + body.trim() + "\n" + endMarker(id)

    fun hasBlock(text: String, id: String): Boolean =
        markerLineIndex(text, startMarker(id)) >= 0 && markerLineIndex(text, endMarker(id)) >= 0

    /** True when any persona marker survives anywhere in [text] — the "zero trace" check. */
    fun hasAnyMarker(text: String): Boolean = text.contains(MARKER_PREFIX)

    /**
     * Index of the first **line that is exactly** [marker], at or after [from]; -1 when absent.
     *
     * Markers are only recognised when they are a line of their own, because that is the only shape
     * [block] ever writes. Matching the raw substring instead made a user's *note about the marker
     * format* indistinguishable from a real block: mentioning the literal in a memory file made the
     * next reconcile cut everything from that mention to the end of the file (audit A-040).
     */
    private fun markerLineIndex(text: String, marker: String, from: Int = 0): Int {
        var index = text.indexOf(marker, from)
        while (index >= 0) {
            if (lineAt(text, index).trim() == marker) return index
            index = text.indexOf(marker, index + marker.length)
        }
        return -1
    }

    /** Index of the first persona-marker line of any id, at or after [from]; -1 when absent. */
    private fun nextMarkerLineIndex(text: String, from: Int): Int {
        var index = text.indexOf(MARKER_PREFIX, from)
        while (index >= 0) {
            val line = lineAt(text, index).trim()
            if (line.startsWith(MARKER_PREFIX) && line.endsWith("-->")) return index
            index = text.indexOf(MARKER_PREFIX, index + MARKER_PREFIX.length)
        }
        return -1
    }

    /** The full line containing [index]. */
    private fun lineAt(text: String, index: Int): String {
        val start = text.lastIndexOf('\n', index - 1) + 1
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    /**
     * Removes [id]'s block, collapsing only the whitespace the block itself introduced.
     *
     * An **unterminated** block (START marker with no END — the shape a process kill leaves behind) is
     * removed too, up to the next persona marker or the end of the text. Treating it as "not present"
     * would leave the half-block in place and the next write would append a second copy, which is how
     * a persona ends up duplicated in the prompt.
     */
    fun removeBlock(text: String, id: String): String {
        val markerStart = markerLineIndex(text, startMarker(id))
        if (markerStart < 0) return text
        val end = markerLineIndex(text, endMarker(id))
        val cut = if (end < markerStart) {
            // Unterminated: stop at whatever persona marker comes next, else take the rest.
            // `indexOf` returns -1 when absent, and -1 must not win the min.
            nextMarkerLineIndex(text, markerStart + startMarker(id).length)
                .takeIf { it >= 0 } ?: text.length
        } else {
            end + endMarker(id).length
        }
        // The separator [upsertBlock] wrote belongs to the block, so removing it here makes
        // upsert-then-remove the identity on any text. Nothing is trimmed: the user's own bytes —
        // leading blank lines, a trailing newline, indentation — survive untouched. An earlier
        // implementation `trim()`-ed the result and silently rewrote the user's file on toggle-off;
        // the on-device test caught it, the JVM tests had not.
        var start = markerStart
        if (start >= SEPARATOR.length &&
            text.regionMatches(start - SEPARATOR.length, SEPARATOR, 0, SEPARATOR.length)
        ) {
            start -= SEPARATOR.length
        }
        return text.substring(0, start) + text.substring(cut)
    }

    /**
     * Idempotent: removes any previous block for [id], then appends the new one at the end.
     *
     * The exact inverse of [removeBlock] for any input — see [SEPARATOR].
     */
    fun upsertBlock(text: String, id: String, body: String): String {
        val base = removeBlock(text, id)
        if (body.isBlank()) return base
        return if (base.isEmpty()) block(id, body) else base + SEPARATOR + block(id, body)
    }

    /** P5 isolation: strips every persona block, leaving the rest of the text untouched. */
    fun stripAll(text: String): String = IDS.fold(text) { acc, id -> removeBlock(acc, id) }

    /** Read-back for the UI and for status assertions: block bodies currently present. */
    fun blocks(text: String): Map<String, String> = IDS.mapNotNull { id ->
        val start = markerLineIndex(text, startMarker(id))
        val end = markerLineIndex(text, endMarker(id))
        if (start < 0 || end < start) null
        else id to text.substring(start + startMarker(id).length, end).trim()
    }.toMap()

    /**
     * Adapter: upstream skill file → persona block body.
     *
     * The YAML frontmatter carries registry metadata (`name`, `description`, `license`,
     * `argument-hint`) that is not instruction text; shipping it would spend context tokens on
     * nothing. Everything after it is copied **verbatim** — defaults must be faithful to upstream.
     */
    fun toBody(skillMarkdown: String): String {
        val text = skillMarkdown.replace("\r\n", "\n").trim()
        if (!text.startsWith("---")) return text
        val close = text.indexOf("\n---", startIndex = 3)
        if (close < 0) return text
        return text.substring(close + 4).trim()
    }
}
