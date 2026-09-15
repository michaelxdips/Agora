package com.newoether.agora.wear

/**
 * The watch's system prompt: a **core context** derived from the phone's active memory, bounded to
 * [MAX_TOKENS] so a chatty memory file can never blow the watch's context budget.
 *
 * The truncation rule is deliberately dumb and predictable: keep whole lines from the top, drop the
 * rest, and say so in the text. A silent truncation would make the watch quietly answer with half the
 * user's context and no way to tell — the failure mode this class exists to prevent.
 *
 * Pure Kotlin, no Android: this is the piece the watch unit tests exercise hardest, because a
 * mis-built core context is invisible until a wrong answer arrives.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object WearCoreContext {

    /** Hard ceiling for the derived system prompt, in estimated tokens. */
    const val MAX_TOKENS = 500

    /** Same estimator rule the phone uses: ~4 characters per token for prose. */
    private const val CHARS_PER_TOKEN = 4

    private const val TRUNCATION_NOTE = "[core context truncated to fit the watch budget]"

    /**
     * Builds the system prompt from a raw active-memory snapshot.
     *
     * @param snapshot the phone's active-memory text (persona blocks already stripped by the phone).
     * @return the system prompt, or an empty string when there is nothing to inject.
     */
    fun build(snapshot: String): String {
        val cleaned = clean(snapshot)
        if (cleaned.isEmpty()) return ""
        val budgetChars = MAX_TOKENS * CHARS_PER_TOKEN
        if (cleaned.length <= budgetChars) return cleaned

        val noteCost = TRUNCATION_NOTE.length + 1
        val kept = StringBuilder()
        var dropped = false
        cleaned.lineSequence().forEach { line ->
            if (kept.length + line.length + 1 <= budgetChars - noteCost) {
                if (kept.isNotEmpty()) kept.append('\n')
                kept.append(line)
            } else {
                dropped = true
            }
        }
        if (!dropped) return cleaned
        return (kept.toString().trimEnd() + "\n" + TRUNCATION_NOTE).trim()
    }

    /** Estimated tokens of a built core context, for the debug screen and for assertions. */
    fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** True when [snapshot] had to be cut to fit. */
    fun wasTruncated(snapshot: String): Boolean = build(snapshot).endsWith(TRUNCATION_NOTE)

    /**
     * Normalises the snapshot and removes any persona block.
     *
     * Removing only the marker *lines* is not enough — that was the first implementation, and the
     * test caught it: the rule text between the markers survived and would have been injected into
     * the watch's core context, spending its 500-token budget on instructions the watch app does not
     * follow. Whole blocks go, markers included.
     */
    private fun clean(snapshot: String): String {
        val withoutPersonas = stripPersonaBlocks(snapshot.replace("\r\n", "\n"))
        return withoutPersonas.lines()
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Removes every `<!-- HERMES:PERSONA:X:START --> … :END -->` block, including unterminated ones.
     *
     * Only **whole-line** markers count. Matching the raw substring made a user's note *about* the
     * marker format indistinguishable from a real block, and the unterminated branch then dropped
     * everything from that mention to the end of the snapshot — user memory silently deleted while the
     * watch reported a clean core context (audit A-037/A-040).
     */
    private fun stripPersonaBlocks(text: String): String {
        var result = text
        while (true) {
            val start = markerLineIndex(result, PERSONA_MARKER_PREFIX)
            if (start < 0) return result
            val end = result.indexOf(":END -->", start).takeIf { it >= 0 }
                ?.let { result.indexOf('\n', it).let { l -> if (l < 0) result.length else l } }
            result = if (end == null) {
                result.substring(0, start)
            } else {
                result.substring(0, start) + result.substring(end)
            }
        }
    }

    /** Index of the first line starting with [prefix] at or after 0; -1 when absent. */
    private fun markerLineIndex(text: String, prefix: String): Int {
        var index = text.indexOf(prefix)
        while (index >= 0) {
            val lineStart = text.lastIndexOf('\n', index - 1) + 1
            if (text.substring(lineStart, index).isBlank()) return index
            index = text.indexOf(prefix, index + prefix.length)
        }
        return -1
    }

    private const val PERSONA_MARKER_PREFIX = "<!-- HERMES:PERSONA:"
}
