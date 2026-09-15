package com.newoether.agora.wear

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import kotlinx.coroutines.launch

/**
 * Watch setup — **two ways in, and the user picks** (owner requirement).
 *
 *  1. **Pair with the phone app** — the phone pushes base URL, key and model over the Data Layer.
 *  2. **BYOK on the watch** — type the three values here. No phone involved at all.
 *
 * Built on Wear Material 3 (`ScreenScaffold`, `Card`, `ListHeader`, `Button`). One thing Wear M3
 * deliberately does not ship is a text field — the platform convention is "open a keyboard on the
 * phone". That convention does not hold here, because the owner wants to type on the watch, so the
 * field below is a Compose foundation `BasicTextField` wrapped in an M3 `Card`: the same surface,
 * shape and colour roles as the rest of the screen, with a real cursor and the system keyboard.
 */
@Composable
fun WearSetupScreen(
    onConfigured: (WearConfig) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val store = remember { WearConfigStore(context) }
    val listState = rememberScalingLazyListState()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    // Pairing is no longer a local boolean that disables the form: it is the real status of a real
    // request, so the screen can say "no phone found" instead of pretending to wait forever.
    val pairing by WearSignals.pairing.collectAsState()
    val pairingAck by WearSignals.pairingAck.collectAsState()
    val waitingForPhone = pairing == PairingStatus.Sending || pairing == PairingStatus.Sent

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                space = 6.dp,
                alignment = androidx.compose.ui.Alignment.CenterVertically,
            ),
        ) {
            // No `autoCentering` here, and no explicit TimeText item.
            //
            // `autoCentering = AutoCenteringParams(itemIndex = 0, itemOffset = 0)` pins ITEM 0 to the
            // exact centre of the screen and lays everything else out below it — on a 384 px watch
            // that pushed the header, the three fields and both buttons off the bottom edge. It is
            // meant for one-item-per-screen pickers (a confirm/cancel pair), not for a scrolling form.
            //
            // ScreenScaffold already draws the system TimeText at the top inset itself, so a
            // `item { TimeText() }` is a second copy; it was also item 0, which is what autoCentering
            // was centring.
            item { ListHeader { Text("${WearBuildInfo.PRODUCT_NAME} setup") } }
            item {
                Text(
                    text = "Pair with the phone app, or enter your own key.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }

            if (waitingForPhone) {
                item {
                    Card(
                        onClick = {},
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Text(
                            text = "Asking the phone…\nKeep the phone app on Watch setup.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SetupField("Base URL", baseUrl, waitingForPhone) { baseUrl = it } }
            item { SetupField("API key", apiKey, waitingForPhone, secret = true) { apiKey = it } }
            item { SetupField("Model", model, waitingForPhone) { model = it } }

            item {
                Button(
                    onClick = {
                        val config = WearConfig(
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            updatedAt = System.currentTimeMillis(),
                        )
                        if (!config.isValid()) {
                            status = "Need an https base URL, key and model"
                            return@Button
                        }
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                store.write(config)
                            }
                            // Publish so the chat screen's collector sees it too; the callback alone
                            // only covers the screen that is currently composed.
                            WearSignals.config.value = config
                            onConfigured(config)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text("Save key") }
            }

            item {
                Button(
                    onClick = {
                        // Real request, real result. The button used to set a flag and print a fixed
                        // sentence; nothing ever left the watch.
                        scope.launch {
                            status = ""
                            WearPairing.request(WearPairingTransport(context))
                        }
                    },
                    enabled = !waitingForPhone,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text("Pair with phone") }
            }

            if (status.isNotBlank()) {
                item {
                    Text(
                        text = status,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }

            // The honest status line: what the last request actually did, plus the phone's own reply
            // when one arrived. Without this the user cannot tell "sent" from "there is no phone".
            if (pairing != PairingStatus.Idle || pairingAck != null) {
                item {
                    Text(
                        text = buildString {
                            append(pairing.message)
                            pairingAck?.let { append("\n").append(it) }
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A labelled, editable field on an M3 surface.
 *
 * `KeyboardOptions(imeAction = Done)` matters on a watch: the Done key is how the user closes the
 * keyboard, and without it the field swallows the whole 384 px screen with no way back.
 *
 * @param secret masks the value. The API key used to be typed and displayed in clear on the watch
 *   screen — the one credential in the app, on the most public display in the pair.
 */
@Composable
private fun SetupField(
    label: String,
    value: String,
    readOnly: Boolean,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            onClick = {},
            enabled = !readOnly,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = !readOnly,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                visualTransformation = if (secret) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = if (readOnly) "…" else "Tap to type",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

