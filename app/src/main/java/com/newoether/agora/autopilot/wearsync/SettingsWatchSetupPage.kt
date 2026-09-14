package com.newoether.agora.autopilot.wearsync

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.settings.CollapsingSettingsLazyScaffold
import com.newoether.agora.viewmodel.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-side "Watch setup" (Phase 7).
 *
 * One-time transfer of base URL, API key and model to the watch over the Data Layer. After this the
 * watch is standalone — it calls the provider directly and the phone is not needed again.
 *
 * The key is **read from the provider config the user already entered** and pushed; it is never
 * displayed, never logged, and never written anywhere on the phone side. Re-pushing overwrites the
 * watch's config, which is also the recovery path when a user re-issues a key.
 */
@Composable
fun SettingsWatchSetupPage(
    settings: SettingsRepository,
    providers: ProviderRegistry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var watchCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val modelId = settings.selectedModel.value.orEmpty()
        val providerName = if (modelId.isBlank()) "" else providers.providerForModel(modelId)
        baseUrl = if (providerName.isBlank()) "" else providers.getEffectiveBaseUrl(providerName)
        model = modelId
        watchCount = withContext(Dispatchers.IO) { WatchSync.connectedWatchCount(context) }
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.hermes_watch_setup),
        onBack = onBack,
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.hermes_watch_setup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.hermes_watch_connected, watchCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.hermes_watch_base_url)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.hermes_watch_model)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                Button(
                    onClick = {
                        scope.launch {
                            status = "…"
                            val modelId = settings.selectedModel.value.orEmpty()
                            val providerName = if (modelId.isBlank()) "" else providers.providerForModel(modelId)
                            // Read the key from the provider config the user already owns; it is
                            // pushed straight to the Data Layer and never held in this screen.
                            val key = withContext(Dispatchers.IO) {
                                runCatching {
                                    settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
                                        ?: settings.resolveActiveKey(providerName)
                                }.getOrNull().orEmpty()
                            }
                            if (key.isBlank()) {
                                status = context.getString(R.string.hermes_watch_no_key)
                                return@launch
                            }
                            val pushed = withContext(Dispatchers.IO) {
                                WatchSync.pushConfig(context, baseUrl, key, model)
                            }
                            val memory = withContext(Dispatchers.IO) { WatchSync.pushMemorySnapshot(context) }
                            watchCount = withContext(Dispatchers.IO) { WatchSync.connectedWatchCount(context) }
                            status = when {
                                !pushed -> context.getString(R.string.hermes_watch_push_failed)
                                !memory -> context.getString(R.string.hermes_watch_config_only)
                                else -> context.getString(R.string.hermes_watch_pushed)
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(stringResource(R.string.hermes_watch_send)) }

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
