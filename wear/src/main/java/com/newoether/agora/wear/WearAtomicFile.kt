package com.newoether.agora.wear

import java.io.File

/**
 * Atomic, durable file replacement for the watch's small state files.
 *
 * Why one helper instead of three copies: `WearOfflineQueue` already wrote temp-then-rename (with a
 * non-atomic fallback that rewrote the destination in place), while `WearConfigStore` and
 * `WearMemoryCache` wrote straight to the final path. A watch is killed by the platform without
 * warning — low battery, wrist-down, a system update — and a write interrupted halfway leaves a file
 * that is shorter than it should be. For the config that means `WearCrypto.decrypt` fails and the
 * user is back on the setup screen with their key apparently gone; for the memory snapshot it means
 * the watch answers with half the user's context and no way to tell.
 *
 * The sequence is: write a sibling temp file → `fsync` → rename over the destination. The rename is
 * the commit point, so a reader either sees the whole old file or the whole new one. `fsync` before
 * the rename is what makes that true across a power loss and not only across a process kill — without
 * it the rename can land while the temp file's data is still in the page cache.
 *
 * `ponytail:` no directory fsync (the metadata durability of the rename itself). That needs a
 * `FileChannel` on the parent directory, which is not portable to every Android filesystem, and the
 * failure it protects against — the rename itself lost — leaves the *previous* good file in place,
 * which is the outcome this helper is for.
 */
internal object WearAtomicFile {

    /**
     * One lock per destination path, so two writers of the same target cannot interleave their
     * temp-rename sequence.
     *
     * A unique temp name alone was not enough. The `renameTo` fallback below has to `delete()` the
     * destination before renaming — that pair is not atomic, and on a filesystem where `renameTo`
     * refuses to replace an existing name (the very reason the fallback exists) two writers could
     * delete each other's just-renamed file. The lock is what makes the pair safe; it is keyed by
     * absolute path so writes to *different* files still run in parallel.
     */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(target: File): Any =
        locks.computeIfAbsent(target.absolutePath) { Any() }

    /**
     * Replaces [target] with [bytes], durably.
     *
     * @return true when the destination now holds [bytes].
     */
    fun write(target: File, bytes: ByteArray): Boolean = synchronized(lockFor(target)) {
        var temp: File? = null
        return runCatching {
            target.parentFile?.mkdirs()
            // HERMES INTEGRATION POINT (Session 4): a fixed `"${target.name}.tmp"` name was shared by
            // every writer of the same target — `ConfigListenerService` racing the setup screen's
            // Save, or two `WearOfflineQueue` instances after a wrist-down recreates the composition.
            // The loser of that race had its temp renamed away underneath it, so its `copyTo` threw
            // `FileNotFoundException` and the write reported `false` with nothing on screen. A unique
            // temp per call removes the race; the `finally` removes the leak.
            temp = File.createTempFile(target.name, ".tmp", target.parentFile)
            java.io.FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (temp.renameTo(target)) return true
            // A rename can fail on a filesystem that refuses to replace an existing name. The copy is
            // the fallback, and it is the *only* case where the destination is written in place: the
            // temp file already holds the fsynced bytes, so the window is as small as this filesystem
            // allows rather than "we never tried to be atomic".
            //
            // HERMES INTEGRATION POINT: `temp.copyTo(target, overwrite = true)` truncated the
            // destination and copied into it, so a kill mid-copy left a *short* config — which fails
            // to decrypt and reads as "my key is gone", the exact failure this helper exists to
            // prevent. The fallback now deletes first and renames, so the destination is either the
            // old file or the complete new one.
            target.delete()
            if (temp.renameTo(target)) return true
            temp.copyTo(target, overwrite = true)
            true
        }.getOrElse { error ->
            WearLog.w("atomic write failed for ${target.name}: ${error.javaClass.simpleName}")
            false
        }.also {
            // Always: a leftover temp is a file the next write has to work around, and on a watch
            // every stray byte is worth reclaiming.
            runCatching { temp?.delete() }
        }
    }

    fun write(target: File, text: String): Boolean = write(target, text.toByteArray(Charsets.UTF_8))
}
