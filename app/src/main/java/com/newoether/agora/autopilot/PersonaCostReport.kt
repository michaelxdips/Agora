package com.newoether.agora.autopilot

import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant

/**
 * Phase 6 P6 — the persona block's **input**-token cost, measured with the app's own estimator.
 *
 * The upstream Caveman project publishes an HONEST-NUMBERS doctrine: on already-terse work a persona
 * can cost more than it saves. The only way to honour that here is to measure the block's input cost
 * with the same estimator every context-budget decision in this app already uses
 * ([ContextTokenEstimator]) instead of quoting a percentage from a README.
 *
 * Output-side savings cannot be measured without a live provider key (HS2, deferred by the owner), so
 * this class deliberately reports **input cost only** and says so. Reporting an output-token saving
 * that was never measured would be exactly the dishonesty the doctrine exists to prevent.
 */
object PersonaCostReport {

    /**
     * Input tokens the persona block adds to every request while enabled, per the app's estimator.
     *
     * @param id which persona the block belongs to. The marker is part of the measured bytes, so using
     *   one persona's marker to measure another's body reports a number that is off by the marker
     *   difference — small, but this class exists to publish honest numbers, and an estimate that is
     *   quietly about a different block is exactly what it must not do.
     */
    fun inputTokenCost(id: String, blockBody: String): Int =
        ContextTokenEstimator.estimate(
            listOf(
                ChatMessage(
                    text = PersonaStore.block(id, blockBody),
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS,
                )
            )
        )

    /** Combined input cost of both personas as they would actually be injected. */
    fun combinedInputTokenCost(bodies: Map<String, String>): Int =
        ContextTokenEstimator.estimate(
            PersonaStore.IDS.mapNotNull { id ->
                bodies[id]?.takeIf { it.isNotBlank() }?.let { body ->
                    ChatMessage(
                        text = PersonaStore.block(id, body),
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                    )
                }
            }
        )

    /** One-line honest summary for STATUS.md / AUDIT_REPORT.md. */
    fun summary(bodies: Map<String, String>): String {
        val perPersona = PersonaStore.IDS.joinToString(", ") { id ->
            val body = bodies[id].orEmpty()
            "$id=${inputTokenCost(id, body)}"
        }
        return "persona input cost (app estimator, per request): $perPersona; " +
            "combined=${combinedInputTokenCost(bodies)}"
    }
}
