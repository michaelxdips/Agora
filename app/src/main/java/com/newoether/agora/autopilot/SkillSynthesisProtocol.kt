package com.newoether.agora.autopilot

import kotlinx.serialization.Serializable

/**
 * A synthesized skill draft: name, trigger description, generalized steps, pitfalls.
 *
 * Phase 5 writes it through `SkillManager` (the only legal skill write path, N4) with the same
 * snapshot-before-write and undo guarantees as memory (see [MemoryApplier]).
 */
@Serializable
data class SkillDraft(
    val name: String,
    val trigger: String,
    val steps: List<String> = emptyList(),
    val pitfalls: List<String> = emptyList(),
) {
    fun isValid(): Boolean = name.isNotBlank() && trigger.isNotBlank() && steps.isNotEmpty()

    /**
     * Renders the draft as the Markdown body stored in `skill_db/<name>.md`.
     *
     * Generalized, not a transcript: the steps and pitfalls are the reusable part.
     */
    fun toMarkdown(): String = buildString {
        appendLine("# $name")
        appendLine()
        appendLine("## When to use")
        appendLine(trigger.trim())
        appendLine()
        appendLine("## Steps")
        steps.filter { it.isNotBlank() }.forEachIndexed { index, step ->
            appendLine("${index + 1}. ${step.trim()}")
        }
        if (pitfalls.isNotEmpty()) {
            appendLine()
            appendLine("## Pitfalls")
            pitfalls.filter { it.isNotBlank() }.forEach { pitfall ->
                appendLine("- ${pitfall.trim()}")
            }
        }
        appendLine()
        appendLine(PROVENANCE_TAG)
    }
}

object SkillSynthesisProtocol {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /** The synthesis prompt: generalize, never transcribe. */
    fun synthesisPrompt(transcript: String, existingSkills: List<String>): String = buildString {
        appendLine("You turn one successful agent session into a reusable Skill.")
        appendLine()
        appendLine("Rules:")
        appendLine("1. Generalize. The Skill must work for a different user request in the same class")
        appendLine("   of task — never transcribe this session's specific values, names, or paths.")
        appendLine("2. `steps` are the ordered actions that produced the successful outcome.")
        appendLine("3. `pitfalls` are the mistakes that were corrected along the way, phrased as warnings.")
        appendLine("4. `trigger` is one sentence describing when a future agent should read this Skill.")
        appendLine("5. If the session does not describe a reusable procedure, reply {\"skip\":true}.")
        appendLine()
        appendLine("Existing skills: ${existingSkills.joinToString(", ").ifBlank { "(none)" }}")
        appendLine()
        appendLine("Reply with ONLY this JSON object:")
        appendLine("""{"name":"kebab-case-name","trigger":"...","steps":["..."],"pitfalls":["..."]}""")
        appendLine()
        appendLine("Session transcript:")
        appendLine(transcript)
    }

    /** Parses a synthesis reply; null means "nothing reusable", and the caller must not write. */
    fun parse(reply: String): SkillDraft? {
        val body = stripFence(reply.trim())
        if (body.isEmpty() || body.contains("\"skip\"")) return null
        val element = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            // No CancellationException branch: `parseToJsonElement` / `decodeFromJsonElement` are
            // synchronous and cannot suspend, so cancellation cannot arrive here as an exception.
            // A rethrow branch would be dead code pretending to be care.
            return null
        }
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        val draft = try {
            json.decodeFromJsonElement(SkillDraft.serializer(), obj)
        } catch (_: Exception) {
            // No CancellationException branch: `parseToJsonElement` / `decodeFromJsonElement` are
            // synchronous and cannot suspend, so cancellation cannot arrive here as an exception.
            // A rethrow branch would be dead code pretending to be care.
            return null
        }
        return draft.takeIf { it.isValid() }
    }

    private fun stripFence(reply: String): String {
        if (!reply.startsWith("```")) return reply
        val firstNewline = reply.indexOf('\n')
        if (firstNewline < 0) return reply
        val closing = reply.lastIndexOf("```")
        return if (closing <= firstNewline) reply.substring(firstNewline + 1)
        else reply.substring(firstNewline + 1, closing)
    }
}
