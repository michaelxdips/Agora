package com.newoether.agora.autopilot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.ui.settings.CollapsingSettingsLazyScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 6 P4 — the Personas section.
 *
 * Two toggles, an editable rule text with Reset-to-default, and a status line whose state is read
 * **back from the injection channel** (Agora's active-memory store), not from the DataStore intent.
 * That distinction is the point: it is what makes "toggle OFF = zero trace" something the UI can
 * prove rather than assert.
 *
 * The "Check for persona updates" action runs the same script the agent runs, so the update flow has
 * one implementation and one test gate.
 */
@Composable
fun SettingsPersonasPage(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) { PersonaRepository(context) }
    val applier = remember(context) {
        PersonaApplier(
            memoryManager = MemoryManager(context),
            log = AdaptationDatabase.get(context).adaptationLogDao(),
        )
    }
    val settings = remember(context) { PersonaSettings(context) }
    val autopilot = remember(context) { AutopilotSettings(context) }
    val scope = rememberCoroutineScope()

    var storeContent by remember { mutableStateOf("") }
    var toggles by remember { mutableStateOf(PersonaStore.IDS.associateWith { false }) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var updateResult by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val state = applier.currentState()
        storeContent = state.storeContent
        toggles = PersonaStore.IDS.associateWith { settings.isEnabled(it) }
    }

    LaunchedEffect(Unit) { refresh() }

    /** One write path for both toggles: recompute the desired channel content, journal, read back. */
    fun setPersona(id: String, value: Boolean) {
        scope.launch {
            settings.setEnabled(id, value)
            val master = autopilot.isEnabled()
            val bodies = withContext(Dispatchers.IO) { repo.bodies() }
            val wanted = settings.enabledMap()
            applier.reconcile(master, wanted, bodies, reason = "persona $id = $value")
            refresh()
        }
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.hermes_personas),
        onBack = onBack,
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.hermes_personas_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PersonaStore.IDS.forEach { id ->
                    val on = toggles[id] == true
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = personaName(id),
                                style = MaterialTheme.typography.bodyLarge
                                    .copy(fontWeight = FontWeight.Medium),
                            )
                            // Status straight from the channel, so a half-written store shows up here.
                            Text(
                                text = stringResource(
                                    if (PersonaStore.hasBlock(storeContent, id)) {
                                        R.string.hermes_persona_status_active
                                    } else {
                                        R.string.hermes_persona_status_inactive
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = on, onCheckedChange = { setPersona(id, it) })
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            editing = id
                            scope.launch {
                                // Suspend rather than block: `effectiveText` reads DataStore.
                                draft = repo.effectiveText(id).orEmpty()
                            }
                        }) {
                            Text(stringResource(R.string.hermes_persona_edit))
                        }
                        TextButton(onClick = {
                            scope.launch {
                                repo.resetToDefault(id)
                                val bodies = withContext(Dispatchers.IO) { repo.bodies() }
                                applier.reconcile(
                                    autopilot.isEnabled(),
                                    settings.enabledMap(),
                                    bodies,
                                    reason = "persona $id reset to default",
                                )
                                refresh()
                            }
                        }) {
                            Text(stringResource(R.string.hermes_persona_reset))
                        }
                    }
                    HorizontalDivider()
                }

                Text(
                    text = stringResource(R.string.hermes_persona_channel, storeContent.length),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(
                        if (PersonaStore.hasAnyMarker(storeContent)) {
                            R.string.hermes_persona_store_dirty
                        } else {
                            R.string.hermes_persona_store_clean
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(onClick = {
                    scope.launch {
                        updateResult = null
                        val result = withContext(Dispatchers.IO) { PersonaUpdater.run(context) }
                        updateResult = result
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.hermes_persona_check_updates))
                }
                updateResult?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    editing?.let { id ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(personaName(id)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    label = { Text(stringResource(R.string.hermes_persona_edit_label)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = id
                    editing = null
                    scope.launch {
                        settings.setCustomText(target, draft)
                        val bodies = withContext(Dispatchers.IO) { repo.bodies() }
                        applier.reconcile(
                            autopilot.isEnabled(),
                            settings.enabledMap(),
                            bodies,
                            reason = "persona $target text edited",
                        )
                        refresh()
                    }
                }) { Text(stringResource(R.string.hermes_persona_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(R.string.hermes_persona_cancel))
                }
            },
        )
    }
}

private fun personaName(id: String): String = when (id) {
    PersonaStore.ID_CAVEMAN -> "Caveman"
    else -> "Ponytail"
}
