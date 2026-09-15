package com.newoether.agora.autopilot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The reflection contract: one conservative extraction prompt plus a strict JSON ops schema.
 *
 * Pure Kotlin — no Android, no Provider, no Room — so the whole contract is unit-testable on the JVM.
 */

/** Provenance marker appended to every fact this layer writes, so a fact can be traced back. */
const val PROVENANCE_TAG = "<!-- hermes:autopilot -->"

@Serializable
data class ReflectionOp(
    /** `add`, `update`, or `archive`. */
    val op: String,
    /** Target file name inside the memory store, without the `.md` suffix. */
    @SerialName("target_file") val targetFile: String,
    /** Full new file content for this op. */
    val content: String,
    val category: String = "",
    val confidence: Double = 0.0,
    @SerialName("source_quote") val sourceQuote: String = "",
) {
    fun isValid(): Boolean =
        op in OPS && targetFile.isNotBlank() && content.isNotBlank() &&
            confidence >= MIN_CONFIDENCE && sourceQuote.isNotBlank()

    companion object {
        const val OP_ADD = "add"
        const val OP_UPDATE = "update"
        const val OP_ARCHIVE = "archive"
        val OPS = setOf(OP_ADD, OP_UPDATE, OP_ARCHIVE)

        /** Conservative floor: below this the model itself is not confident, so nothing is written. */
        const val MIN_CONFIDENCE = 0.8
    }
}

@Serializable
data class ReflectionPlan(val ops: List<ReflectionOp> = emptyList())

object ReflectionProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The extraction prompt.
     *
     * Deliberately conservative: when in doubt, extract nothing. The model may only return the
     * strict JSON object, never prose.
     */
    fun extractionPrompt(transcript: String, existingFiles: List<String>): String = buildString {
        appendLine("You extract durable user facts from a conversation transcript.")
        appendLine()
        appendLine("Rules:")
        appendLine("1. Extract ONLY high-confidence, durable facts about the user (preferences, identity,")
        appendLine("   recurring constraints, long-lived projects). Never extract one-off requests,")
        appendLine("   transient state, assistant opinions, or anything you must guess.")
        appendLine("2. When in doubt, extract nothing. An empty ops array is the correct answer most of the time.")
        appendLine("3. Every fact must quote the exact transcript text that proves it in `source_quote`.")
        appendLine("4. `confidence` is your honest probability (0.0-1.0) that the fact is durable and correct.")
        appendLine("   Never report a confidence below ${ReflectionOp.MIN_CONFIDENCE} — omit the fact instead.")
        appendLine("5. `content` is the COMPLETE new file body for `target_file`, as Markdown bullet lines.")
        appendLine("   Preserve every existing fact you are not changing.")
        appendLine("6. End every bullet you add or change with the literal marker $PROVENANCE_TAG")
        appendLine()
        appendLine("Existing memory files: ${existingFiles.joinToString(", ").ifBlank { "(none)" }}")
        appendLine()
        appendLine("Reply with ONLY this JSON object, no prose, no code fence:")
        appendLine("""{"ops":[{"op":"add","target_file":"user-preferences","content":"- ...","category":"preference","confidence":0.9,"source_quote":"..."}]}""")
        appendLine()
        appendLine("Transcript:")
        appendLine(transcript)
    }

    /**
     * Parses a model reply into a plan, tolerating a surrounding code fence.
     *
     * Returns null when the reply is not a usable plan — the caller must then skip silently rather
     * than write anything (fail closed). A reply without an `ops` array is NOT an empty plan: it
     * means the model did not answer the contract, so nothing is written.
     */
    fun parse(reply: String): ReflectionPlan? {
        val body = stripFence(reply).trim()
        if (body.isEmpty()) return null
        val element = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            // No CancellationException branch: `parseToJsonElement` / `decodeFromJsonElement` are
            // synchronous and cannot suspend, so cancellation cannot arrive here as an exception.
            // A rethrow branch would be dead code pretending to be care.
            return null
        }
        val obj = element as? JsonObject ?: return null
        val opsArray = obj["ops"] as? JsonArray ?: return null
        val plan = try {
            ReflectionPlan(ops = opsArray.map { json.decodeFromJsonElement(ReflectionOp.serializer(), it) })
        } catch (_: Exception) {
            // No CancellationException branch: `parseToJsonElement` / `decodeFromJsonElement` are
            // synchronous and cannot suspend, so cancellation cannot arrive here as an exception.
            // A rethrow branch would be dead code pretending to be care.
            return null
        }
        return plan.copy(ops = plan.ops.filter { it.isValid() })
    }

    private fun stripFence(reply: String): String {
        val trimmed = reply.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val closing = trimmed.lastIndexOf("```")
        return if (closing <= firstNewline) trimmed.substring(firstNewline + 1)
        else trimmed.substring(firstNewline + 1, closing)
    }
}
