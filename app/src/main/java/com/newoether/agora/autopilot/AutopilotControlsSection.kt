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
    val enabled by settings.enabled.collectAsState(initial = true)
    val cap by settings.dailyCap.collectAsState(initial = AutopilotSettings.DEFAULT_DAILY_CAP)

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
                checked = enabled,
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
