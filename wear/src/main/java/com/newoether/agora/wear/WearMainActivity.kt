package com.newoether.agora.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The watch app: ask a question, read a short answer.
 *
 * Three inputs, because a watch is a bad place to type but a worse place to be unable to:
 *  * **typing** — a real field with a cursor and the system keyboard;
 *  * **voice** — the platform recognizer, one tap;
 *  * **send** — one tap, and the answer is spoken aloud as well as shown.
 *
 * Built on Wear Material 3 (`ScreenScaffold`, `ScalingLazyColumn`, `Card`, `Button`, `ListHeader`,
 * `TimeText`). Everything the phone app has that a 384 px screen cannot use is absent on purpose —
 * no image generation, no conversation trees, no sandbox, no MCP, no skills, no Room, no llama.cpp.
 * The module's whole dependency list is Compose + Wear Compose + Data Layer + OkHttp.
 */
class WearMainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null

    /** Set by the speech callback, consumed by the composition. A flow so a result can never be lost. */
    private val spoken = MutableStateFlow<String?>(null)

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        spoken.value = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchSpeech() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        setContent {
            WearHermesTheme {
                WearChatScreen(
                    spokenQuestion = spoken,
                    onSpeak = ::speak,
                    onStartListening = ::startListening,
                )
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun speak(text: String) {
        tts?.speak(text.take(MAX_SPOKEN_CHARS), TextToSpeech.QUEUE_FLUSH, null, "hermes-wear")
    }

    private fun startListening() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchSpeech() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.wear_speak_prompt))
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { WearLog.w("no speech recognizer on this watch") }
    }

    private companion object {
        /** TTS truncation: a watch speaker reading a 4000-character answer is unusable. */
        const val MAX_SPOKEN_CHARS = 600
    }
}

@Composable
private fun WearChatScreen(
    spokenQuestion: MutableStateFlow<String?>,
    onSpeak: (String) -> Unit,
    onStartListening: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val configStore = remember { WearConfigStore(context) }
    val memoryCache = remember { WearMemoryCache(context) }
    val queue = remember { WearOfflineQueue(context) }
    val listState = rememberScalingLazyListState()

    var draft by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var queued by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var debug by remember { mutableStateOf("") }

    /** One place that touches the queue, so the badge can never disagree with the file. */
    suspend fun refreshQueued() {
        queued = withContext(Dispatchers.IO) { queue.size() }
    }

    /**
     * Sends held questions oldest-first, removing each only after its own answer came back.
     *
     * Declared before [send] because it is called from there: Kotlin local functions are not
     * forward-referenceable, and a stale closure would be worse than an ordering constraint.
     *
     * @param showResult whether the drained answers may take over the visible answer slot. Callers
     *   that have a *fresh* question on screen pass false: the queue holds the OLDEST questions, so
     *   letting a drained answer win would replace what the user just asked with an answer to a
     *   question they asked minutes ago. Auto-drain on launch passes true, because then there is no
     *   fresh answer to protect.
     * @return how many held questions were delivered.
     */
    suspend fun drainQueue(client: WearChatClient, core: String, showResult: Boolean): Int {
        val pending = withContext(Dispatchers.IO) { queue.all() }
        if (pending.isEmpty()) return 0
        var delivered = 0
        for (entry in pending) {
            val outcome = withContext(Dispatchers.IO) { client.ask(entry.text, core) }
            if (outcome.isSuccess) {
                withContext(Dispatchers.IO) { queue.complete(entry.id) }
                delivered += 1
                if (showResult) {
                    answer = outcome.getOrNull().orEmpty()
                    onSpeak(answer)
                }
            } else {
                // Give up on this one only after the attempt ceiling, so a permanently failing
                // question cannot block every question behind it forever.
                WearLog.w("drain: send failed for id=${entry.id}: ${outcome.exceptionOrNull()?.message}")
                val dropped = withContext(Dispatchers.IO) { queue.recordFailure(entry.id) }
                if (dropped) WearLog.w("dropped after ${WearOfflineQueue.MAX_ATTEMPTS} attempts")
                break
            }
        }
        refreshQueued()
        return delivered
    }

    /**
     * Sends one question and, on success, drains anything the queue was holding.
     *
     * Draining here rather than on a connectivity callback is deliberate: this app has no background
     * service, so "the network came back" is only observable when the user asks something — and that
     * is exactly the moment the queued questions are worth sending.
     *
     * The drain passes `showResult = false`: this call has a fresh question on screen, and the queue
     * holds older ones, so a drained answer must not overwrite the answer the user just asked for.
     */
    suspend fun send(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        status = "Thinking…"
        answer = ""

        val config = withContext(Dispatchers.IO) { configStore.read() }
        if (config == null) {
            status = "No setup yet"
            ready = false
            return
        }

        val core = withContext(Dispatchers.IO) { WearCoreContext.build(memoryCache.read()) }
        val client = WearChatClient(config)
        val result = withContext(Dispatchers.IO) { client.ask(trimmed, core) }

        result.fold(
            onSuccess = {
                answer = it
                status = ""
                onSpeak(it)
                drainQueue(client, core, showResult = false)
            },
            onFailure = { error ->
                // Offline is a normal watch state, not an error: hold the question and say so.
                withContext(Dispatchers.IO) { queue.enqueue(trimmed) }
                refreshQueued()
                status = "Offline — held"
                WearLog.w("ask failed: ${error.message}")
            },
        )
    }

    LaunchedEffect(Unit) {
        ready = withContext(Dispatchers.IO) { configStore.read() != null }
        refreshQueued()

        // P0 fix: drain on launch. Before this, `drainQueue` had exactly one call site — the success
        // branch of `send()` — so a question held offline was only ever delivered if the user asked a
        // SECOND question that ALSO succeeded. A held question that nobody re-asks is a silently
        // dropped question, which is the worst possible failure for a device whose whole premise is
        // losing connectivity. Launch is the one moment we know the app is open and the user is
        // looking at it, and it is cheap: `drainQueue` returns immediately when the queue is empty.
        //
        // Note: this reads `ready` from the local val below rather than the state variable, because
        // Compose state writes inside a LaunchedEffect are not visible to a read in the same
        // composition pass — the `if (ready)` form was always false on first launch, which the
        // on-device proof caught (`held question delivered on launch` never logged).
        val configured = withContext(Dispatchers.IO) { configStore.read() }
        WearLog.w("launch drain check: configured=${configured != null} queue=${queue.size()}")
        if (configured != null) {
            val core = withContext(Dispatchers.IO) { WearCoreContext.build(memoryCache.read()) }
            val delivered = drainQueue(WearChatClient(configured), core, showResult = true)
            WearLog.w("launch drain: delivered=$delivered, queue now ${queue.size()}")
        }
    }

    val question by spokenQuestion.collectAsState()
    LaunchedEffect(question) {
        val pending = question
        if (pending != null) {
            spokenQuestion.value = null
            draft = pending
            send(pending)
        }
    }

    // Setup gate: two ways in, user picks. A config can also arrive from the phone while this is up,
    // so the gate re-checks the store rather than trusting the first read.
    if (!ready) {
        WearSetupScreen(onConfigured = { ready = true })
        return
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            // See WearSetupScreen: `autoCentering` pins item 0 to the screen centre, which pushed
            // the whole composer off the bottom edge. ScreenScaffold draws TimeText itself.
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                space = 6.dp,
                alignment = androidx.compose.ui.Alignment.CenterVertically,
            ),
        ) {
            item { ListHeader { Text(WearBuildInfo.PRODUCT_NAME) } }

            if (answer.isNotBlank()) {
                item {
                    Card(
                        onClick = {},
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = answer,
                            textAlign = TextAlign.Start,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else if (status.isNotBlank()) {
                item {
                    Text(
                        text = status,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }

            // Composer: the field and the two actions in one card, so the keyboard opens over a
            // stable layout instead of shifting the answer off-screen.
            item {
                Card(
                    onClick = {},
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Type a question",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val text = draft
                        draft = ""
                        scope.launch { send(text) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text("Send") }
            }
            item {
                Button(
                    onClick = onStartListening,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text("Speak") }
            }

            if (queued > 0) {
                item {
                    Text(
                        text = "$queued held offline",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }

            // Debug surface: what the watch actually holds (mandated: memory snapshot visible on the
            // watch). A plain toggle — a watch has no room for hidden gestures.
            item {
                Button(
                    onClick = {
                        showDebug = !showDebug
                        if (showDebug) {
                            scope.launch {
                                val snapshot = withContext(Dispatchers.IO) { memoryCache.read() }
                                val core = withContext(Dispatchers.IO) { WearCoreContext.build(snapshot) }
                                debug = "snapshot ${snapshot.length} chars\n" +
                                    "core ${WearCoreContext.estimateTokens(core)} tokens\n" +
                                    "queued ${withContext(Dispatchers.IO) { queue.size() }}"
                            }
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(if (showDebug) "Hide debug" else "Debug") }
            }
            if (showDebug) {
                item {
                    Text(
                        text = debug,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
