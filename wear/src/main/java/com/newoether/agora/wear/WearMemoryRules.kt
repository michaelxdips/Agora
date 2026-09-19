package com.newoether.agora.wear

/**
 * What to do with an incoming `/hermes/memory` payload.
 *
 * Extracted from [MemoryListenerService] because the decision is the interesting part and a
 * `WearableListenerService` cannot be unit-tested on the JVM: the three cases below are the whole of
 * it, and one of them was wrong for a long time.
 */
sealed interface MemoryUpdate {
    /** Nothing usable arrived; the previous snapshot stays. */
    data object Keep : MemoryUpdate

    /** A delivered, decodable snapshot — including an **empty** one. */
    data class Apply(val snapshot: String) : MemoryUpdate
}

/**
 * The rule, in one place.
 *
 * The distinction that matters, and that the first version got wrong:
 *
 *  * **the payload key is absent, or the bytes do not decode** — a transport failure. The watch keeps
 *    the last good snapshot, because replacing the user's memory with nothing because a delivery was
 *    corrupt is worse than stale.
 *  * **the key is present and the bytes decode to nothing** — a *successful* delivery of an empty
 *    memory. The user deleted everything on the phone. Ignoring it means the watch keeps answering
 *    from facts the user has erased, in a place they cannot see or edit: memory that outlives its
 *    deletion. It must be applied.
 *
 * The empty case is why the test is on *presence*, not on `isBlank()`: an empty string is what the
 * phone legitimately sends when the active memory is empty (`Base64.encodeToString("")` is `""`), so
 * a blank-check would silently convert "the user deleted everything" into "keep the old snapshot".
 */
object WearMemoryRules {

    fun decide(payloadPresent: Boolean, decoded: ByteArray?): MemoryUpdate {
        if (!payloadPresent) return MemoryUpdate.Keep
        if (decoded == null) return MemoryUpdate.Keep
        return MemoryUpdate.Apply(String(decoded, Charsets.UTF_8))
    }

    /**
     * The snapshot a listener should cache, given whatever the phone sent.
     *
     * Two senders exist: the current phone derives the core context before pushing (O3), and an older
     * phone pushes the raw active memory. Both are handled here, and the rule is **idempotent**: the
     * derived form of an already-derived context is that same context, because truncating a string that
     * fits the budget returns it unchanged. So a watch can apply this unconditionally without needing to
     * know which phone it is paired with.
     */
    fun cacheValue(decoded: ByteArray?): String =
        WearCoreContext.build(String(decoded ?: ByteArray(0), Charsets.UTF_8))
}
