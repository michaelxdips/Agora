package com.newoether.agora.autopilot.wearsync

import com.newoether.agora.util.DebugLog

/**
 * The watch's core context, derived on the **phone** before the snapshot goes over the Data Layer.
 *
 * Why this exists (O3): the phone used to base64 the entire active-memory snapshot and hand it to the
 * Data Layer, and the watch then truncated it to its own 500-token budget. For a user with a large
 * memory file that meant transferring ~100 KB (base64 adds another 33%) so that the watch could throw
 * most of it away — on a Bluetooth link, to a watch, to save the phone nothing.
 *
 * So the derivation happens once, on the side that has the text, and the payload is what the watch
 * will actually use.
 *
 * **The duplication is deliberate and bounded.** The watch's own [WearCoreContext] keeps its
 * truncation as a safety net (a snapshot can also arrive from an older phone), and this class has to
 * repeat the rule because the two modules have no shared code — exactly the same reason
 * `WearChatClient` duplicates the phone's HTTP call instead of importing it. The two numbers are
 * asserted equal by `CoreContextDerivationTest` on this side and by the wear suite on the other, and
 * both files say so where a reader will see it.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object CoreContextDerivation {

    private const val TAG = "AutopilotWearSync"

    /** Same ceiling as the watch's `WearCoreContext.MAX_TOKENS`, in the same ~4-chars-per-token unit. */
    const val MAX_TOKENS = 500

    /** Same estimator rule the watch uses: ~4 characters per token for prose. */
    private const val CHARS_PER_TOKEN = 4

    /** Same sentence the watch appends, so a truncated context is recognisable on either screen. */
    const val TRUNCATION_NOTE = "[core context truncated to fit the watch budget]"

    /** The character budget the watch enforces. */
    val MAX_CHARS: Int = MAX_TOKENS * CHARS_PER_TOKEN

    /**
     * Truncates [snapshot] to the watch's budget, keeping whole lines from the top.
     *
     * The rule is the watch's, verbatim: keep whole lines while they fit, drop the rest, and say so.
     * A silent truncation would make the watch answer with half the user's context and no way to tell.
     */
    fun derive(snapshot: String): String {
        val cleaned = snapshot.replace("\r\n", "\n").trim()
        if (cleaned.length <= MAX_CHARS) return cleaned
        val noteCost = TRUNCATION_NOTE.length + 1
        val kept = StringBuilder()
        var dropped = false
        cleaned.lineSequence().forEach { line ->
            if (kept.length + line.length + 1 <= MAX_CHARS - noteCost) {
                if (kept.isNotEmpty()) kept.append('\n')
                kept.append(line)
            } else {
                dropped = true
            }
        }
        if (!dropped) return cleaned
        return (kept.toString().trimEnd() + "\n" + TRUNCATION_NOTE).trim()
    }

    /**
     * The payload the Data Layer will carry, for a snapshot.
     *
     * Persona blocks are stripped first: the watch derives its own core context, and persona text
     * would spend the watch's budget on instructions the watch app does not follow.
     */
    fun payloadFor(snapshot: String): String =
        derive(com.newoether.agora.autopilot.PersonaStore.stripAll(snapshot))

    /** What the transfer actually costs, for the log line and for the measurement in the test. */
    fun payloadBytes(payload: String): Int =
        android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.NO_WRAP).length

    /**
     * The same measurement without the Android framework.
     *
     * `android.util.Base64` is not available to a JVM unit test, and the *number* this returns is what
     * the O3 measurement is asserted against — so it is computed here with `java.util.Base64`, which
     * produces the same encoded length. Kept next to [payloadBytes] so the two cannot drift.
     */
    fun payloadBytesForTest(payload: String): Int =
        java.util.Base64.getEncoder().encodeToString(payload.toByteArray()).length

    /** Logs the saving when it is large enough to be worth a line. */
    fun logSaving(originalChars: Int, payload: String) {
        val bytes = payloadBytes(payload)
        if (originalChars > payload.length) {
            DebugLog.d(
                TAG,
                "memory snapshot derived for the watch: $originalChars chars -> ${payload.length} " +
                    "chars ($bytes bytes on the wire)",
            )
        }
    }
}
