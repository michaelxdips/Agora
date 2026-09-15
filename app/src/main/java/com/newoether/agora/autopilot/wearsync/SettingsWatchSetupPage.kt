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
import com.newoether.agora.AgoraApplication
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
 * The key is **read from the provider config the user already entered** and pushed. This page never
 * displays it and never logs it, and it writes no copy of its own — but the transfer does place it in
 * the Data Layer's replicated store, which is private to this app (the platform requires the package
 * name *and* signing certificate to match on both devices). The watch deletes the item as soon as it
 * has stored the value in its encrypted `filesDir`, so the credential does not sit there indefinitely.
 * Re-pushing overwrites the watch's config, which is also the recovery path when a user re-issues a key.
 *
 * This page is also the phone half of pairing: when the watch taps "Pair with phone" it sends a
 * request on `/hermes/pair`, [PairingListenerService] checks the request's protocol version, pushes the
 * config without the user touching this screen, and the push result shows up here as the last-transfer
 * status.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
@Composable
fun SettingsWatchSetupPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf<SettingsRepository?>(null) }
    var providers by remember { mutableStateOf<ProviderRegistry?>(null) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var watchCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        // Resolved from the process-scoped container rather than passed in, so the entry point in
        // SettingsScreen stays a one-liner like every other Hermes page.
        val container = (context as? AgoraApplication)?.awaitContainer()
        settings = container?.settingsRepository
        providers = container?.providerRegistry
        val settingsRepo = container?.settingsRepository ?: return@LaunchedEffect
        val registry = container.providerRegistry
        val modelId = settingsRepo.selectedModel.value.orEmpty()
        val providerName = if (modelId.isBlank()) "" else registry.providerForModel(modelId)
        // `getEffectiveBaseUrl` is nullable: a provider with no configured base URL resolves to null,
        // and a watch config with a null URL is invalid. Fall back to empty so `WearConfig.isValid()`
        // rejects it on the watch instead of the field silently holding "null".
        baseUrl = if (providerName.isBlank()) "" else registry.getEffectiveBaseUrl(providerName).orEmpty()
        model = modelId
        watchCount = withContext(Dispatchers.IO) { WatchSync.connectedWatchCount(context) }
        // A pairing request that arrived while this page was closed is reported here instead of
        // being lost — the phone is the one that knows whether the push actually worked.
        WatchSync.lastPushOutcome.value?.let { status = context.getString(it.reason.stringRes) }
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
                        val settingsRepo = settings ?: return@Button
                        val registry = providers ?: return@Button
                        scope.launch {
                            status = null
                            val outcome = withContext(Dispatchers.IO) {
                                WatchSync.sendConfigToWatch(context, settingsRepo, registry, baseUrl, model)
                            }
                            // Resolved from resources here, on the phone: the enum is the source of
                            // truth and the sentence stays translatable.
                            status = context.getString(outcome.reason.stringRes)
                            watchCount = withContext(Dispatchers.IO) { WatchSync.connectedWatchCount(context) }
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(stringResource(R.string.hermes_watch_send)) }

                status?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
