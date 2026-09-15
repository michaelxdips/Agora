package com.newoether.agora.autopilot

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The two controls the autopilot obeys: master toggle (default ON) and the daily adaptation cap.
 *
 * An interface because there are two real consumers: the DataStore-backed implementation below and
 * the JVM test double that proves the cap actually gates writes.
 */
interface AutopilotControls {
    suspend fun isEnabled(): Boolean
    suspend fun currentDailyCap(): Int
    suspend fun underDailyCap(log: AdaptationLogDao, sinceMillis: Long): Boolean
}

/**
 * Autopilot's own preferences, in a Hermes-private DataStore file.
 *
 * No upstream settings schema or archive format is extended (N3/N4). Values are read on demand — the
 * worker never blocks the user on them.
 */
private val Context.autopilotDataStore by preferencesDataStore(name = "hermes_autopilot_settings")

class AutopilotSettings(private val context: Context) : AutopilotControls {

    val enabled: Flow<Boolean> = context.autopilotDataStore.data
        .map { it[KEY_ENABLED] ?: true }

    val dailyCap: Flow<Int> = context.autopilotDataStore.data
        .map { it[KEY_DAILY_CAP] ?: DEFAULT_DAILY_CAP }

    override suspend fun isEnabled(): Boolean = enabled.first()

    override suspend fun currentDailyCap(): Int = dailyCap.first()

    override suspend fun underDailyCap(log: AdaptationLogDao, sinceMillis: Long): Boolean {
        val cap = currentDailyCap()
        if (cap <= 0) return false
        // Persona toggles are the user's action, not the autopilot's work — counting them spent the
        // day's budget on five taps and silenced reflection until midnight (audit A-027).
        return log.countAutopilotSince(sinceMillis, AdaptationEntry.STORE_ACTIVE_MEMORY) < cap
    }

    suspend fun setEnabled(value: Boolean) {
        context.autopilotDataStore.edit { it[KEY_ENABLED] = value }
    }

    companion object {
        const val DEFAULT_DAILY_CAP = 5

        /** Start of the current local day, in epoch millis. */
        fun startOfToday(nowMillis: Long): Long =
            java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

        private val KEY_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("enabled")
        private val KEY_DAILY_CAP: Preferences.Key<Int> = intPreferencesKey("daily_cap")
    }
}
