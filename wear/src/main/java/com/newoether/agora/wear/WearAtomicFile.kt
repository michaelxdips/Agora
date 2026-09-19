package com.newoether.agora.wear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
     * Replaces [target] with [bytes], durably.
     *
     * @return true when the destination now holds [bytes].
     */
    fun write(target: File, bytes: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        java.io.FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (temp.renameTo(target)) return@runCatching true
        // A rename can fail on a filesystem that refuses to replace an existing name. The copy is
        // the fallback, and it is the *only* case where the destination is written in place: the
        // temp file already holds the fsynced bytes, so the window is as small as this filesystem
        // allows rather than "we never tried to be atomic".
        temp.copyTo(target, overwrite = true)
        temp.delete()
        true
    }.getOrElse { error ->
        WearLog.w("atomic write failed for ${target.name}: ${error.javaClass.simpleName}")
        false
    }

    fun write(target: File, text: String): Boolean = write(target, text.toByteArray(Charsets.UTF_8))
}
