package com.newoether.agora.remote

import com.newoether.agora.model.ToolImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Private disposable files only; native images and ordinary durable tool media are untouched. */
internal class RemoteImageCache(private val directory: File, private val maxBytes: Long = 128L * 1024 * 1024) {
    private val slots = Semaphore(2)
    private val entries = LinkedHashMap<String, ToolImageAttachment>(16, .75f, true)
    private val lock = Any()

    suspend fun load(key: String, fetch: suspend () -> ToolImageAttachment): ToolImageAttachment =
        withContext(Dispatchers.IO) {
            slots.withPermit {
                synchronized(lock) { entries[key]?.takeIf { File(it.path).isFile } }?.let { return@withPermit it }
                val image = fetch()
                // HERMES INTEGRATION POINT: the eviction loop used to run *inside* the `synchronized`
                // block — `File.length()`, `delete()` and `listFiles()` are all blocking syscalls, and
                // every concurrent `load` waits on this monitor, so a cache over its budget made
                // unrelated image loads wait on filesystem I/O they do not need. The candidate list and
                // the bookkeeping stay under the lock (they touch the shared map); the deletions happen
                // after it is released. The image being returned is never a victim either way.
                val victims: List<File>
                val overBudget: Boolean
                synchronized(lock) {
                    entries[key] = image
                    File(image.path).setLastModified(System.currentTimeMillis())
                    val current = File(image.path).absolutePath
                    val recency = entries.values.mapIndexed { index, entry ->
                        File(entry.path).absolutePath to index
                    }.toMap()
                    val files = directory.listFiles().orEmpty()
                        .filter { it.isFile && !it.name.startsWith(".") }
                    overBudget = files.sumOf { it.length() } > maxBytes || files.size > 64
                    // Filesystem timestamp resolution cannot establish access order.
                    victims = files.filter { it.absolutePath != current }.sortedWith(
                        compareBy<File> { recency[it.absolutePath] ?: -1 }
                            .thenBy { it.lastModified() }.thenBy { it.name },
                    )
                    entries.entries.removeAll { !File(it.value.path).isFile }
                }
                if (overBudget) {
                    var bytes = directory.listFiles().orEmpty().sumOf { it.length() }
                    var count = directory.listFiles().orEmpty().count { it.isFile }
                    for (old in victims) {
                        if (bytes <= maxBytes && count <= 64) break
                        val size = old.length()
                        if (old.delete()) { bytes -= size; count-- }
                    }
                }
                image
            }
        }
}
