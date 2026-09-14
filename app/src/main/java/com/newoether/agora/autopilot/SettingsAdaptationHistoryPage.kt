package com.newoether.agora.autopilot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.ui.settings.CollapsingSettingsLazyScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Phase 4 — Adaptation History.
 *
 * A settings sub-page (the same mechanism every other settings page uses; see `SettingsScreen`),
 * listing every autopilot adaptation with its before/after diff, a status chip, and a per-entry Undo.
 *
 * All state comes from the autopilot database through the view model; nothing here touches the
 * Agora stores directly.
 */
@Composable
fun SettingsAdaptationHistoryPage(
    viewModel: com.newoether.agora.viewmodel.ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val history = remember(context) {
        val database = AdaptationDatabase.get(context)
        AutopilotHistoryViewModel(
            log = database.adaptationLogDao(),
            applier = MemoryApplier(
                memoryManager = MemoryManager(context),
                skillManager = SkillManager(context),
                log = database.adaptationLogDao(),
            ),
        )
    }
    LaunchedEffect(history) { history.refresh() }

    val entries by history.entries.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var pendingUndo by remember { mutableStateOf<AdaptationEntry?>(null) }
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val debugBuild = remember(appContext) {
        (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    pendingUndo?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingUndo = null },
            title = { Text("Undo this adaptation?") },
            text = {
                Text(
                    "Hermes will restore ${entry.targetFile} to the exact content it had before " +
                        "this change."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUndo = null
                    scope.launch { history.undo(entry) }
                }) { Text("Undo") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUndo = null }) { Text("Cancel") }
            },
        )
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.hermes_adaptation_history),
        onBack = onBack,
        listState = listState,
    ) {
        item { AutopilotControlsSection() }
        if (debugBuild) {
            item {
                TextButton(
                    onClick = {
                        // Debug trigger ("Run reflection now"), debug builds only.
                        viewModel.currentConversationId.value?.let { conversationId ->
                            ReflectionWorker.scheduleNow(appContext, conversationId)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.hermes_autopilot_run_now)) }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.hermes_adaptation_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                AdaptationHistoryRow(
                    entry = entry,
                    onUndo = { pendingUndo = entry },
                )
            }
        }
    }
}

@Composable
private fun AdaptationHistoryRow(
    entry: AdaptationEntry,
    onUndo: () -> Unit,
) {
    val diff = remember(entry.id, entry.beforeSnapshot, entry.afterSnapshot) {
        AdaptationHistoryPresenter.diff(entry)
    }
    val canUndo = AdaptationHistoryPresenter.canUndo(entry)
    val timestamp = remember(entry.timestamp) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(entry.timestamp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.targetFile,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    text = "$timestamp · ${entry.store}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(entry.status)
        }

        Text(
            text = entry.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        diff.forEach { line ->
            Text(
                text = (if (line.added) "+ " else "− ") + line.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (line.added) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (entry.feedbackFlags > 0) {
            Text(
                text = "${entry.feedbackFlags} correction(s) flagged",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (canUndo) {
            TextButton(
                onClick = onUndo,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Undo")
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val container = when (status) {
        AdaptationEntry.STATUS_APPLIED -> MaterialTheme.colorScheme.primaryContainer
        AdaptationEntry.STATUS_NEEDS_REVISION -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (status) {
        AdaptationEntry.STATUS_APPLIED -> MaterialTheme.colorScheme.onPrimaryContainer
        AdaptationEntry.STATUS_NEEDS_REVISION -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = AdaptationHistoryPresenter.statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
    Spacer(modifier = Modifier.width(0.dp))
}

/**
 * Owns the history list and the undo action. Kept out of `ChatViewModel` so the autopilot feature
 * adds no responsibility to an existing upstream owner (development/README.md §5).
 */
class AutopilotHistoryViewModel(
    private val log: AdaptationLogDao,
    private val applier: MemoryApplier,
) {
    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<AdaptationEntry>>(emptyList())
    val entries: kotlinx.coroutines.flow.StateFlow<List<AdaptationEntry>> = _entries

    /** Re-reads the journal. Called on page entry and after every mutation. */
    suspend fun refresh() {
        _entries.value = withContext(Dispatchers.IO) { log.all() }
    }

    suspend fun undo(entry: AdaptationEntry) {
        withContext(Dispatchers.IO) {
            applier.undo(entry, AdaptationEntry.STATUS_USER_ROLLED_BACK)
            refresh()
        }
    }
}
