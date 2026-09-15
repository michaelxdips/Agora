package com.newoether.agora.autopilot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import kotlinx.coroutines.launch

/**
 * Phase 3 controls, shown at the top of Adaptation History: master toggle (default ON) and the
 * daily adaptation cap. Both read/write autopilot's own DataStore, never upstream settings.
 */
@Composable
fun AutopilotControlsSection() {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { AutopilotSettings(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Null until the stored value is known. `collectAsState(initial = true)` made the effect below run
    // once with a *guess* of ON while the real value was OFF: the applier then wrote the persona blocks
    // into active memory and stripped them again in the next pass, spending journal rows and a daily-cap
    // slot on nothing — and a process death inside that window left a persona active with the autopilot
    // switched off (audit A-025).
    val enabled by settings.enabled.collectAsState(initial = null)
    val cap by settings.dailyCap.collectAsState(initial = AutopilotSettings.DEFAULT_DAILY_CAP)
    val repo = remember(context) { PersonaRepository(context) }
    val applier = remember(context) {
        PersonaApplier(
            memoryManager = com.newoether.agora.data.MemoryManager(context),
            log = AdaptationDatabase.get(context).adaptationLogDao(),
        )
    }

    /**
     * Phase 6 P1: master autopilot OFF strips every persona block, and it has to happen **here**,
     * immediately — not at the next app start. The master toggle lives on this screen and the persona
     * toggles live on another, so a deferred strip means a user who just switched the autopilot off
     * still gets persona-shaped replies with nothing on screen to explain why. Verified on device:
     * before this, toggling off left all four markers in `active_memory.md`.
     */
    LaunchedEffect(enabled) {
        val master = enabled ?: return@LaunchedEffect   // no guess: see the note on `collectAsState`
        val bodies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.bodies() }
        // A rejected write (disk full, revoked permission) must not take the settings screen down with
        // it: `reconcile` rethrows on IO failure, and an uncaught throw inside a LaunchedEffect is an
        // app crash on the one screen where the user could have fixed it (audit A-026).
        runCatching {
            applier.reconcile(
                masterEnabled = master,
                enabled = PersonaSettings(context).enabledMap(),
                bodies = bodies,
                reason = if (master) "autopilot enabled" else "autopilot disabled — personas stripped",
            )
        }.onFailure { error ->
            com.newoether.agora.util.DebugLog.w("AutopilotPersonas", "persist failed: ${error.javaClass.simpleName}")
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.hermes_autopilot),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    text = stringResource(R.string.hermes_autopilot_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled == true,
                onCheckedChange = { value -> scope.launch { settings.setEnabled(value) } },
            )
        }
        Text(
            text = stringResource(R.string.hermes_autopilot_cap, cap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
