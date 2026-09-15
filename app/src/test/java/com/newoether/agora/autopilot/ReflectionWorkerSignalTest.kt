package com.newoether.agora.autopilot

import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two signals the autopilot's Phase 4/5 wiring reads out of the durable transcript.
 *
 * Both were previously unavailable because nothing called `CircuitBreaker.recordCorrection` or
 * `SkillSynthesizer.synthesize` outside their own tests — the features were shipped as code and never
 * as behaviour (audit A-008/A-009). These tests pin the derivation, so the wiring has something to be
 * right or wrong about.
 */
class ReflectionWorkerSignalTest {

    private var clock = 0L

    private fun row(
        id: String,
        participant: Participant,
        parentId: String? = null,
        status: MessageStatus = MessageStatus.SUCCESS,
    ): MessageContextTopology {
        clock += 1
        return MessageContextTopology(
            id = id,
            conversationId = "c1",
            parentId = parentId,
            status = status,
            participant = participant,
            timestamp = clock,
            modelName = null,
            runId = "run",
            runSequence = clock,
            consumedAtPass = null,
        )
    }

    // ---------- corrections ----------

    @Test
    fun `an answer asked again under the same parent counts the first one as rejected`() {
        val topology = listOf(
            row("u1", Participant.USER),
            row("m1", Participant.MODEL, parentId = "u1"),
            row("m2", Participant.MODEL, parentId = "u1"),   // regenerate
        )

        assertEquals(listOf("m1"), ReflectionWorker.rejectedAnswerIds(topology))
    }

    @Test
    fun `a normal conversation has no rejections`() {
        val topology = listOf(
            row("u1", Participant.USER),
            row("m1", Participant.MODEL, parentId = "u1"),
            row("u2", Participant.USER),
            row("m2", Participant.MODEL, parentId = "u2"),
        )

        assertTrue(ReflectionWorker.rejectedAnswerIds(topology).isEmpty())
    }

    @Test
    fun `three siblings are still one rejected answer, not two`() {
        // The user regenerated twice: there is one answer they moved on from, and the second retry is
        // itself a rejection of the first retry — but counting each sibling would double the flags and
        // roll the fact back on a single bad day.
        val topology = listOf(
            row("u1", Participant.USER),
            row("m1", Participant.MODEL, parentId = "u1"),
            row("m2", Participant.MODEL, parentId = "u1"),
            row("m3", Participant.MODEL, parentId = "u1"),
        )

        assertEquals(listOf("m1"), ReflectionWorker.rejectedAnswerIds(topology))
    }

    @Test
    fun `rows without a parent are never treated as rejections`() {
        val topology = listOf(
            row("m1", Participant.MODEL, parentId = null),
            row("m2", Participant.MODEL, parentId = null),
        )

        assertTrue(ReflectionWorker.rejectedAnswerIds(topology).isEmpty())
    }

    // ---------- tool chains ----------

    @Test
    fun `a run of tool calls is measured as the longest chain`() {
        val topology = listOf(
            row("u1", Participant.USER),
            row("m1", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
            row("m2", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
            row("m3", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
            row("m4", Participant.MODEL),
        )

        assertEquals(3, ReflectionWorker.chainedToolCalls(topology))
    }

    @Test
    fun `isolated tool calls do not add up to a procedure`() {
        val topology = listOf(
            row("m1", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
            row("m2", Participant.MODEL),
            row("m3", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
            row("m4", Participant.MODEL),
        )

        assertEquals(1, ReflectionWorker.chainedToolCalls(topology))
    }

    @Test
    fun `a session with no tool calls has a chain of zero`() {
        assertEquals(
            0,
            ReflectionWorker.chainedToolCalls(
                listOf(row("u1", Participant.USER), row("m1", Participant.MODEL, parentId = "u1")),
            ),
        )
    }

    @Test
    fun `the chain signal and the candidate rule agree`() {
        val topology = listOf(
            row("m1", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
            row("m2", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
            row("m3", Participant.MODEL, status = MessageStatus.TOOL_CALLING),
        )

        val chain = ReflectionWorker.chainedToolCalls(topology)
        assertEquals(SkillCandidateDetector.MIN_CHAINED_TOOL_CALLS, chain)
        assertTrue(
            "a 3-deep tool chain must qualify a session",
            SkillCandidateDetector.isCandidate(chain, userCorrections = 0, succeeded = true),
        )
    }
}
