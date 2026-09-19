package com.newoether.agora.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.wear.compose.foundation.lazy.items
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

    /** True once the TTS engine reported SUCCESS; false means Speak cannot produce sound here. */
    private val ttsReady = MutableStateFlow(false)

    /** Set by the speech callback, consumed by the composition. A flow so a result can never be lost. */
    private val spoken = MutableStateFlow<String?>(null)

    /** Set when no recognizer activity exists on this image, so the UI can say so instead of nothing. */
    private val voiceUnavailable = MutableStateFlow<String?>(null)

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
    ) { granted ->
        if (granted) {
            launchSpeech()
        } else {
            // Denied is a state the user can act on, so it is stated rather than swallowed.
            voiceUnavailable.value = getString(R.string.wear_mic_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            ttsReady.value = status == TextToSpeech.SUCCESS
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
            if (status != TextToSpeech.SUCCESS) WearLog.w("no TTS engine on this watch")
        }
        setContent {
            WearHermesTheme {
                WearChatScreen(
                    spokenQuestion = spoken,
                    voiceUnavailable = voiceUnavailable,
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

    /**
     * Speaks [text] when the engine is there.
     *
     * Returns whether anything was said, so the caller can tell the user when it was not: the previous
     * version called `tts?.speak(...)` and a null/no-engine TTS made the button a silent no-op — the
     * user tapped Speak, nothing happened, and no line on screen or in the log explained it.
     */
    private fun speak(text: String): Boolean {
        val engine = tts ?: return false
        if (!ttsReady.value) return false
        engine.speak(text.take(MAX_SPOKEN_CHARS), TextToSpeech.QUEUE_FLUSH, null, "hermes-wear")
        return true
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
        // Resolve before launching: on an image with no recognizer the launcher throws
        // ActivityNotFoundException, and the previous code turned that into a log line the user
        // never sees. A watch whose whole point is voice must say when voice is not installed.
        val resolved = runCatching { packageManager.resolveActivity(intent, 0) }.getOrNull()
        if (resolved == null) {
            voiceUnavailable.value = getString(R.string.wear_voice_unavailable)
            WearLog.w("no speech recognizer on this watch")
            return
        }
        runCatching { speechLauncher.launch(intent) }.onFailure {
            voiceUnavailable.value = getString(R.string.wear_voice_unavailable)
            WearLog.w("speech launch failed: ${it.javaClass.simpleName}")
        }
    }

    private companion object {
        /** TTS truncation: a watch speaker reading a 4000-character answer is unusable. */
        const val MAX_SPOKEN_CHARS = 600
    }
}

/** How much of a dropped question's text the notice shows before it becomes noise. */
private const val DROPPED_TEXT_CHARS = 60

/** Ceiling on the visible answer before it becomes a scrollable wall on a 384 px screen. */
private const val MAX_ANSWER_LINES = 12

@Composable
private fun WearChatScreen(
    spokenQuestion: MutableStateFlow<String?>,
    voiceUnavailable: kotlinx.coroutines.flow.StateFlow<String?>,
    onSpeak: (String) -> Boolean,
    onStartListening: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val configStore = remember { WearConfigStore(context) }
    val memoryCache = remember { WearMemoryCache(context) }
    val queue = remember { WearOfflineQueue(context) }
    val listState = rememberScalingLazyListState()

    // `rememberSaveable`, not `remember`: a watch is rotated/wrist-downed constantly, and a plain
    // `remember` dropped the answer and the draft on every configuration change — the user looked
    // away mid-answer and came back to an empty screen.
    var draft by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var queued by remember { mutableStateOf(0) }
    var held by remember { mutableStateOf<List<WearOfflineQueue.Entry>>(emptyList()) }
    var notice by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    /** A question that was dropped for good. Error-coloured, and never a status line. */
    var dropNotice by rememberSaveable { mutableStateOf("") }

    /**
     * Whether a usable config exists. `rememberSaveable` so a wrist-down / process death does not
     * flash the setup screen at a user who is already configured — the value is re-derived from the
     * store by [LaunchedEffect] below, but that happens a frame late.
     */
    var ready by rememberSaveable { mutableStateOf(false) }

    /**
     * Whether the user asked to edit the configuration.
     *
     * This exists because the setup gate used to be one-way: once a config was stored, `ready` was
     * true for the rest of the process, and nothing in the app could set it back. A user who
     * mistyped a base URL, or rotated an API key, had to uninstall the app or clear its data —
     * there was no screen, no button and no gesture that led back to setup. On a watch with no
     * rotating crown and only two physical buttons, that is a dead end with no way out.
     */
    var editingConfig by rememberSaveable { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var debug by remember { mutableStateOf("") }

    val voiceProblem by voiceUnavailable.collectAsState()

    /**
     * Speaks, or says why it could not.
     *
     * The button used to be a silent no-op on an image without a TTS engine (the target watch image
     * ships none): the user tapped Speak and nothing at all happened. A fallback the user cannot see
     * is not a fallback.
     */
    fun speakOrNotice(text: String) {
        if (!onSpeak(text)) {
            notice = context.getString(R.string.wear_chat_voice_unavailable)
        }
    }

    // The store is still the source of truth, but the flow is what tells the composition that the
    // phone pushed a config while the setup screen was up. Reading the file only at launch left the
    // user staring at setup until they restarted the app.
    val pushedConfig by WearSignals.config.collectAsState()
    LaunchedEffect(pushedConfig) {
        if (pushedConfig != null) ready = true
    }

    /** One place that touches the queue, so the badge can never disagree with the file. */
    suspend fun refreshQueued() {
        val entries = withContext(Dispatchers.IO) { queue.all() }
        held = entries
        queued = entries.size
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
        val report = WearQueueDrainer.drain(
            queue = queue,
            sender = { question, coreContext ->
                AskOutcome.from(
                    withContext(Dispatchers.IO) { client.ask(question, coreContext) }
                )
            },
            coreContext = core,
            showResult = showResult,
            onFailure = { message -> WearLog.w("drain: send failed: $message") },
            onWait = { waitMs ->
                // The provider told us when it will listen again. Say so rather than showing the
                // generic offline line: "Offline" for a 429 is a lie the user acts on by retrying.
                notice = context.getString(R.string.wear_chat_rate_limited, (waitMs / 1000).toInt())
            },
        )
        if (report.lastAnswer != null) {
            answer = report.lastAnswer
            speakOrNotice(report.lastAnswer)
        }
        // Held questions whose answers were produced but could not all be shown: say how many, so
        // "answered" and "silently dropped" cannot look the same from the watch.
        if (report.answersNotShown > 0) {
            notice = if (report.answersNotShown == 1) {
                context.getString(R.string.wear_chat_earlier_one, report.answersNotShown)
            } else {
                context.getString(R.string.wear_chat_earlier_many, report.answersNotShown)
            }
        }
        report.droppedText?.let { text ->
            // P0: an automatic drop was only a log line before, so a question could disappear with
            // nothing on screen. Say it out loud, with the text, while it is still true.
            dropNotice = context.getString(
                R.string.wear_chat_dropped, WearOfflineQueue.MAX_ATTEMPTS, text.take(DROPPED_TEXT_CHARS),
            )
        }
        refreshQueued()
        return report.delivered
    }

    /**
     * One send, with no duplicate guard of its own — [send] owns that.
     *
     * Declared before [send] because Kotlin local functions are not forward-referenceable.
     */
    suspend fun sendOnce(trimmed: String) {
        status = context.getString(R.string.wear_chat_thinking)
        answer = ""

        val config = withContext(Dispatchers.IO) { configStore.read() }
        if (config == null) {
            status = context.getString(R.string.wear_chat_no_setup)
            ready = false
            return
        }

        // Durable *before* the send, removed only once the answer is in hand.
        //
        // `WearChatClient.ask` rethrows CancellationException on purpose, and this scope dies with
        // the composition — wrist-down, config change, process death. The first version enqueued
        // only in the failure branch of `result.fold`, which never runs when the coroutine is
        // cancelled: the question vanished with no queue entry, no notice and no log. Enqueueing
        // first makes the queue the record of the question, which is what it is for.
        //
        // HERMES INTEGRATION POINT (Session 5 audit): `enqueue` returns null when the queue could
        // not be persisted (disk full). Sending anyway would spend a provider call on a question
        // whose only record is this process's memory — lost on the next kill, with no queue entry
        // to recover. The user is told instead of being quietly charged.
        val entry = withContext(Dispatchers.IO) { queue.enqueue(trimmed) }
        if (entry == null) {
            status = ""
            notice = context.getString(R.string.wear_chat_queue_write_failed)
            return
        }

        val core = withContext(Dispatchers.IO) { WearCoreContext.build(memoryCache.read()) }
        val client = WearChatClient(config)
        val result = withContext(Dispatchers.IO) { client.ask(trimmed, core) }

        result.fold(
            onSuccess = {
                withContext(Dispatchers.IO) { queue.complete(entry.id) }
                refreshQueued()
                answer = it
                status = ""
                speakOrNotice(it)
                drainQueue(client, core, showResult = false)
            },
            onFailure = { error ->
                val typed = error as? WearChatException
                // A permanent failure is not "offline", and calling it that is what made a revoked
                // key look like a network problem: the user retried forever and the queue spent its
                // attempts on an answer that could never change. It leaves the queue at once and is
                // named on screen.
                if (typed?.retryable == false) {
                    val dropped = withContext(Dispatchers.IO) {
                        queue.recordFailure(entry.id, permanent = true)
                    }
                    refreshQueued()
                    status = ""
                    val reason = error.message.orEmpty()
                        .ifBlank { context.getString(R.string.wear_chat_rejected_fallback) }
                    dropNotice = if (dropped) {
                        context.getString(R.string.wear_chat_not_sent, reason)
                    } else {
                        context.getString(R.string.wear_chat_rejected, reason)
                    }
                    WearLog.w("ask failed permanently: ${error.message}")
                } else {
                    // Offline is a normal watch state, not an error: the question is already held.
                    refreshQueued()
                    status = context.getString(R.string.wear_chat_offline_held)
                    WearLog.w("ask failed: ${error.message}")
                }
            },
        )
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
     *
     * Wrapped in [WearSendCoordinator] so the *same* question cannot be sent twice at once. A double
     * tap on a 40 px button used to run two full send paths — two queue entries, two provider calls,
     * two charges — and the second answer overwrote the first on screen.
     */
    suspend fun send(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        val outcome: WearSendCoordinator.Outcome<Unit> = WearSendCoordinator.send(trimmed) {
            sending = true
            try {
                sendOnce(trimmed)
            } finally {
                sending = false
            }
        }
        if (outcome is WearSendCoordinator.Outcome.Duplicate) {
            // Said out loud: a tap that does nothing is indistinguishable from a broken button, and
            // the whole reason this path exists is that the user tapped twice on purpose.
            notice = context.getString(R.string.wear_chat_duplicate)
            WearLog.w("send: duplicate suppressed for the in-flight question")
        }
    }

    /**
     * Sends whatever is in the field and clears it.
     *
     * One function for both the Send button and the IME's Send key, so the two cannot disagree about
     * what "send" means (the keyboard action used to do nothing at all).
     */
    fun sendDraft() {
        val text = draft
        draft = ""
        scope.launch { send(text) }
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
    //
    // BackHandler on the setup screen: BACK on a watch is the platform's "up one level", and without
    // this it closed the app entirely. There is nowhere to go up to from a first-run gate, so the
    // only correct behaviour is to leave the app — which is what the platform default does. It is
    // declared explicitly rather than left implicit because the setup screen is the one place where
    // "BACK quits" is right, and that should be a decision in the code, not an accident.
    if (!ready || editingConfig) {
        // Read on the caller's side, off the composition thread: the store decrypts through the
        // Android keystore, and the setup screen must not do that in a `remember`.
        var existingConfig by remember { mutableStateOf<WearConfig?>(null) }
        LaunchedEffect(editingConfig) {
            existingConfig = withContext(Dispatchers.IO) { configStore.read() }
        }

        // BackHandler only while editing. On first run there is nothing to go back to, so the
        // platform default (leave the app) is already the correct behaviour and is left alone —
        // installing a handler that calls finish() would be a no-op with extra code.
        BackHandler(enabled = editingConfig && ready) {
            // Editing an existing config: BACK is "cancel", and the stored config is untouched.
            editingConfig = false
        }
        WearSetupScreen(
            onConfigured = {
                ready = true
                editingConfig = false
            },
            existing = existingConfig,
            onCancel = if (ready) ({ editingConfig = false }) else null,
            onSwipeBack = if (ready) ({ editingConfig = false }) else null,
        )
        return
    }

    // Back from the chat screen while a config exists: the platform default (leave the app) is
    // correct here — the chat screen IS the root. No BackHandler is installed, deliberately.

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
                        // A long answer used to be handed to the card unbounded, and the card grew
                        // past the round screen with no way to reach the rest.
                        //
                        // The answer is NOT put in its own `verticalScroll`: this Text is an item of a
                        // ScalingLazyColumn, and a scrollable child measured with an infinite maximum
                        // height is an `IllegalStateException` — found on the device, where every
                        // answer crashed the app with
                        //   "Vertically scrollable component was measured with an infinity maximum
                        //    height constraints, which is disallowed".
                        // The list scrolls; the text is capped and ellipsised by MAX_ANSWER_LINES.
                        Text(
                            text = answer,
                            textAlign = TextAlign.Start,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = MAX_ANSWER_LINES,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
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
                            // Without this the IME's Send key did nothing at all: the keyboard
                            // offered "send", the user pressed it, and the text stayed in the field.
                            keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = context.getString(R.string.wear_chat_placeholder),
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
                    // Disabled while a send is in flight, with the label saying which: the guard in
                    // WearSendCoordinator already refuses the duplicate, and a button that looks
                    // live but does nothing is the failure mode this pair exists to avoid.
                    onClick = { sendDraft() },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(if (sending) context.getString(R.string.wear_action_sending) else context.getString(R.string.wear_action_send)) }
            }
            item {
                Button(
                    onClick = onStartListening,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(context.getString(R.string.wear_action_speak)) }
            }

            if (queued > 0) {
                item {
                    Text(
                        text = context.getString(R.string.wear_chat_held_offline, queued),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
                // P0: the badge was read-only, so a held question could not be seen, retried or
                // discarded — the only way out was to wait and hope the next send drained it.
                items(held) { entry ->
                    Card(
                        onClick = {},
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = entry.text,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Start,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            // HERMES INTEGRATION POINT (Session 4): this used to be
                                            // just `send(entry.text)`, and that ran TWO provider
                                            // calls for one tap. `send` enqueues a *new* queue entry
                                            // and its success branch drains the queue — which still
                                            // held this card's original entry — so the original was
                                            // sent again as well, billed twice. Completing the
                                            // original first makes the tap mean what it says: send
                                            // this question now, once. The new send re-enqueues it,
                                            // so a failure still leaves it durably held.
                                            withContext(Dispatchers.IO) { queue.complete(entry.id) }
                                            refreshQueued()
                                            send(entry.text)
                                            refreshQueued()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(context.getString(R.string.wear_action_send_now)) }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { queue.complete(entry.id) }
                                            refreshQueued()
                                        }
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(),
                                    modifier = Modifier.weight(1f),
                                ) { Text(context.getString(R.string.wear_action_discard)) }
                            }
                        }
                    }
                }
            }

            if (notice.isNotBlank() || !voiceProblem.isNullOrBlank()) {
                item {
                    Text(
                        // The voice fallback goes through the same line as every other notice.
                        // `voiceUnavailable` was collected and then rendered **nowhere**, so the
                        // fallback this screen's own KDoc promises ("the UI can say so instead of
                        // nothing") never reached a user: on an image with no recognizer, tapping
                        // Speak did nothing visible. Reading the flow here is the render site.
                        //
                        // HERMES INTEGRATION POINT (Session 5 audit): the order used to be the
                        // reverse — `voiceProblem` won unconditionally and was never cleared — so
                        // one denied mic permanently shadowed every later notice for the rest of
                        // the process ("Rate limited — waiting 5s", "Already sending that
                        // question"): the user kept reading "Microphone permission denied" over a
                        // successful send. A fresh notice now takes the line; the voice problem
                        // remains the fallback when there is nothing newer to say.
                        text = notice.ifBlank { voiceProblem?.takeIf { it.isNotBlank() } ?: "" },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }

            if (dropNotice.isNotBlank()) {
                item {
                    Text(
                        text = dropNotice,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }

            // The way back to setup. Without this the config screen was reachable exactly once per
            // install: a mistyped base URL or a rotated API key meant uninstalling the app.
            //
            // Placed next to Debug because both are "settings", and a watch list wants the frequent
            // action (composer, Send, Speak) above the rare ones.
            item {
                Button(
                    onClick = { editingConfig = true },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(context.getString(R.string.wear_action_change_key)) }
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
                                val stored = withContext(Dispatchers.IO) { configStore.read() }
                                val lastMemory = WearSignals.memoryUpdatedAt.value
                                val dead = withContext(Dispatchers.IO) { queue.deadLetters().size }
                                debug = "${WearBuildInfo.PRODUCT_NAME}\n" +
                                    WearBuildInfo.identityLines() + "\n\n" +
                                    "snapshot ${snapshot.length} chars\n" +
                                    "core ${WearCoreContext.estimateTokens(core)} tokens\n" +
                                    "queued ${withContext(Dispatchers.IO) { queue.size() }}\n" +
                                    "dead-lettered $dead\n" +
                                    "config ${if (stored == null) "none" else stored.baseUrl}\n" +
                                    "model ${stored?.model.orEmpty().ifBlank { "none" }}\n" +
                                    "pairing ${WearSignals.pairing.value.message}\n" +
                                    "memory pushed " + if (lastMemory == 0L) "never" else lastMemory.toString()
                            }
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(if (showDebug) context.getString(R.string.wear_action_hide_debug) else context.getString(R.string.wear_action_debug)) }
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
