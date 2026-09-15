package com.newoether.agora.autopilot

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Autopilot's own Room database.
 *
 * N2: Agora's Room database is never extended. This database is separate, version 1, and may use
 * destructive migration until the fork's first tagged release.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
@Entity(tableName = "adaptation_log")
data class AdaptationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    /** Which Agora store owns [targetFile]: [STORE_MEMORY], [STORE_SKILL] or [STORE_ACTIVE_MEMORY]. */
    val store: String,
    /** File name inside the store (e.g. `user-preferences.md`). */
    val targetFile: String,
    /** Exact prior content, or null when the file did not exist before this adaptation. */
    val beforeSnapshot: String?,
    /** Exact content written by this adaptation; null for a delete. */
    val afterSnapshot: String?,
    val reason: String,
    val sourceSessionId: String?,
    val status: String,
    val feedbackFlags: Int = 0,
) {
    init {
        require(store == STORE_MEMORY || store == STORE_SKILL || store == STORE_ACTIVE_MEMORY)
        require(status in ALL_STATUSES)
        require(feedbackFlags >= 0)
        require(beforeSnapshot != afterSnapshot) { "adaptation did not change $targetFile" }
    }

    companion object {
        const val STORE_MEMORY = "memory"
        const val STORE_SKILL = "skill"

        /**
         * The singleton `files/active_memory.md`, which Agora's own Memory UI shows as a file.
         *
         * It gets its own store id because it does **not** live under `memory_db/`: `MemoryManager`
         * resolves `memory_db/<name>.md` for every other name, so journaling a persona write as
         * [STORE_MEMORY] with `targetFile = "active_memory.md"` made Undo resolve to a *different*
         * file — it either failed silently (the file did not exist) or overwrote the user's own
         * `memory_db/active_memory.md` with a persona snapshot. Persona writes are the only writer of
         * this store, and [MemoryApplier] routes it through `getActiveMemory`/`updateActiveMemory`.
         */
        const val STORE_ACTIVE_MEMORY = "active_memory"

        const val STATUS_APPLIED = "applied"
        const val STATUS_AUTO_ROLLED_BACK = "auto_rolled_back"
        const val STATUS_USER_ROLLED_BACK = "user_rolled_back"
        const val STATUS_NEEDS_REVISION = "needs_revision"

        val ALL_STATUSES = setOf(
            STATUS_APPLIED,
            STATUS_AUTO_ROLLED_BACK,
            STATUS_USER_ROLLED_BACK,
            STATUS_NEEDS_REVISION,
        )
    }
}

/**
 * Records that one adaptation's content was actually injected into a session's context.
 *
 * The circuit breaker counts corrections only in sessions that saw the adapted fact, so this row is
 * the gate for `feedbackFlags++`. Same autopilot database (N2).
 */
@Entity(tableName = "adaptation_injection")
data class AdaptationInjection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val adaptationId: Long,
    val sessionId: String,
    val injectedAt: Long,
)

@Dao
interface AdaptationLogDao {
    @Insert
    suspend fun insert(entry: AdaptationEntry): Long

    @Query("SELECT * FROM adaptation_log WHERE id = :id")
    suspend fun find(id: Long): AdaptationEntry?

    @Query("SELECT * FROM adaptation_log ORDER BY timestamp DESC, id DESC")
    suspend fun all(): List<AdaptationEntry>

    @Query("UPDATE adaptation_log SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String): Int

    @Query("UPDATE adaptation_log SET feedbackFlags = feedbackFlags + 1 WHERE id = :id")
    suspend fun incrementFeedback(id: Long): Int

    @Query("SELECT * FROM adaptation_log WHERE targetFile = :file AND store = :store ORDER BY timestamp DESC, id DESC")
    suspend fun historyFor(file: String, store: String): List<AdaptationEntry>

    @Query("DELETE FROM adaptation_log WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM adaptation_log WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    /**
     * Adaptations the **autopilot itself** produced today, which is what the daily cap limits.
     *
     * Persona toggles live in this table too, and they are the user's own action, not the autopilot's
     * autonomous work. Counting them meant five persona flips spent the whole day's budget and stopped
     * reflection until midnight — the cap punished the user for using the UI. The persona channel has
     * its own store id, so the exclusion is exact rather than a guess.
     */
    @Query("SELECT COUNT(*) FROM adaptation_log WHERE timestamp >= :since AND store != :excludedStore")
    suspend fun countAutopilotSince(since: Long, excludedStore: String): Int

    /** Retention (Phase 4): the ids to drop once a file exceeds its version budget. */
    @Query(
        "SELECT id FROM adaptation_log WHERE targetFile = :file AND store = :store " +
            "ORDER BY timestamp DESC, id DESC LIMIT -1 OFFSET :keep"
    )
    suspend fun idsBeyondRetention(file: String, store: String, keep: Int): List<Long>

    @Query("SELECT id FROM adaptation_log WHERE timestamp < :cutoff")
    suspend fun idsOlderThan(cutoff: Long): List<Long>

    @Insert
    suspend fun insertInjection(injection: AdaptationInjection): Long

    @Query("SELECT * FROM adaptation_injection WHERE adaptationId = :adaptationId")
    suspend fun injectionsFor(adaptationId: Long): List<AdaptationInjection>

    @Query("DELETE FROM adaptation_injection WHERE adaptationId IN (:ids)")
    suspend fun deleteInjections(ids: List<Long>): Int
}

@Database(
    entities = [AdaptationEntry::class, AdaptationInjection::class],
    version = 1,
    exportSchema = false,
)
abstract class AdaptationDatabase : RoomDatabase() {
    abstract fun adaptationLogDao(): AdaptationLogDao

    companion object {
        const val DB_NAME = "hermes_autopilot.db"

        @Volatile
        private var instance: AdaptationDatabase? = null

        /** Process-wide singleton; destructive migration is allowed until the first tagged release. */
        fun get(context: Context): AdaptationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AdaptationDatabase::class.java,
                    DB_NAME,
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
    }
}
