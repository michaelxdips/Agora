# GAP_ANALYSIS.md — Hermes fork of Agora

Phase 2 output. Every claim cites an existing file path + signature; every path was verified to
exist on disk by the analysis pass. Contracts (development/README.md, skills.md, semantic-search.md,
ARCHITECTURE.md) were read first and override any conflicting assumption.

---

System map of Agora's **Memory** (active + saved) and **Session-Lifecycle** subsystems.

Repo root: `C:\Users\Michael\Documents\Chatapp\Agora` (Android Kotlin, Jetpack Compose, Room, WorkManager).
All paths below are relative to repo root. Every path was verified to exist on disk; every line number was
read back from the working tree at the time of writing.

Read-only analysis. No repo file was modified except this document.

Package root for all Kotlin sources: `app/src/main/java/com/newoether/agora/`.
Android `applicationId` is `com.hermes.app` (fork of upstream Agora); the Kotlin `namespace` remains
`com.newoether.agora` — `app/build.gradle.kts:19` and `app/build.gradle.kts:29`.

---

## 1. ACTIVE MEMORY

### 1.1 Storage location and exact on-disk path expression

Class: `MemoryManager` — `app/src/main/java/com/newoether/agora/data/MemoryManager.kt:10`

```kotlin
class MemoryManager(context: Context) {                                  // line 10
    private val memoryDir: File =                                        // line 11
        File(context.filesDir, "memory_db").also { it.mkdirs() }         // line 12

    private val activeMemoryFile: File =                                 // line 14
        File(context.filesDir, "active_memory.md")                       // line 15

    private val metaFile: File =                                         // line 17
        File(memoryDir, "memory_meta.json")                              // line 18
```

| Item | Exact expression | Line |
|---|---|---|
| Active-memory file | `File(context.filesDir, "active_memory.md")` | `MemoryManager.kt:15` |
| Saved-memory directory | `File(context.filesDir, "memory_db").also { it.mkdirs() }` | `MemoryManager.kt:12` |
| Saved-memory metadata sidecar | `File(memoryDir, "memory_meta.json")` | `MemoryManager.kt:18` |

Resolved on-device path (Android `Context.getFilesDir()`):

```
/data/data/com.hermes.app/files/active_memory.md
/data/data/com.hermes.app/files/memory_db/<name>.md
/data/data/com.hermes.app/files/memory_db/memory_meta.json
```

Note: the active-memory file is a **direct child of `filesDir`** — it is NOT inside `memory_db/`. The
`memory_db/` directory is not created for the active file; only `memoryDir` is eagerly `mkdirs()`-ed.

### 1.2 Format

- Plain UTF-8 markdown text. No frontmatter, no schema, no YAML, no size cap enforced in code.
- Written with `File.writeText` / `File.appendText` (default UTF-8) — `MemoryManager.kt:45, 50, 63, 67`.
- Read with `File.readText()` — `MemoryManager.kt:34`.
- Missing file is not an error: `getActiveMemory()` returns the empty string when the file does not exist
  (`MemoryManager.kt:33-34`).

### 1.3 Injection into the system prompt — exact chain

The active-memory text is injected as the value of the `active_memory` template variable, which the
default system prompt places inside `<active_memory_context>` tags.

**Step 1 — placeholder constant.** `app/src/main/java/com/newoether/agora/data/PromptTemplateItem.kt:27`

```kotlin
const val ACTIVE_MEMORY = "active_memory"
```

Also registered in the ordered variable list at `PromptTemplateItem.kt:41` and given an editor preview
value at `PromptTemplateItem.kt:55`.

**Step 2 — the default system prompt declares where it lands.**
`app/src/main/java/com/newoether/agora/data/DefaultSystemPrompt.kt`

```kotlin
private fun systemItems(): List<PromptTemplateItem> = listOf(   // line 79
    custom("""... <active_memory_context>""".trimIndent() + "\n"),  // lines 80-91
    variable(PredefinedVariables.ACTIVE_MEMORY),                 // line 92  <-- INJECTION POINT
    custom("\n" + """</active_memory_context> ..."""),           // lines 93-124
)
```

The same variable appears a second time in the legacy prompt retained for migration comparison:
`DefaultSystemPrompt.kt:140` (inside `previousSystemItems()`, declared at `DefaultSystemPrompt.kt:127`).

**Step 3 — the runtime value map.**
`app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt:32-55`

```kotlin
internal fun buildPromptRuntimeValues(
    now: java.util.Date,
    modelId: String,
    activeMemory: String,
    skillCatalog: String,
): Map<String, String> {
    ...
    return mapOf(
        ...
        PredefinedVariables.ACTIVE_MEMORY to activeMemory,        // line 52  <-- VALUE BINDING
        PredefinedVariables.SKILL_CATALOG to skillCatalog,        // line 53
    )
}
```

**Step 4 — the file is read and the gate is applied.**
`app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt`

```kotlin
private suspend fun resolvePromptTemplate(                      // line 568
    promptTemplate: GenerationPromptTemplate,
    activeModel: String,
): ResolvedPrompt = withContext(Dispatchers.Default) {
    coroutineScope {
        val includeActiveMemory = settings.accessActiveMemory.value   // line 573
        val includeSkillCatalog = settings.accessSkills.value         // line 574
        val activeMemoryDeferred = async(Dispatchers.IO) {            // line 575
            if (includeActiveMemory) memoryManager.getActiveMemory() else ""   // line 576  <-- FILE READ
        }
        ...
        val runtimeValues = buildPromptRuntimeValues(                 // line 584
            now = java.util.Date(),
            modelId = modelId,
            activeMemory = activeMemoryDeferred.await()               // line 587
                .takeIf { includeActiveMemory }
                .orEmpty(),                                           // line 589
            skillCatalog = skillCatalogDeferred.await()
                .takeIf { includeSkillCatalog }
                .orEmpty(),
        )
        ...
        ResolvedPrompt(
            systemPrompt = PredefinedVariables.compile(               // line 616  <-- SUBSTITUTION
                promptTemplate.systemItems,
                runtimeValues,
                emptyMap(),
            ).takeIf(String::isNotEmpty),                             // line 620
            ...
        )
```

**Step 5 — placeholder substitution.**
`app/src/main/java/com/newoether/agora/data/PromptTemplateItem.kt:63-76`

```kotlin
fun compile(
    items: List<PromptTemplateItem>,
    runtimeValues: Map<String, String>,
    exampleValues: Map<String, String> = EXAMPLE_VALUES
): String = items.joinToString("") { item ->
    when (item.type) {
        PromptItemType.CUSTOM -> item.value
        PromptItemType.PREDEFINED -> runtimeValues[item.value]
            ?: exampleValues[item.value]
            ?: "{${item.value}}"
    }
}
```

**Step 6 — the compiled prompt reaches the provider request.**
`GenerationConfig.effectiveSystemPrompt` (`GenerationContracts.kt:24`) ← assigned from
`resolved.systemPrompt` at `GenerationRequestBuilder.kt:419` (via `buildGenerationPair`) or
`GenerationRequestBuilder.kt:368` (via `captureContextProjectionSnapshot`) → `ProviderConfig.systemPrompt`
at `GenerationApiPathBuilder.kt:55` and `GenerationApiPathBuilder.kt:94` → provider dispatch via
`ProviderConfig.resolveRequest` at `api/LlmProvider.kt:93-98`.

### 1.4 Two distinct call paths (important for gap analysis)

| Path | Entry point | When the file is read |
|---|---|---|
| Ordinary Run admission | `captureAdmissionSnapshot(...)` — `GenerationRequestBuilder.kt:184` | NOT read here. `resolvedSystemPrompt = null` is passed to `buildGenerationPair` at `GenerationRequestBuilder.kt:210`; a lazy `requestResolver` is installed at `GenerationRequestBuilder.kt:303` via `createRequestResolver` (`GenerationRequestBuilder.kt:539`). The read happens inside the resolver lambda at `GenerationRequestBuilder.kt:543` → `resolvePromptTemplate` → line 576, i.e. **once per provider dispatch**, on `Dispatchers.IO`. |
| Context-projection snapshot (token indicator) | `captureContextProjectionSnapshot(...)` — `GenerationRequestBuilder.kt:325` | Read eagerly at `GenerationRequestBuilder.kt:365` (`resolvePromptTemplate(promptTemplate, selectedModelId)`). Works even with no API key. |

Consequence: the active-memory file is re-read for every provider pass of a Run, so mid-Run edits to
`active_memory.md` are visible to the next pass.

### 1.5 Who can write it

- **Tool path (LLM-facing):** `MemoryToolProvider.execute` handles the tool named `update_active_memory`
  — `app/src/main/java/com/newoether/agora/tool/MemoryToolProvider.kt:250-259`, calling
  `memoryManager.updateActiveMemory(arg("content"), mode, oldStr, newStr)` at
  `MemoryToolProvider.kt:257`. Tool definition is emitted only when `ctx.accessActiveMemory` is true
  (`MemoryToolProvider.kt:120-147`); tool name registered in `handles(...)` at
  `MemoryToolProvider.kt:265-272`.
- **Settings UI:** `app/src/main/java/com/newoether/agora/ui/settings/SettingsMemoryPage.kt` reads via
  `getActiveMemory()` at
  `app/src/main/java/com/newoether/agora/ui/settings/SettingsMemoryPage.kt:66`, opens the editor with the
  sentinel file name `"ACTIVE_MEMORY"` at `SettingsMemoryPage.kt:136` (recognised at
  `SettingsMemoryPage.kt:290`), and writes via `updateActiveMemory(contentSnapshot)` at
  `SettingsMemoryPage.kt:364`.
- **Import/export:** `DataExporter.kt:618-623` writes the entry `memories/active_memory.md` into the
  backup zip (`ExportCategory.MEMORIES`, declared `DataExporter.kt:49`); `DataImporter.kt:499-507`
  restores it from that exact path.
- **Gating switch:** `SettingsPreferenceSchema.kt:56`
  (`ACCESS_ACTIVE_MEMORY = booleanPreferencesKey("access_active_memory")`, default `true` at
  `SettingsManager.kt:120`), surfaced as `SettingsRepository.accessActiveMemory`
  (`SettingsRepository.kt:163`).

### 1.6 Change notification

`MemoryManager` exposes a revision counter that UI/token-estimation code observes:

```kotlin
private val _activeMemoryRevision = MutableStateFlow(0L)     // MemoryManager.kt:22
val activeMemoryRevision = _activeMemoryRevision.asStateFlow() // MemoryManager.kt:23
```

Bumped at `MemoryManager.kt:71` (inside `updateActiveMemory`). Consumers:
`ui/chat/ContextProjectionState.kt:29` and `ui/settings/SettingsMemoryPage.kt:42`.

---

## 2. SAVED MEMORY STORE

### 2.1 Class and storage contract

Class: `MemoryManager` — `app/src/main/java/com/newoether/agora/data/MemoryManager.kt:10`

One `.md` file per memory directly under `filesDir/memory_db/`, plus a single JSON object sidecar
`memory_meta.json` mapping `{ "<file name>" -> "<description>" }`. There is **no Room table and no
entity** for memories — `data/local/ChatDatabase.kt:26-43` lists all 12 entities and none of them is a
memory.

Metadata sidecar engine: `DescriptionMetadataStore` — `app/src/main/java/com/newoether/agora/data/DescriptionMetadataStore.kt:8`.
It performs atomic writes through a `.tmp` + `.bak` rename protocol
(`DescriptionMetadataStore.kt:12-13`, `atomicWrite` at `:50`, `recoverInterruptedWrite` at `:90`).

### 2.2 Complete public API of `MemoryManager`

Every public member, exact signature, `file:line`. All functions are `@Synchronized` (monitor on the
`MemoryManager` instance).

| # | Signature | file:line |
|---|---|---|
| 1 | `val activeMemoryRevision` — type `StateFlow<Long>` | `MemoryManager.kt:23` |
| 2 | `val catalogRevision` — type `StateFlow<Long>` | `MemoryManager.kt:25` |
| 3 | `data class MemoryFileInfo(val name: String, val description: String = "")` | `MemoryManager.kt:27-30` |
| 4 | `fun getActiveMemory(): String` | `MemoryManager.kt:33` |
| 5 | `fun updateActiveMemory(content: String, mode: String = "replace", oldString: String? = null, newString: String? = null): String` | `MemoryManager.kt:37` |
| 6 | `fun getDescription(name: String): String` | `MemoryManager.kt:76` |
| 7 | `fun setDescription(name: String, description: String)` | `MemoryManager.kt:83` |
| 8 | `fun listFiles(): List<MemoryFileInfo>` | `MemoryManager.kt:96` |
| 9 | `fun getMetaJson(): String` | `MemoryManager.kt:106` |
| 10 | `fun saveMetaJson(jsonString: String)` | `MemoryManager.kt:109` |
| 11 | `fun readFile(name: String): String` | `MemoryManager.kt:115` |
| 12 | `fun createFile(name: String, content: String, description: String = ""): String` | `MemoryManager.kt:122` |
| 13 | `fun editFile(name: String, content: String? = null, newName: String? = null, description: String? = null, oldString: String? = null, newString: String? = null): String` | `MemoryManager.kt:143` |
| 14 | `fun deleteFile(name: String): String` | `MemoryManager.kt:240` |

Private helpers (not API, but load-bearing):

| Helper | file:line | Behaviour |
|---|---|---|
| `private fun updateDescription(values: MutableMap<String, String>, name: String, description: String)` | `MemoryManager.kt:264` | Blank description removes the key; otherwise sets it. |
| `private fun String.countOccurrences(value: String): Int` | `MemoryManager.kt:272` | Non-overlapping occurrence count used for patch uniqueness. |
| `private fun resolveFile(name: String): File` | `MemoryManager.kt:284` | Path-traversal guard (see 2.4). |

### 2.3 Per-operation behaviour

**List** — `listFiles(): List<MemoryFileInfo>` at `MemoryManager.kt:96`

```kotlin
val values = metadata.read()                                     // line 97
return memoryDir.listFiles()                                     // line 98
    ?.filter { it.extension == "md" }                            // line 99
    ?.map { MemoryFileInfo(it.name, values[it.name].orEmpty()) }  // line 100
    ?.sortedBy { it.name }                                       // line 101
    .orEmpty()
```

Only `*.md` files are listed; the `memory_meta.json` sidecar is excluded by the extension filter.
Returns an empty list (not null) when the directory cannot be listed.

**Read** — `readFile(name: String): String` at `MemoryManager.kt:115`. Resolves the name, then
`require(file.exists()) { "File not found: $name" }` (line 117) and returns `file.readText()` (line 118).
Throws `IllegalArgumentException` for a missing file.

**Create** — `createFile(name: String, content: String, description: String = ""): String` at
`MemoryManager.kt:122`. Rejects an existing file: `require(!file.exists()) { "File already exists: ${file.name}" }`
(line 124). Writes content, then conditionally writes the description into the metadata map
(lines 125-131). On any exception it rolls the file back with `file.delete()` and attaches a suppressed
`IOException` if the rollback itself fails (lines 132-137). Bumps `_catalogRevision` (line 138) and
returns `"Created ${file.name}"` (line 139).

**Edit** — `editFile(...)` at `MemoryManager.kt:143`. Supports four independent edit modes in one call:

| Argument | Effect |
|---|---|
| `content` | Whole-file replacement (line 181, written at line 202) |
| `oldString` + `newString` | Exact-once patch; `require(matches == 1)` at lines 175-178, applied at line 179 |
| `newName` | Rename; target-collision check at lines 163-167, `file.renameTo(renameTarget)` at line 205, metadata key moved at line 189 |
| `description` | Description update via `updateDescription(...)` at line 192 |

Mutual-exclusion and non-empty guards: `require(content == null || oldString == null)` (line 153) and
`require(content != null || oldString != null || newName != null || description != null)` (line 156).
Full rollback on failure: original bytes restored (line 218) and the rename reverted (line 223), each
attached as a suppressed exception. Bumps `_catalogRevision` only when something actually changed
(lines 229-231). Returns `"Updated ${target.name}"` or `"No changes made."` (lines 232-236).

**Delete** — `deleteFile(name: String): String` at `MemoryManager.kt:240`. Reads the metadata map and the
file bytes first (lines 243-245), deletes the file (line 246), removes the metadata key (line 247), and
if the metadata write fails restores the file bytes before rethrowing (lines 248-259). Bumps
`_catalogRevision` (line 260), returns `"Deleted ${file.name}"` (line 261).

**Description only** — `getDescription` at `MemoryManager.kt:76` returns `""` for a non-existent file
(line 78). `setDescription` at `MemoryManager.kt:83` requires the file to exist (line 85) and bumps
`_catalogRevision` only when the map actually changed (lines 89-92).

**Metadata JSON passthrough** — `getMetaJson(): String` at `MemoryManager.kt:106` returns `"{}"` when the
sidecar is absent (`DescriptionMetadataStore.kt:32`). `saveMetaJson(jsonString: String)` at
`MemoryManager.kt:109` validates and replaces the whole map (`DescriptionMetadataStore.kt:36-43`), throwing
`IllegalArgumentException("Invalid metadata JSON")` on malformed input.

**Active-memory modes** — `updateActiveMemory(...)` at `MemoryManager.kt:37`:

| `mode` | Implementation | line |
|---|---|---|
| `"append"` | `activeMemoryFile.appendText("\n$content")` | 45 |
| `"prepend"` | `writeText("$content\n$existing")` | 50 |
| `"patch"` | exact-once `oldString` substitution, `require(count == 1)` at line 59 | 63 |
| anything else (default `"replace"`) | `activeMemoryFile.writeText(content)` | 67 |

### 2.4 Security invariant — path traversal guard

`private fun resolveFile(name: String): File` — `MemoryManager.kt:284-291`

```kotlin
val sanitized = name.replace(Regex("""[/\\]"""), "_")                        // line 285
val file = File(memoryDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")  // line 286
val canonicalDirectory = memoryDir.canonicalFile                             // line 287
val canonicalFile = file.canonicalFile                                       // line 288
require(canonicalFile.parentFile == canonicalDirectory) { "Invalid file name: $name" }  // line 289
return canonicalFile                                                         // line 290
```

Slash/backslash characters are replaced with `_`, `.md` is appended when absent, and the canonical parent
directory must equal the canonical `memory_db` directory. `SkillManager` uses the identical pattern at
`SkillManager.kt:242-249`.

### 2.5 LLM-facing tool surface

`app/src/main/java/com/newoether/agora/tool/MemoryToolProvider.kt` — `class MemoryToolProvider(private val memoryManager: MemoryManager) : ToolProvider` at line 20.

| Tool name | Dispatch | line |
|---|---|---|
| `list_memory_files` | `memoryManager.listFiles()` | registered `:31`, `handles` `:266` |
| `read_memory_file` | `memoryManager.readFile(...)` (single `name` or array `names`) | `:187-202`, `handles` `:267` |
| `create_memory_file` | `memoryManager.createFile(name, content, description)` | `:204-208`, `handles` `:268` |
| `edit_memory_file` | `memoryManager.editFile(...)` with `operation` ∈ `replace`/`patch`/`rename`/`describe` | `:210-246`, `handles` `:269` |
| `delete_memory_file` | `memoryManager.deleteFile(arg("name"))` | `:248`, `handles` `:270` |
| `update_active_memory` | `memoryManager.updateActiveMemory(content, mode, oldStr, newStr)` | `:250-259`, `handles` `:271` |

All definitions are suppressed when both `ctx.accessSavedMemories` and `ctx.accessActiveMemory` are false
(`MemoryToolProvider.kt:25`); saved-memory tools require `ctx.accessSavedMemories` (`:27`); the active-memory
tool requires `ctx.accessActiveMemory` (`:120`). `execute(...)` is declared
`override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String` at
`MemoryToolProvider.kt:151`, running on `Dispatchers.IO` (line 155).

### 2.6 Wiring / construction

| Site | line |
|---|---|
| `AppContainer.memoryManager: MemoryManager by lazy { MemoryManager(appContext) }` | `app/src/main/java/com/newoether/agora/di/AppContainer.kt:73` |
| Passed to `ChatViewModelFactory` | `di/AppContainer.kt:210` |
| `ChatViewModelFactory(private val memoryManager: MemoryManager, ...)` | `viewmodel/ChatViewModelFactory.kt:32` |
| `ChatViewModel(val memoryManager: MemoryManager, ...)` | `viewmodel/ChatViewModel.kt:47` |
| `GenerationRequestBuilder(private val memoryManager: MemoryManager, ...)` | `viewmodel/GenerationRequestBuilder.kt:64` |
| `GenerationManager(private val memoryManager: MemoryManager, ...)` | `viewmodel/GenerationManager.kt:47` |
| `GenerationManager` → `GenerationToolExecutor.createDefault(memoryManager = memoryManager, ...)` | `viewmodel/GenerationManager.kt:60-70` (line 63) |

---

## 3. SESSION LIFECYCLE — where "conversation finished / idle" is detectable

### 3.1 The owner that knows a generation Run ended

Per-conversation runtime host:

`app/src/main/java/com/newoether/agora/viewmodel/ConversationGenerationState.kt:60`

```kotlin
class ConversationGenerationState(
    val conversationId: String,
    private val onRegistryActive: (String) -> Unit = {},
    private val onRegistryIdle: (String) -> Unit = {},
) {
```

It owns the authoritative slot state `runState: RunState` (`ConversationGenerationState.kt:86`) and the
per-conversation `CoroutineScope` (`ConversationGenerationState.kt:66`). A state machine
(`RunState.Idle | Recovering | Preparing | Active | Stopping | Finalizing`, defined in
`app/src/main/java/com/newoether/agora/model/RunState.kt:3-120`) is reduced under a lock by
`reduceLocked(command: ConversationCommand)` at `ConversationGenerationState.kt:655`.

Idle is the terminal `RunState.Idle` state — `model/RunState.kt:6`:

```kotlin
data class Idle(override val conversationId: String) : RunState
```

### 3.2 The exact signals of idle — four options, most useful first

**(a) `Flow<Boolean>` per conversation — the cleanest programmatic signal.**

```kotlin
val generating = resources.generating      // ConversationGenerationState.kt:75
```

Backing flow: `ConversationRuntimeResources.kt:49-50`

```kotlin
private val _generating = MutableStateFlow(false)
val generating: StateFlow<Boolean> = _generating.asStateFlow()
```

Cleared to `false` in the single release path `ConversationRuntimeResources.release()` at
`ConversationRuntimeResources.kt:196-201`, which is invoked when a `RunEffect.ReleaseSlot` effect is
applied (`ConversationRuntimeResources.kt:126-134`). **Idle ⇔ `generating == false`.**

**(b) Suspend-until-idle helper.**

```kotlin
/** Wait until the conversation's one ordinary generation slot is available. */
suspend fun awaitSendAvailable() {                        // ConversationGenerationState.kt:164
    generating.first { isGenerating -> !isGenerating }    // line 165
}
```

Callers: `MessageGenerationController.kt:636` and `:756`, `QueuedGuidanceDrainExecutor.kt:322`,
`automation/TaskExecutionEngine.kt:526` and `:588`, `automation/TaskGenerationTransitions.kt:127`.

**(c) The callback fired at the exact idle transition (push, not poll).**

```kotlin
@Volatile var onIdle: ((String) -> Unit)? = null          // ConversationGenerationState.kt:387
```

Invoked from the private notifier at `ConversationGenerationState.kt:648-652`:

```kotlin
private fun notifyReleasedLocked() {
    check(runState is RunState.Idle)      // line 649
    onRegistryIdle(conversationId)        // line 650
    onIdle?.invoke(conversationId)        // line 651
}
```

which is reached only from `applyMailboxEffectsLocked` when the applied transition emitted a release
(`ConversationGenerationState.kt:633`). The sibling `onActive` callback is at
`ConversationGenerationState.kt:386`, wired by the same notifier at `:643-646`.

**(d) Run-end observability at the DB level.** A Run row that has left the live slot:
`RunEntity.activeSlot: Int?` (`data/local/RunEntity.kt:38`), with the invariant
`require((activeSlot == 1) == !status.isTerminal)` at `RunEntity.kt:49`. Terminal statuses are
`COMPLETED | STOPPED | FAILED` (`model/RuntimeIdentity.kt:8-17`, `isTerminal` at `:15-16`). Query:

```kotlin
@Query("SELECT * FROM runs WHERE conversationId = :conversationId AND activeSlot = 1 LIMIT 1")
suspend fun getLiveRun(conversationId: String): RunEntity?     // ChatContextCompactDao.kt:112
```

exposed as `ConversationRepository.getLiveRun(conversationId: String): RunEntity?` at
`data/repository/ConversationRepository.kt:388`. A `null` live Run plus a terminal latest Run means the
conversation finished.

### 3.3 How the ViewModel observes it

The ViewModel holds a registry of per-conversation states and attaches its UI callbacks once:

`app/src/main/java/com/newoether/agora/viewmodel/ConversationStateRegistry.kt:29`
`class ConversationStateRegistry` — `getOrCreate(conversationId: String): ConversationGenerationState` at
`ConversationStateRegistry.kt:42`, `attachUiCallbacks(...)` at `:69`,
`val activeConversationIds: StateFlow<Set<String>>` at `:40`, `markActive(...)` at `:112`,
`markIdle(...)` at `:117`, `isActive(conversationId: String): Boolean` at `:121`.

`ChatViewModel` wires it at `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt:472-490`:

```kotlin
private val generationCallbacksAttached = Unit.also {                     // line 472
    generationRegistry.attachUiCallbacks(generationCallbackOwner) { state ->
        state.onActive = { conversationId ->                              // line 474
            conversationUi.markActive(conversationId)                     // line 476
        }
        state.onIdle = { conversationId ->                                // line 478
            conversationUi.markIdle(conversationId)                       // line 479  <-- IDLE SINK
        }
        state.onStreamCommit = { conversationId, message -> ... }         // line 481
        state.onQueueDrainRequested = { settledState -> ... }             // line 484
    }
}
```

The idle sink is `ConversationUiStateAssembler.markIdle(conversationId: String)` at
`app/src/main/java/com/newoether/agora/viewmodel/ConversationUiStateAssembler.kt:166-171`:

```kotlin
fun markIdle(conversationId: String) {                       // line 166
    if (currentConversationId.value == conversationId) {     // line 167
        _isLoading.value = false                             // line 168
        _generatingInConversationId.value = null             // line 169
    }
}
```

Its mirror counterpart is `markActive(conversationId: String)` at `ConversationUiStateAssembler.kt:159-164`.

### 3.4 Public StateFlows a caller can collect

| Signal | Declaration | Semantics |
|---|---|---|
| `ChatViewModel.isLoading: StateFlow<Boolean>` | `ChatViewModel.kt:453` | Open conversation only; `true` while generating. |
| `ChatViewModel.generatingInConversationId: StateFlow<String?>` | `ChatViewModel.kt:454-455` | Open conversation only; `null` ⇔ idle. |
| `ChatViewModel.generationSnapshot: StateFlow<ConversationGenerationSnapshot>` | `ChatViewModel.kt:456-457` | Open conversation only. |
| `ChatViewModel.generatingConversationIds: StateFlow<Set<String>>` | `ChatViewModel.kt:495-500` | **All** conversations generating (foreground ∪ automation). Absence of an id ⇔ that conversation is idle. |
| `ConversationStateRegistry.activeConversationIds: StateFlow<Set<String>>` | `ConversationStateRegistry.kt:40` | Foreground-only active ids. |
| `ConversationExecutionCoordinator.activeAutomationConversationIds: StateFlow<Set<String>>` | `automation/ConversationExecutionCoordinator.kt:52-53` | Headless Task/Loop active ids. |
| `ConversationGenerationState.generating: StateFlow<Boolean>` | `ConversationGenerationState.kt:75` | Per-conversation, exact. |
| `ConversationGenerationState.generationSnapshot: StateFlow<ConversationGenerationSnapshot>` | `ConversationGenerationState.kt:77` | Per-conversation; carries `isGenerating` + `isLoading`. |

`ConversationGenerationSnapshot` — `app/src/main/java/com/newoether/agora/viewmodel/ConversationRuntimeResources.kt:21-26`:

```kotlin
data class ConversationGenerationSnapshot(
    val conversationId: String? = null,
    val streamingMessage: ChatMessage? = null,
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
)
```

Published by `ConversationRuntimeResources.publishGenerationSnapshot(currentState: RunState)` at
`ConversationRuntimeResources.kt:187-194`. The open-conversation mirror is
`ConversationGenerationMirror.collect(conversationId: String, state: ConversationGenerationState)` at
`app/src/main/java/com/newoether/agora/viewmodel/ConversationGenerationMirror.kt:18-22`, driven from
`ConversationUiStateAssembler.kt:227-229`.

### 3.5 Explicit end-of-generation callbacks on the generation coroutine

The generation entry point is
`GenerationManager.generate(...)` at `app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt:154-169`,
returning `GenerationExecutionResult` (`GenerationContracts.kt:230-232`). Its terminal block ends with the
completion-effects call at `GenerationManager.kt:735-753` (`GenerationCompletionEffectsExecutor.execute(...)`,
class at `viewmodel/GenerationCompletionEffectsExecutor.kt:35`, method at `:40`).

The token-gated callback bundle handed to one generation is built by
`ConversationGenerationState.callbacksFor(uiToken: Long, persistId: Long): GenerationCallbacks` at
`ConversationGenerationState.kt:395-412`. Terminal accounting is echoed back through
`suspend fun finishRunFinalization(identity: RunEffectIdentity, success: Boolean): RunFinalizationOutcome`
at `ConversationGenerationState.kt:148`, and `suspend fun endGeneration(uiToken: Long): Boolean` at
`ConversationGenerationState.kt:296`. Both are the paths that ultimately release the slot and therefore
flip `generating` to `false`.

### 3.6 Terminal persistence

`ConversationRepository.finishStoppedGeneration(...)` at `data/repository/ConversationRepository.kt:439`
and `ChatDao.finishStoppedGeneration(checkpoints: List<MessageStreamCheckpoint>, runId: String?, at: Long): Boolean`
at `data/local/ChatDao.kt:511` (annotated `@Transaction` at `ChatDao.kt:510`) are the only user-Stop
terminal writers. Normal completion uses `ChatDao.finishGeneration(...)` at `data/local/ChatDao.kt:487-504`
(`@Transaction` at `:486`), which also stamps `hasUnreadGeneration` (line 501).

---

## 4. MESSAGE COUNT

### 4.1 There is no per-conversation COUNT query in this codebase

Exhaustive search for `COUNT(` across all Kotlin sources returns exactly these DAO sites:

| file:line | Query scope |
|---|---|
| `data/local/ChatSearchDao.kt:49-50` | `SELECT COUNT(*) FROM embeddings ...` — global embedding count for one model (`getEmbeddingCountByModel`) |
| `data/local/ChatSearchDao.kt:54-68` | `SELECT e.modelId, COUNT(*) ... GROUP BY e.modelId` — global per-model counts (`getEmbeddingCountsByModels`) |
| `data/local/ChatSearchDao.kt:70-71` | `SELECT COUNT(*) FROM messages m INNER JOIN conversations c ...` — **global** indexable-message count across ALL non-task conversations (`getIndexableMessageCount`) |
| `data/local/ChatSearchDao.kt:119-120` | `SELECT COUNT(*) FROM conversations WHERE taskId IS NULL` (`getSearchableConversationCount`) |
| `data/local/SemanticIndexLedger.kt:165-174` | `SELECT COUNT(*) FROM semantic_index_work WHERE modelId = :modelId AND sourceRevision <= :maxSourceRevision` (`getWorkCountThroughRevision`) |
| `data/local/SemanticIndexLedger.kt:206-224` | `SELECT COUNT(*) FROM messages ...` (`getReconcileMessageCount`) |
| `data/local/migration/Migration16To17.kt:111` | migration-only guard `SELECT COUNT(*) FROM messages WHERE runId IS NULL OR runSequence IS NULL` |

None of these is scoped by `:conversationId`. **A per-conversation message count must be computed by
fetching the topology and calling `.size`, or by adding a new DAO query.**

### 4.2 Recommended: topology snapshot, then `.size`

DAO — `app/src/main/java/com/newoether/agora/data/local/ChatProviderContextDao.kt` (mixed into the single
`@Dao interface ChatDao` at `data/local/ChatDao.kt:32-38`):

```kotlin
@Query(
    """
    SELECT
        id, conversationId, parentId, status, participant, timestamp, tokenCount,
        modelName, runId, runSequence, consumedAtPass
    FROM messages
    WHERE conversationId = :conversationId
    ORDER BY timestamp ASC, id ASC
    """
)
suspend fun getMessageContextTopology(
    conversationId: String,
): List<MessageContextTopology>                       // ChatProviderContextDao.kt:36-38
```

Reactive twin:

```kotlin
fun observeMessageContextTopology(
    conversationId: String,
): Flow<List<MessageContextTopology>>                 // ChatProviderContextDao.kt:59-61
```

Repository wrappers — `app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt`:

```kotlin
fun observeMessageTopology(conversationId: String): Flow<List<MessageContextTopology>>   // line 202
suspend fun getMessageTopologySnapshot(conversationId: String): List<MessageContextTopology>  // line 207
```

Row type — `app/src/main/java/com/newoether/agora/data/local/MessageContextTopology.kt:7-19`:

```kotlin
data class MessageContextTopology(
    val id: String,
    val conversationId: String,
    val parentId: String?,
    val status: MessageStatus,
    val participant: Participant,
    val timestamp: Long,
    val tokenCount: Int = 0,
    val modelName: String?,
    val runId: String,
    val runSequence: Long,
    val consumedAtPass: Int?,
)
```

Working precedent in production code — `tool/RagToolProvider.kt:323-327`:

```kotlin
val topology = conversations.getMessageTopologySnapshot(conversationId)     // line 323
    .filter { it.participant in listOf(Participant.USER, Participant.MODEL) }  // line 324
val branch = buildSelectedTopologyBranch(topology, conversation.selectedBranchesJson)  // line 325
    .filterNot { isSyntheticMessageId(it.id) }                              // line 326
val totalMessages = branch.size                                             // line 327
```

Three counting granularities, all derived from the same snapshot:

| Count | Expression | Notes |
|---|---|---|
| Raw row count | `conversations.getMessageTopologySnapshot(id).size` | Includes tool/result/compact rows. |
| User+Model only | `.filter { it.participant in listOf(Participant.USER, Participant.MODEL) }.size` | Mirrors the DAO predicate used by the COUNT queries. |
| Selected branch, no synthetics | `.filter { it.participant in listOf(Participant.USER, Participant.MODEL) }` → `buildSelectedTopologyBranch(topology, conversation.selectedBranchesJson)` → `.filterNot { isSyntheticMessageId(it.id) }.size` | Exactly what `read_conversation` reports as `total_messages`. |

Helpers: `private fun buildSelectedTopologyBranch(...)` at `tool/RagToolProvider.kt:371` and
`private fun isSyntheticMessageId(messageId: String): Boolean` at `tool/RagToolProvider.kt:407-409`
(true for `Constants.TOOL_MSG_PREFIX` and `Constants.RESULT_MSG_PREFIX`).

Other production callers of `getMessageTopologySnapshot` that already size the result:
`viewmodel/ConversationBranchMutationService.kt:67,79`, `viewmodel/ConversationForkShareService.kt:59,272`,
`viewmodel/ConversationLifecycleController.kt:58`, `viewmodel/ConversationTitleGenerator.kt:80`.

### 4.3 UI-layer count

`ConversationUiStateAssembler.messages: StateFlow<List<ChatMessage>>` at
`ConversationUiStateAssembler.kt:88-100`, re-exposed as `ChatViewModel.messages` at `ChatViewModel.kt:452`.
`messages.value.size` gives the currently rendered conversation's count — but only for the conversation
that is open, and only after its projection has loaded.

### 4.4 Related message queries (for completeness)

| Signature | file:line |
|---|---|
| `@Query("SELECT * FROM messages WHERE id = :messageId") fun observeMessage(messageId: String): Flow<MessageEntity?>` | `data/local/ChatDao.kt:79-80` |
| `@Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1") suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?` | `data/local/ChatDao.kt:644-645` |
| `@Query("SELECT * FROM messages WHERE id IN (:ids)") suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity>` | `data/local/ChatDao.kt:665-666` |
| `@Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")` (via `getMessageContextTopology`) | `data/local/ChatProviderContextDao.kt:17-38` |
| `@Query("SELECT id FROM messages WHERE id IN (:ids)") suspend fun findExistingMessageIds(ids: List<String>): List<String>` | `data/local/ChatDao.kt:709-710` |
| `suspend fun getMessagesPage(afterId: String?, limit: Int): List<MessageEntity>` (keyset paging over the whole table) | `data/local/ChatDao.kt:695-704` |
| `@Query("SELECT * FROM messages WHERE runId IN (:runIds) ORDER BY runSequence, timestamp, id") suspend fun getMessagesForRuns(runIds: List<String>): List<MessageEntity>` | `data/local/ChatContextCompactDao.kt:48-49` |
| `suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity>` (repository wrapper) | `data/repository/ConversationRepository.kt:507` |
| `suspend fun getMessage(messageId: String): MessageEntity?` (repository wrapper) | `data/repository/ConversationRepository.kt:224` |

### 4.5 Global counts that DO exist (via `ConversationRepository`)

| Signature | file:line | Scope |
|---|---|---|
| `suspend fun getEmbeddingCountByModel(modelId: String): Int` | `data/repository/ConversationRepository.kt:594` | global, per model |
| `suspend fun getEmbeddingCountsByModels(modelIds: List<String>): List<EmbeddingModelCount>` | `data/repository/ConversationRepository.kt:597` | global, per model |
| `suspend fun getIndexableMessageCount(): Int` | `data/repository/ConversationRepository.kt:600` | **global** across all non-task conversations |
| `suspend fun getSearchableConversationCount(): Int` | `data/repository/ConversationRepository.kt:621` | global conversation count |

`getIndexableMessageCount` has exactly one production consumer:
`viewmodel/RagManager.kt:157` (`async { conversations.getIndexableMessageCount() }`).

`EmbeddingModelCount` — `data/local/ChatSearchDao.kt:5-8`.

---

## 5. GAP NOTES (observed, not inferred)

1. **No per-conversation message count exists.** Every `COUNT(*)` in the codebase is global or
   model-scoped (`ChatSearchDao.kt:49,54,70,119`; `SemanticIndexLedger.kt:167,208`). Counting messages
   for one conversation requires materialising the topology list and calling `.size`
   (`ConversationRepository.kt:207`, precedent `RagToolProvider.kt:323-327`). Adding a
   `SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId` query to `ChatDao` would be the
   minimal fix.
2. **Active memory has no size cap.** `MemoryManager.updateActiveMemory` writes unconditionally
   (`MemoryManager.kt:45,50,63,67`) with no length check. Because it is re-injected into the system
   prompt on every provider dispatch (`GenerationRequestBuilder.kt:543→576`), an unbounded file grows the
   fixed token cost of every request.
3. **`memory_meta.json` is inside the same directory as the memory files.** `listFiles()` compensates
   with an extension filter (`MemoryManager.kt:99`), but any consumer that enumerates
   `filesDir/memory_db/` directly will see the sidecar.
4. **Idle is per-conversation, not global.** `ChatViewModel.isLoading` / `generatingInConversationId`
   (`ChatViewModel.kt:453-455`) reflect only the open conversation; multi-conversation idle requires
   `ChatViewModel.generatingConversationIds` (`ChatViewModel.kt:495-500`) or per-id
   `ConversationGenerationState.generating` (`ConversationGenerationState.kt:75`).
5. **`onIdle` fires only on slot release.** `notifyReleasedLocked()` asserts `runState is RunState.Idle`
   (`ConversationGenerationState.kt:649`), so it does not fire for the `Finalizing`/`Stopping`
   intermediate states — a consumer that needs "the model stopped producing text" must use
   `onStreamCommit` (`ConversationGenerationState.kt:388`) or the Run-row status instead.

---

## Sources

Every file path cited above, relative to repo root `C:\Users\Michael\Documents\Chatapp\Agora`. All were
verified to exist on disk before this document was written.

**Memory subsystem**
- `app/src/main/java/com/newoether/agora/data/MemoryManager.kt`
- `app/src/main/java/com/newoether/agora/data/DescriptionMetadataStore.kt`
- `app/src/main/java/com/newoether/agora/data/SkillManager.kt`
- `app/src/main/java/com/newoether/agora/data/PromptTemplateItem.kt`
- `app/src/main/java/com/newoether/agora/data/DefaultSystemPrompt.kt`
- `app/src/main/java/com/newoether/agora/data/SettingsPreferenceSchema.kt`
- `app/src/main/java/com/newoether/agora/data/SettingsManager.kt`
- `app/src/main/java/com/newoether/agora/data/DataExporter.kt`
- `app/src/main/java/com/newoether/agora/data/DataImporter.kt`
- `app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt`
- `app/src/main/java/com/newoether/agora/tool/MemoryToolProvider.kt`
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsMemoryPage.kt`
- `app/src/main/java/com/newoether/agora/ui/chat/ContextProjectionState.kt`
- `app/src/main/java/com/newoether/agora/di/AppContainer.kt`

**Prompt injection chain**
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationContracts.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationApiPathBuilder.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt`
- `app/src/main/java/com/newoether/agora/api/LlmProvider.kt`

**Session lifecycle**
- `app/src/main/java/com/newoether/agora/viewmodel/ConversationGenerationState.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/ConversationRuntimeResources.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/ConversationStateRegistry.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/ConversationUiStateAssembler.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/ConversationGenerationMirror.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModelFactory.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/MessageGenerationController.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/QueuedGuidanceDrainExecutor.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationCompletionEffectsExecutor.kt`
- `app/src/main/java/com/newoether/agora/automation/ConversationExecutionCoordinator.kt`
- `app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt`
- `app/src/main/java/com/newoether/agora/model/RunState.kt`
- `app/src/main/java/com/newoether/agora/model/RuntimeIdentity.kt`
- `app/src/main/java/com/newoether/agora/model/ChatMessage.kt`

**Message count / Room**
- `app/src/main/java/com/newoether/agora/data/local/ChatDao.kt`
- `app/src/main/java/com/newoether/agora/data/local/ChatSearchDao.kt`
- `app/src/main/java/com/newoether/agora/data/local/ChatProviderContextDao.kt`
- `app/src/main/java/com/newoether/agora/data/local/ChatContextCompactDao.kt`
- `app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt`
- `app/src/main/java/com/newoether/agora/data/local/MessageContextTopology.kt`
- `app/src/main/java/com/newoether/agora/data/local/RunEntity.kt`
- `app/src/main/java/com/newoether/agora/data/local/ChatEntities.kt`
- `app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt`
- `app/src/main/java/com/newoether/agora/data/local/migration/Migration16To17.kt`
- `app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt`
- `app/src/main/java/com/newoether/agora/viewmodel/RagManager.kt`
- `app/src/main/java/com/newoether/agora/tool/RagToolProvider.kt`

**Build config**
- `app/build.gradle.kts`

---

System map of Agora's **Skills**, **Provider-Call**, **Embedding**, and **Settings-Navigation** subsystems.

Repo root: `C:\Users\Michael\Documents\Chatapp\Agora` (commit `2205ac56`, working tree clean).
All paths below are relative to repo root. Every path was verified to exist on disk; every line number
was verified with `rg -n` against the working tree at the time of writing.

Read-only analysis. No file in the repo was modified except this document.

---

## 1. SKILLMANAGER

### 1.1 Class and location

`app/src/main/java/com/newoether/agora/data/SkillManager.kt`

```kotlin
class SkillManager(context: Context)          // line 10
```

Not an interface, not injected via annotations. Constructor takes a raw Android `Context` and derives
its storage root itself:

| Member | Line | Notes |
|---|---|---|
| `private val skillDir = File(context.filesDir, "skill_db")` | 11 | `.also { it.mkdirs() }` — created eagerly in the constructor |
| `private val metaFile = File(skillDir, "skill_meta.json")` | 12 | description sidecar |
| `private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }` | 13 | |
| `private val metadata = DescriptionMetadataStore(metaFile, json)` | 14 | `app/src/main/java/com/newoether/agora/data/DescriptionMetadataStore.kt:8` |

Storage contract: one `.md` file per skill directly under `filesDir/skill_db/`, plus a single JSON map
`{ "<file name>" -> "<description>" }`. There is no database table and no Room entity for skills.

### 1.2 Full public API surface

Every public member, with exact signature and `file:line`. All `fun`s except the constructor are
annotated `@Synchronized` (monitor on the `SkillManager` instance).

| # | Signature | Line |
|---|---|---|
| 1 | `val catalogRevision = _catalogRevision.asStateFlow()` — type `StateFlow<Long>` | `SkillManager.kt:16` |
| 2 | `data class SkillFileInfo(val name: String, val description: String = "")` | `SkillManager.kt:18` |
| 3 | `@Synchronized fun listFiles(): List<SkillFileInfo>` | `SkillManager.kt:24` |
| 4 | `@Synchronized fun catalog(): String` | `SkillManager.kt:34` |
| 5 | `@Synchronized fun getDescription(name: String): String` | `SkillManager.kt:57` |
| 6 | `@Synchronized fun getMetaJson(): String` | `SkillManager.kt:64` |
| 7 | `@Synchronized fun saveMetaJson(jsonString: String)` | `SkillManager.kt:67` |
| 8 | `@Synchronized fun readFile(name: String): String` | `SkillManager.kt:73` |
| 9 | `@Synchronized fun createFile(name: String, content: String, description: String = ""): String` | `SkillManager.kt:80` |
| 10 | `@Synchronized fun editFile(name: String, content: String? = null, newName: String? = null, description: String? = null, oldString: String? = null, newString: String? = null): String` | `SkillManager.kt:101` |
| 11 | `@Synchronized fun deleteFile(name: String): String` | `SkillManager.kt:198` |

Private helpers (not part of the API but load-bearing for behaviour):

- `private fun updateDescription(values: MutableMap<String, String>, name: String, description: String)` — `SkillManager.kt:222`
- `private fun String.countOccurrences(value: String): Int` — `SkillManager.kt:230`
- `private fun resolveFile(name: String): File` — `SkillManager.kt:242`. Sanitises `/` and `\` to `_`,
  appends `.md` when missing, and **path-traversal guards** by requiring
  `canonicalFile.parentFile == canonicalDirectory` (`SkillManager.kt:247`).

Behavioural contracts worth knowing before extending:

- `catalog()` (`:34`) returns `""` for an empty skill dir, otherwise an XML-ish block wrapped in
  `<available_skills>` … `</available_skills>` listing `- <name>: <description>` per file. This string
  is what gets injected into the prompt (see §1.4).
- `createFile` (`:80`) requires the file **not** to exist (`require(!file.exists())`, `:82`) and rolls
  the file back if metadata write throws (`:90-95`).
- `editFile` (`:101`) enforces: `content` and `oldString` mutually exclusive (`:111`), at least one edit
  argument present (`:114`), rename target must not exist (`:122`), and `oldString` must match
  **exactly once** (`:133-136`). It rolls back content and rename on failure (`:172-185`).
- `catalogRevision` is a monotonically increasing `Long` bumped on every mutating operation
  (`:69`, `:96`, `:188`, `:218`). It is the recomposition trigger for the skills UI and the context
  projection cache (see §1.4). It is **not** persisted — it resets to `0` on process restart.

### 1.3 Who constructs it and how it is obtained at runtime

Manual DI / service-locator-ish container, no Hilt/Koin.

```
AgoraApplication (Application subclass)
  └─ DatabaseStartupGate
       └─ openResource = { ChatDatabase.build(...) ; AppContainer(this@AgoraApplication, database) }
```

- `app/src/main/java/com/newoether/agora/AgoraApplication.kt:24` — `class AgoraApplication : Application()`
- `app/src/main/java/com/newoether/agora/AgoraApplication.kt:36` — `AppContainer(this@AgoraApplication, database)`
- `app/src/main/java/com/newoether/agora/AgoraApplication.kt:81` — `suspend fun awaitContainer(): AppContainer?`
- `app/src/main/java/com/newoether/agora/AgoraApplication.kt:84` — `fun requireContainer(): AppContainer`

The container itself:

- `app/src/main/java/com/newoether/agora/di/AppContainer.kt:49` — `class AppContainer(private val appContext: Context, val database: ChatDatabase)`
- `app/src/main/java/com/newoether/agora/di/AppContainer.kt:74` — **the single construction site**:

```kotlin
val skillManager: SkillManager by lazy { SkillManager(appContext) }
```

`by lazy` on the container ⇒ process-wide singleton, created on first touch, keyed to the
`Application` context (never an Activity). The container is published only after the database passes
compatibility/migration/Room-schema validation (`AgoraApplication.kt:21-22` doc comment).

Runtime access path — everything is constructor-threaded, nothing re-resolves the locator:

| Consumer | Wiring | Line |
|---|---|---|
| `ChatViewModel` (public field, reachable from all settings/chat Compose code as `viewModel.skillManager`) | `val skillManager: SkillManager` ctor param | `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt:48` |
| `ChatViewModelFactory` | `private val skillManager: SkillManager` ctor param → passed into `ChatViewModel(...)` | `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModelFactory.kt:33`, `:58` |
| `ChatViewModelFactory` creation | `fun chatViewModelFactory(): ChatViewModelFactory` | `app/src/main/java/com/newoether/agora/di/AppContainer.kt:278` (skillManager at `:280`) |
| `TaskExecutionEngine` (headless/background) | named arg `skillManager = skillManager` | `app/src/main/java/com/newoether/agora/di/AppContainer.kt:211` |
| `AutoBackupManager` | `AutoBackupManager(appContext, settingsManager, memoryManager, skillManager)` | `app/src/main/java/com/newoether/agora/di/AppContainer.kt:273` |
| `GenerationManager` | `private val skillManager: SkillManager` ctor param | `app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt:48`; wired at `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt:252` |
| `GenerationToolExecutor` → `SkillToolProvider` | `SkillToolProvider(skillManager)` | `app/src/main/java/com/newoether/agora/viewmodel/GenerationToolExecutor.kt:99` |
| `DataControlController` | `skills = skillManager` | `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt:106` |
| `ImportExportManager` → `DataExporter`/`DataImporter` | `skillManager = skillManager` | `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt:180` |
| `GenerationRequestBuilder` | `private val skillManager: SkillManager` ctor param | `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt:65` |
| Compose: skills settings page | `viewModel.skillManager.*` | `app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt:70`, `:105`, `:117`, `:316`, `:495`, `:608`, `:713` |
| Compose: context projection invalidation | `viewModel.skillManager.catalogRevision.collectAsState()` | `app/src/main/java/com/newoether/agora/ui/chat/ContextProjectionState.kt:30` |

### 1.4 How the catalog reaches the model

- Tool surface: `app/src/main/java/com/newoether/agora/tool/SkillToolProvider.kt:18` —
  `class SkillToolProvider(private val skillManager: SkillManager) : ToolProvider`, with
  `override fun definitions(ctx: GenerationContext): List<ToolDefinition>` at `:21`. It returns an empty
  list when both `ctx.skillReadAccess` and `ctx.skillModifyAccess` are false (`:22`).
- Prompt injection: `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt:407` —
  `val skillCatalog = if (skillReadAccess) skillManager.catalog() else ""`, gated by
  `settings.accessSkills` at `:404-406`, and threaded into `GenerationContext(skillCatalog = …)` at `:456`.
  A second call site at `:579` (`if (includeSkillCatalog) skillManager.catalog() else ""`).
- The catalog string lands in prompt variables via
  `internal fun buildPromptRuntimeValues(now: Date, modelId: String, activeMemory: String, skillCatalog: String): Map<String, String>`
  — `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt:32`, mapping
  `PredefinedVariables.SKILL_CATALOG to skillCatalog` at `:53`.
- Settings gates: `val accessSkills: Flow<Boolean>` (`app/src/main/java/com/newoether/agora/data/SettingsManager.kt:121`),
  `val accessSkillsModify` (`:123`); repository mirrors at
  `app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt:164-165`.

---

## 2. EMBEDDING PIPELINE

### 2.1 The text → vector primitive

Two mutually exclusive paths, selected by `EmbeddingModelConfig.type`.

**Remote (OpenAI-compatible HTTP):**

`app/src/main/java/com/newoether/agora/api/EmbeddingClient.kt` — a **singleton `object`**, stateless,
no DI needed:

```kotlin
object EmbeddingClient {                                                    // :26

    suspend fun computeEmbedding(
        text: String,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1",
    ): FloatArray?                                                          // :30-35

    suspend fun computeEmbeddings(
        texts: List<String>,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1",
    ): List<FloatArray?>                                                     // :58-63
}
```

- Runs on `Dispatchers.IO` via `withContext` (`:35`, `:63`).
- `POST "$baseUrl/embeddings"` through `HttpClient.post(url, body, headers)`
  (`app/src/main/java/com/newoether/agora/api/HttpClient.kt:470`), with `Authorization: Bearer $apiKey`
  added only when the key is non-blank (`:41`, `:70`).
- Returns `null` (single) / `List<FloatArray?>` with per-item `null` (batch) on any failure — **never
  throws**. Failures are logged as `DebugLog.e("EmbeddingClient", …)` (`:53`, `:82`).

**Local (on-device llama.cpp):**

`app/src/main/java/com/newoether/agora/api/LlamaEngine.kt` — also an `object`:

```kotlin
fun isModelReady(modelPath: String): Boolean                                 // :25
suspend fun computeEmbedding(text: String, modelPath: String): FloatArray?   // :29
suspend fun computeEmbeddings(texts: List<String>, modelPath: String): List<FloatArray?> // :34
```

`isModelReady` is a cheap `File(path).exists() && length() > 0` check (`:26`) — call it before use.
Native embedding runs under `LocalModelRuntime.runEmbedding(modelPath) { … }`
(`app/src/main/java/com/newoether/agora/api/LocalModelRuntime.kt:160`), which serialises access to the
single process-wide embedded model handle.

**Vector math helper:** `app/src/main/java/com/newoether/agora/data/EmbeddingIndexer.kt:6` —
`object EmbeddingIndexer` with `floatsToBytes(FloatArray): ByteArray` (`:8`),
`bytesToFloats(ByteArray): FloatArray` (`:14`), `cosineSimilarity(a: FloatArray, b: FloatArray): Float` (`:21`).
Persistence uses `floatsToBytes` into `EmbeddingEntity.embedding` — see
`app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt:360`.

### 2.2 The canonical "do it right" invocation (copy this pattern)

`app/src/main/java/com/newoether/agora/tool/RagToolProvider.kt` — `suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>>` at `:411`. The branch at `:417-435` is the reference implementation:

```kotlin
val config = ctx.activeEmbeddingConfig ?: return emptyList()          // :412
val queryEmbedding = if (config.type == EmbeddingModelType.LOCAL) {
    if (!LlamaEngine.isModelReady(config.localFilePath)) return emptyList()   // :418
    LlamaEngine.computeEmbedding(query, config.localFilePath)                 // :422
} else {
    val apiKey = resolveEmbeddingApiKey(ctx) ?: return emptyList()            // :424-428
    EmbeddingClient.computeEmbedding(
        text    = query,
        apiKey  = apiKey,
        model   = config.remoteModelName,
        baseUrl = config.remoteBaseUrl.ifBlank { ProviderDefaults.OPENAI_BASE_URL },  // :429-434
    )
}
```

### 2.3 Config model

`app/src/main/java/com/newoether/agora/data/EmbeddingModelConfig.kt`:

```kotlin
enum class EmbeddingModelType { REMOTE, LOCAL }        // :6

@Serializable
data class EmbeddingModelConfig(                       // :9
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: EmbeddingModelType,
    val remoteModelName: String = "",
    val remoteBaseUrl: String = "",
    val remoteApiKey: String = "",
    val localFilePath: String = "",
    val batchSize: Int = 8,
)
```

### 2.4 How the active config, API key, and base URL are resolved

**Active config** — `app/src/main/java/com/newoether/agora/viewmodel/RagManager.kt:53`:

```kotlin
val activeEmbeddingModel: StateFlow<EmbeddingModelConfig?> =
    combine(settings.embeddingModels, settings.activeEmbeddingModelId) { models, id ->
        models.find { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)                        // :53-56
```

Backing settings: `val embeddingModels: StateFlow<List<EmbeddingModelConfig>>`
(`app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt:173`) and
`val activeEmbeddingModelId: StateFlow<String>` (`:174`), fed from DataStore keys
`EMBEDDING_MODELS_JSON` / `ACTIVE_EMBEDDING_MODEL_ID`
(`app/src/main/java/com/newoether/agora/data/SettingsManager.kt:129-133`).

`RagManager` is **not** in `AppContainer`; it is constructed twice, once per scope:

- foreground: `app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt:161` — `val ragManager = RagManager(conversations = convRepo, settings = settings, appContext = appContext, scope = viewModelScope) { _snackbarMessage.emit(it) }`
- headless: `app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt:148` — same shape with `scope = appScope` and `emitSnackbar = {}`

`class RagManager(private val conversations: ConversationRepository, private val settings: SettingsRepository, private val appContext: Context, private val scope: CoroutineScope, private val emitSnackbar: suspend (SnackbarEvent) -> Unit)` — `app/src/main/java/com/newoether/agora/viewmodel/RagManager.kt:46`.

**API key resolution, two variants — they disagree, know which you want:**

1. Foreground / settings-value based — `app/src/main/java/com/newoether/agora/viewmodel/RagManager.kt:542`:

```kotlin
fun resolveEmbeddingApiKey(): String? {          // :542
    val keys = settings.apiKeys.value
    for (entry in keys) {
        if (ProviderDefaults.isOpenAiCompatibleEmbedding(entry.provider)) return entry.key   // :545
    }
    return keys.firstOrNull()?.key                                                           // :547
}
```

   `fun resolveEmbeddingBaseUrl(): String` at `:550` →
   `ProviderDefaults.openAiCompatibleBaseUrl(settings.providerBaseUrls.value)`.

2. Worker / on-disk-awaiting based — `app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt:399`:

```kotlin
private suspend fun resolveEmbeddingConfig(model: EmbeddingModelConfig, settingsManager: SettingsManager): Pair<String, String>? {
    if (model.type == EmbeddingModelType.LOCAL) {
        check(LlamaEngine.isModelReady(model.localFilePath)) { "Local model file not found" }   // :404
        return null
    }
    val apiKey = model.remoteApiKey.ifBlank { resolveApiKey(settingsManager) ?: "" }            // :407
    check(apiKey.isNotBlank()) { "No API key configured" }                                      // :408
    return apiKey to model.remoteBaseUrl.ifBlank { resolveBaseUrl(settingsManager) }            // :409
}

private suspend fun resolveApiKey(settingsManager: SettingsManager): String? {                  // :412
    val keys = settingsManager.apiKeys.first()
    return keys.firstOrNull { ProviderDefaults.isOpenAiCompatibleEmbedding(it.provider) }?.key
        ?: keys.firstOrNull()?.key
}

private suspend fun resolveBaseUrl(settingsManager: SettingsManager): String =                  // :418
    ProviderDefaults.openAiCompatibleBaseUrl(settingsManager.providerBaseUrls.first())
```

   Note this variant **honours `model.remoteApiKey` first** and falls back to the global OpenAI-compatible
   key. `ProviderDefaults.isOpenAiCompatibleEmbedding` accepts only `openai` and `open_router`
   (`app/src/main/java/com/newoether/agora/api/ProviderDefaults.kt:19-20`); the base-URL fallback is
   `ProviderDefaults.OPENAI_BASE_URL = "https://api.openai.com/v1"` (`:16`).

**Recommended call shape for a new consumer** (mirrors `EmbeddingCacheWorker`, avoids the
`.value`-before-load race documented at `SettingsRepository.kt:719-726`):

```kotlin
val model = ragManager.activeEmbeddingModel.value ?: return
val vector: FloatArray? = if (model.type == EmbeddingModelType.LOCAL) {
    LlamaEngine.computeEmbedding(text, model.localFilePath)
} else {
    EmbeddingClient.computeEmbedding(
        text    = text,
        apiKey  = model.remoteApiKey.ifBlank { ragManager.resolveEmbeddingApiKey() ?: "" },
        model   = model.remoteModelName,
        baseUrl = model.remoteBaseUrl.ifBlank { ragManager.resolveEmbeddingBaseUrl() },
    )
}
```

Batch ceiling: text is truncated to `Constants.MAX_EMBEDDING_TEXT_LENGTH` before embedding
(`app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt:339`).

---

## 3. PROVIDER CALL PATH REUSABLE FOR A CHEAP REFLECTION CALL

### 3.1 The lowest-level reusable primitive

```kotlin
interface LlmProvider {                                                   // app/src/main/java/com/newoether/agora/api/LlmProvider.kt:517
    val name: String                                                      // :518
    val defaultBaseUrl: String                                            // :519
    val nativeTextParsingAuthoritative: Boolean get() = false             // :520-521
    val baseUrlPlaceholder: String get() = defaultBaseUrl                 // :522-523

    fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent>                                                  // :525-528

    suspend fun fetchModels(apiKey: String, baseUrl: String? = null): List<String>  // :530
}
```

`generateResponse` is **always a `Flow<StreamEvent>`** — there is no separate non-streaming entry point
anywhere in the codebase. A "non-streaming" call means: collect the flow and concatenate
`StreamEvent.TextChunk.text`. Implementations:

| Provider | `override fun generateResponse` |
|---|---|
| `BaseOpenAiProvider` (OpenAI + OpenAI-compatible subclasses) | `app/src/main/java/com/newoether/agora/api/openai/BaseOpenAiProvider.kt:92` |
| `AnthropicProvider` | `app/src/main/java/com/newoether/agora/api/anthropic/AnthropicProvider.kt:248` |
| `GeminiProvider` | `app/src/main/java/com/newoether/agora/api/gemini/GeminiProvider.kt:234` |
| `OllamaProvider` | `app/src/main/java/com/newoether/agora/api/ollama/OllamaProvider.kt:117` |
| `LocalProvider` (embedded llama.cpp) | `app/src/main/java/com/newoether/agora/api/local/LocalProvider.kt:64` |

### 3.2 `ProviderConfig` — the argument you must build

`app/src/main/java/com/newoether/agora/api/LlmProvider.kt:100`:

```kotlin
data class ProviderConfig(
    val apiKey: String,                                   // :101   required
    val modelId: String,                                  // :102   required — bare model name, NOT prefixed
    val systemPrompt: String? = null,                     // :103
    val maxContextWindow: Int = ContextBudget.DEFAULT_TOKENS,   // :105
    val codeExecutionEnabled: Boolean = false,            // :106
    val googleSearchEnabled: Boolean = false,             // :107
    val thinkingEnabled: Boolean = true,                  // :108
    val thinkingLevel: String = "medium",                 // :109
    val thinkingBudgetEnabled: Boolean = false,           // :110
    val thinkingBudgetTokens: Int = 4096,                 // :111
    val openAiServiceTier: String? = null,                // :112
    val responsesApiEnabled: Boolean = false,             // :113
    val anthropicCacheEnabled: Boolean = true,            // :114
    val anthropicCacheTtl: String = "1h",                 // :115
    val openAiWebSearchEnabled: Boolean = false,          // :116
    val baseUrl: String? = null,                          // :117
    val tools: List<ToolDefinition>? = null,              // :118
    val userPrepend: String? = null,                      // :119
    val userPostpend: String? = null,                     // :120
    val includeImages: Boolean = true,                    // :121
    val temperature: Float? = null,                       // :122
    val maxTokens: Int? = null,                           // :123
    val topP: Float? = null,                              // :124
    val frequencyPenalty: Float? = null,                  // :125
    val presencePenalty: Float? = null,                   // :126
    val promptCacheKey: String? = null,                   // :128
    val requestResolver: ProviderRequestResolver? = null, // :130
)
```

**Minimal viable call: only `apiKey` and `modelId` are required.** For a cheap reflection call the
recommended explicit set is:

```kotlin
ProviderConfig(
    apiKey            = activeKey,                     // from settings, see §3.4
    modelId           = bareModelName,                 // ModelId.parse(canonical).modelName
    systemPrompt      = reflectionSystemPrompt,        // optional but you want it
    maxContextWindow  = ContextBudget.MIN_TOKENS,      // cheapest possible budget
    thinkingEnabled   = false,                         // do not pay for reasoning tokens
    baseUrl           = providerRegistry.getEffectiveBaseUrl(providerName),  // required for custom/local
    temperature       = 0f,                            // deterministic extraction
    maxTokens         = <small>,                       // bound the output
)
```

### 3.3 The reference implementation to copy: `ConversationTitleGenerator`

This is the existing "cheap side-channel LLM call" in the codebase and the closest structural analogue
to a reflection call. File: `app/src/main/java/com/newoether/agora/viewmodel/ConversationTitleGenerator.kt`.

```kotlin
class ConversationTitleGenerator(                              // :63
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
) {
    sealed interface Result {                                  // :68
        data class Success(val title: String) : Result         // :69
        data class Failure(val reason: String) : Result        // :70
    }

    suspend fun generateAndPersist(conversationId: String): Result   // :73
}
```

The full call sequence (`:74-182`) — this is the recipe:

1. `settings.awaitInitialLoad()` (`:74`) then `providers.awaitInitialSync()` (`:75`) — both mandatory
   before reading any key or provider instance.
2. Resolve the model id with a **four-level fallback chain** (`:107-112`):
   `settings.titleGenerationModel.value` → `conversation.modelId` → last model message's model name →
   `settings.selectedModel.value`. If still blank → `Result.Failure("No title model selected")`.
3. Resolve provider: `val providerName = providers.providerForModel(prefixedModelId)` (`:114`).
4. Resolve key: `settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() } ?: settings.resolveActiveKey(providerName).orEmpty()` (`:115-116`).
5. Verify: `if (!providers.isConfigured(providerName, activeKey)) return Result.Failure(...)` (`:117-119`).
6. Strip the provider prefix: `val modelId = ModelId.parse(providers.canonicalModelId(prefixedModelId)).modelName` (`:136`).
7. Get the live instance: `val provider = providers.getInstanceOrNull(providerName) ?: return Result.Failure("Provider not registered: $providerName")` (`:137-138`).
8. Build `ProviderConfig(...)` (`:139-154`) — note `maxContextWindow = ContextBudget.MIN_TOKENS` and
   `thinkingEnabled = false` (`:151-152`).
9. Wrap in a stream scope and collect (`:172-183`):

```kotlin
HttpClient.withStreamScope(scope = null, requestTrace = requestTrace) {
    provider.generateResponse(titlePrompt, config).collect { event ->
        when (event) {
            is StreamEvent.TextChunk -> title += event.text
            is StreamEvent.Error     -> providerError = event.message
            else -> Unit
        }
    }
}
```

10. Wrap the collect in `try/catch` — rethrow `CancellationException`, catch `Exception` and return a
    `Failure` (`:189-198`). Never let a side-channel call kill the caller.

`HttpClient.withStreamScope(scope, requestTrace)` is declared at
`app/src/main/java/com/newoether/agora/api/HttpClient.kt:426`.

Two instantiation sites for this generator, both trivial:

- `app/src/main/java/com/newoether/agora/viewmodel/MessageGenerationController.kt:106` —
  `private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)`
- `app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt:157` — same, headless side.

### 3.4 The heavier, mailbox-gated path (do NOT use for reflection)

For completeness, the in-generation pass is:

`app/src/main/java/com/newoether/agora/viewmodel/ProviderPassRunner.kt`:

```kotlin
internal class ProviderPassRunner(private val json: Json = Json) {          // :55
    suspend fun run(
        identity: RunEffectIdentity,
        provider: LlmProvider,
        messages: List<ChatMessage>,
        config: ProviderConfig,
        onEvent: suspend (StreamEvent) -> Unit,
    ): ProviderPassOutcome                                                    // :58-64
}
```

Reached through `ProviderPassEffectExecutor` (`app/src/main/java/com/newoether/agora/viewmodel/ProviderPassEffectExecutor.kt:29`),
whose `execute(request: ProviderPassExecutionRequest, callbacks: ProviderPassExecutionCallbacks): ProviderPassOutcome`
(`:32-35`) requires a `RunEffect.StartProviderPass` authorization from the conversation mailbox
(`:36-40`) — it throws `CancellationException` without one. It is driven from
`GenerationManager` (`app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt:412`, via
`private val providerPassEffects = ProviderPassEffectExecutor()` at `:71`).

**A reflection call must not go through this path** — it needs a conversation, a run id, a mailbox, and
persisted messages. Use the `ConversationTitleGenerator` shape instead.

### 3.5 Reading the "current" provider and model from settings

| What | How | Where |
|---|---|---|
| Current model (prefixed, e.g. `openai:gpt-4o`) | `val selectedModel: StateFlow<String>` | `app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt:98` (defaults to `Constants.EXAMPLE_MODEL_ID`) |
| Provider for a model id | `fun providerForModel(modelId: String): String` | `app/src/main/java/com/newoether/agora/viewmodel/ProviderRegistry.kt:209` |
| Canonicalise legacy prefixed ids | `fun canonicalModelId(modelId: String): String` | `ProviderRegistry.kt:226` |
| Live provider instance (throws) | `fun getInstance(name: String): LlmProvider` | `ProviderRegistry.kt:171` |
| Live provider instance (nullable — use this) | `fun getInstanceOrNull(name: String): LlmProvider?` | `ProviderRegistry.kt:177` |
| Effective base URL (handles custom-endpoint resolution) | `fun getEffectiveBaseUrl(providerName: String): String?` | `ProviderRegistry.kt:184` |
| Is the provider usable with this key | `fun isConfigured(providerName: String, activeKey: String): Boolean` | `ProviderRegistry.kt:199` |
| Frozen provider map for one run | `fun generationSnapshot(): Map<String, LlmProvider>` | `ProviderRegistry.kt:158` |
| Raw live map | `val all: Map<String, LlmProvider>` | `ProviderRegistry.kt:155` |
| Active API key (sync, `.value`-based) | `fun resolveActiveKey(provider: String): String?` | `app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt:716` |
| Active API key (**awaits DataStore — prefer on the request path**) | `suspend fun awaitActiveKey(provider: String): String?` | `SettingsRepository.kt:727` |
| Wait for DataStore to load | `suspend fun awaitInitialLoad()` | `SettingsRepository.kt:92` |
| Wait for custom providers to register | `suspend fun awaitInitialSync()` | `ProviderRegistry.kt:312` |
| Key/base-url sources | `val apiKeys: StateFlow<List<ApiKeyEntry>>` `:114`, `val activeApiKeyIds: StateFlow<Map<String, String>>` `:115`, `val providerBaseUrls: StateFlow<Map<String, String>>` `:147` | `SettingsRepository.kt` |

`ProviderRegistry` is constructed once in the container:
`app/src/main/java/com/newoether/agora/di/AppContainer.kt:144` —
`ProviderRegistry(settingsRepository, conversationRepository, localProvider, appScope)`; class decl at
`ProviderRegistry.kt:127`. Built-in provider map at `ProviderRegistry.kt:133-143`.

**Rule:** always `awaitActiveKey` (not `resolveActiveKey`) on a code path that can run during app
startup — the race is documented verbatim at `SettingsRepository.kt:719-726` (blank key → empty
`Authorization` header → intermittent 401).

---

## 4. COMPOSE NAVIGATION + SETTINGS ENTRY

### 4.1 There is NO NavHost — this is the single most important finding

Verified by exhaustive search:

```
rg -n "NavHost|rememberNavController|androidx\.navigation" app/src/    →  no matches
```

`androidx.navigation:navigation-compose` **is** declared as a dependency
(`gradle/libs.versions.toml:8` version `2.9.8`, `:38` artifact; `app/build.gradle.kts:167`
`implementation(libs.androidx.navigation.compose)`) but **no source file imports or uses it**. Routes,
route strings, deep links, and back stacks do not exist. There is no `sealed class Route`, no
`object Routes`, no `composable("...")` builder anywhere in `app/src/main/`.

Navigation is instead **boolean-state overlays with a single-owner presentation stack**.

#### The top-level mechanism

`app/src/main/java/com/newoether/agora/TopLevelPresentation.kt`:

```kotlin
enum class TopLevelPresentation { CHAT, SETTINGS, TASKS, REMOTE, MEDIA_PREVIEW, TEXT_PREVIEW }   // :8-15

@Stable
internal class TopLevelPresentationState(
    initialOwner: TopLevelPresentation = TopLevelPresentation.CHAT,
    private val onOwnerChanged: (TopLevelPresentation) -> Unit = {},
) {                                                                       // :19-22
    var owner by mutableStateOf(initialOwner); private set                // :26-27
    fun present(presentation: TopLevelPresentation)                       // :33
    fun release(presentation: TopLevelPresentation): Boolean              // :42
}
```

#### The "NavHost" composable

`app/src/main/java/com/newoether/agora/MainActivity.kt:273`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(
    viewModel: ChatViewModel,
    settingsManager: SettingsManager,
    notificationConversationId: kotlinx.coroutines.flow.StateFlow<String?>,
    onNotificationConversationConsumed: (String) -> Unit,
)
```

This is the de-facto navigation graph. Its structure:

| Element | Line |
|---|---|
| `fun MainNavigation(...)` declaration | `MainActivity.kt:273` |
| `var showSettings by rememberSaveable { mutableStateOf(false) }` | `MainActivity.kt:299` |
| `var showTasks by rememberSaveable { mutableStateOf(false) }` | `MainActivity.kt:300` |
| `var showRemote by rememberSaveable { mutableStateOf(false) }` | `MainActivity.kt:301` |
| `TopLevelPresentationState(...)` construction with `initialOwner` `when` block | `MainActivity.kt:302-314` |
| `onOpenSettings = { topLevelPresentation.present(TopLevelPresentation.SETTINGS); showSettings = true }` | `MainActivity.kt:460-463` |
| `onOpenRemote = { … }` | `MainActivity.kt:464-467` |
| `onOpenTasks = { … }` | `MainActivity.kt:468-471` |
| `SettingsOverlayHost(visible = showSettings, …) { SettingsScreen(viewModel, onBack = { showSettings = false }) }` | `MainActivity.kt:511-524` |
| `SettingsOverlayHost(visible = showTasks, …) { TasksScreen(...) }` | `MainActivity.kt:526-563` |
| `FullScreenMediaPreviewDialog(...)` | `MainActivity.kt:566` |
| text preview `AnimatedVisibility` | `MainActivity.kt:611` |

`SettingsOverlayHost` is the reusable full-screen overlay host (slide-in/scrim animation, its own
composition, `content: @Composable () -> Unit`):
`app/src/main/java/com/newoether/agora/SettingsOverlayHost.kt:71` —
`internal fun SettingsOverlayHost(visible: Boolean, onDismiss: () -> Unit, onEnterFinished: () -> Unit = {}, onExitFinished: () -> Unit = {}, content: @Composable () -> Unit)`.

### 4.2 Where an "Adaptation History" screen would attach

`ROADMAP.md:35-40` already specifies this target: *"Phase 4 — Adaptation History + Auto-Rollback:
Settings → Adaptation History (list, before/after diff, per-entry Undo, status chips)"*. Two attachment
points, and the second is clearly the intended one:

**Option A — second-level settings page (recommended, matches the ROADMAP wording).**
Add a `"adaptation"` category key. Three edits, all in one file:

1. `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt:266-270` — the `settings_group_memory_data`
   `SettingsGroupData` list, currently:
   ```kotlin
   SettingsGroupData(titleRes = R.string.settings_group_memory_data, items = listOf(     // :266
       SettingsCategory("memory",     R.string.settings_memory,     R.string.settings_memory_desc,     Icons.Default.Description),  // :267
       SettingsCategory("skills",     R.string.settings_skills,     R.string.settings_skills_desc,     Icons.Default.Extension),    // :268
       SettingsCategory("datacontrol",R.string.settings_data_control,R.string.settings_data_control_desc,Icons.Default.Storage),   // :269
   )),                                                                                    // :270
   ```
2. `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt:323-348` — the `when (category)`
   dispatch block. Add a branch alongside `"skills" -> SettingsSkillsPage(viewModel, onBack = { selectedCategory = null })` (`:340`).
3. New file `app/src/main/java/com/newoether/agora/ui/settings/SettingsAdaptationHistoryPage.kt` with the
   same shape as `fun SettingsSkillsPage(viewModel: ChatViewModel, onBack: () -> Unit)`
   (`app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt:65-68`).

Supporting types and scaffolding for the list screen:

| Piece | Location |
|---|---|
| `private data class SettingsCategory(val key: String, @StringRes val titleRes: Int, @StringRes val descriptionRes: Int, val icon: ImageVector? = null, @DrawableRes val iconRes: Int? = null)` | `SettingsScreen.kt:223-229` |
| `private data class SettingsGroupData(val titleRes: Int? = null, val items: List<SettingsCategory>)` | `SettingsScreen.kt:231-234` |
| `private val baseSettingsGroups = listOf(...)` — the group list | `SettingsScreen.kt:236` |
| `private val developerSettingsGroup` (conditionally appended) | `SettingsScreen.kt:277` |
| `private val aboutSettingsGroup` | `SettingsScreen.kt:289` |
| `fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit)` | `SettingsScreen.kt:298` |
| `settingsGroups` assembly (`remember(developerOptionsEnabled) { buildList { addAll(baseSettingsGroups); … } }`) | `SettingsScreen.kt:301-307` |
| row click → `selectedCategory = cat.key` | `SettingsScreen.kt:387` |
| page container `fun CollapsingSettingsLazyScaffold(title, onBack, modifier, listState, contentHorizontalPadding, contentBottomPadding, actions, floatingActionButton, content: LazyListScope.() -> Unit)` | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScaffold.kt:236` |
| reusable group/item primitives: `SettingsGroupColumn`, `SettingsGroup`, `SettingsIconContent`, `SettingsItem`, `SettingsAddItem` | `SettingsScreen.kt:50`, `:65`, `:107`, `:127`, `:187` |
| strings to add next to `settings_skills` / `settings_skills_desc` | `app/src/main/res/values/strings.xml:729-730` (group title `settings_group_memory_data` at `:360`) |

**Option B — new top-level overlay** (only if the screen must be reachable outside Settings, e.g. from
the Phase 3 "N memories updated" notification tap, per `ROADMAP.md:37`). That requires:
`TopLevelPresentation` enum entry (`TopLevelPresentation.kt:8-15`), a `showAdaptation` state +
`SettingsOverlayHost` block in `MainNavigation` (`MainActivity.kt:299-301`, `:511-524` pattern), and
`handleNavigationIntent`/deep-link handling (`MainActivity.kt:262-268`, which currently only parses
`agora://conversation/<id>`).

Note the ROADMAP explicitly wants the notification tap wired to this screen, so a hybrid (second-level
page **plus** a `TopLevelPresentation` entry that opens Settings pre-selected on the `"adaptation"` key)
is the design that satisfies both requirements with the least new machinery.

### 4.3 Back handling

There is no back stack. Each page owns its own `BackHandler`:

- Settings: `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt:310-316` — if
  `selectedCategory != null` clear it, else call `onBack()`.
- Any new page must follow the same pattern: `BackHandler { onBack() }`.

---

## 5. Addendum — in-flight `autopilot/` package (uncommitted, observed mid-session)

While this analysis was being written, a **separate, concurrent** change appeared in the working tree:
an untracked package `app/src/main/java/com/newoether/agora/autopilot/`. It was not present at the start
of this session (the first `git status --porcelain` was empty) and is **not committed** — treat
everything in this addendum as a moving target, not as verified stable API.

Files present at time of writing (all untracked, `git status --porcelain` shows `?? app/src/main/java/com/newoether/agora/autopilot/`):

| File | Declares |
|---|---|
| `app/src/main/java/com/newoether/agora/autopilot/AdaptationLog.kt` | `@Entity(tableName = "adaptation_log") data class AdaptationEntry(...)` at `:21`; `@Dao interface AdaptationLogDao` at `:63`; `@Database(entities = [AdaptationEntry::class], version = 1, exportSchema = false) abstract class AdaptationDatabase : RoomDatabase()` at `:93` |
| `app/src/main/java/com/newoether/agora/autopilot/MemoryApplier.kt` | `data class AdaptationTarget(val store: String, val fileName: String)` at `:15`; `class MemoryApplier(private val memoryManager: MemoryManager, private val skillManager: SkillManager, private val log: AdaptationLogDao)` at `:32` with `fun snapshot(...)` `:38`, `suspend fun apply(...)` `:51`, `suspend fun undo(...)` `:90` |
| `app/src/main/java/com/newoether/agora/autopilot/ReflectionCaller.kt` | `class ReflectionCaller(private val settings: SettingsRepository, private val providers: ProviderRegistry, private val configuredModel: () -> String? = { null })` at `:25`; `fun resolveModelId(): String?` `:32`; `suspend fun reflect(transcript: String, existingFiles: List<String>): String?` `:47` |
| `app/src/main/java/com/newoether/agora/autopilot/ReflectionProtocol.kt` | `const val PROVENANCE_TAG = "<!-- hermes:autopilot -->"` at `:14` |

Three points that matter for the parent task:

1. **`ReflectionCaller` independently arrived at the §3 recipe.** Its `reflect()` body is the
   `ConversationTitleGenerator` pattern verbatim: `providerForModel` → `awaitActiveKey` /
   `resolveActiveKey` fallback → `isConfigured` guard → `getInstanceOrNull` →
   `ProviderConfig(maxContextWindow = ContextBudget.MIN_TOKENS, thinkingEnabled = false, baseUrl = getEffectiveBaseUrl(...))`
   → `HttpClient.withStreamScope(scope = null, requestTrace = null) { provider.generateResponse(...).collect { … } }`
   (`ReflectionCaller.kt:48-77`). It also picks a **different model resolution order** than the title
   generator: `configuredModel()` → `settings.titleGenerationModel` → `settings.selectedModel`
   (`:33-38`). Its contract is documented as **never throwing** and returning `null` on every failure
   (`:44-45`, `:23`).
2. **It is not wired in.** Nothing outside `com.newoether.agora.autopilot` references
   `AdaptationDatabase`, `AdaptationLogDao`, `MemoryApplier`, or `ReflectionCaller`
   (verified with `rg -n "AdaptationLogDao|AdaptationDatabase|MemoryApplier|AdaptationEntry" --glob '*.kt' app/src/`).
   There is **no `AppContainer` entry** for any of them — so §1.3's DI map is still complete for the
   committed code. `AdaptationDatabase.get(context)` is its own `@Volatile` singleton
   (`AdaptationLog.kt:99-110`), consistent with the file's N2 comment that Agora's Room database is
   never extended (`AdaptationLog.kt:17-18`).
3. **The screen's data source already exists.** `AdaptationLogDao.observe(): Flow<List<AdaptationEntry>>`
   (`AdaptationLog.kt:74`) and `historyFor(file: String, store: String)` (`:83`) are what an
   "Adaptation History" page (§4.2) would collect. Status vocabulary for the status chips is fixed at
   `AdaptationLog.kt:48-58`: `applied`, `auto_rolled_back`, `user_rolled_back`, `needs_revision`;
   `feedbackFlags` (`:35`, incremented by `:80`) is the input to the `ROADMAP.md:38` correction
   heuristic. So a new `SettingsAdaptationHistoryPage` should take an `AdaptationLogDao` (or the
   `AdaptationDatabase` singleton), **not** a new data layer.

**Caveat:** these four files were written during this session and may already differ from the above by
the time this document is read. Re-run the `rg` commands above before relying on any signature in
this section.

---

## 6. Sources

Every file path cited above, for re-verification:

```
app/build.gradle.kts
app/src/main/java/com/newoether/agora/AgoraApplication.kt
app/src/main/java/com/newoether/agora/MainActivity.kt
app/src/main/java/com/newoether/agora/SettingsOverlayHost.kt
app/src/main/java/com/newoether/agora/TopLevelPresentation.kt
app/src/main/java/com/newoether/agora/api/EmbeddingClient.kt
app/src/main/java/com/newoether/agora/api/HttpClient.kt
app/src/main/java/com/newoether/agora/api/LlamaEngine.kt
app/src/main/java/com/newoether/agora/api/LlmProvider.kt
app/src/main/java/com/newoether/agora/api/LocalModelRuntime.kt
app/src/main/java/com/newoether/agora/api/ProviderDefaults.kt
app/src/main/java/com/newoether/agora/api/anthropic/AnthropicProvider.kt
app/src/main/java/com/newoether/agora/api/gemini/GeminiProvider.kt
app/src/main/java/com/newoether/agora/api/local/LocalProvider.kt
app/src/main/java/com/newoether/agora/api/ollama/OllamaProvider.kt
app/src/main/java/com/newoether/agora/api/openai/BaseOpenAiProvider.kt
app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt
app/src/main/java/com/newoether/agora/autopilot/AdaptationLog.kt
app/src/main/java/com/newoether/agora/autopilot/MemoryApplier.kt
app/src/main/java/com/newoether/agora/autopilot/ReflectionCaller.kt
app/src/main/java/com/newoether/agora/autopilot/ReflectionProtocol.kt
app/src/main/java/com/newoether/agora/data/DescriptionMetadataStore.kt
app/src/main/java/com/newoether/agora/data/EmbeddingIndexer.kt
app/src/main/java/com/newoether/agora/data/EmbeddingModelConfig.kt
app/src/main/java/com/newoether/agora/data/MemoryManager.kt
app/src/main/java/com/newoether/agora/data/SettingsManager.kt
app/src/main/java/com/newoether/agora/data/SkillManager.kt
app/src/main/java/com/newoether/agora/data/SkillMarkdownImport.kt
app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt
app/src/main/java/com/newoether/agora/di/AppContainer.kt
app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt
app/src/main/java/com/newoether/agora/tool/RagToolProvider.kt
app/src/main/java/com/newoether/agora/tool/SkillToolProvider.kt
app/src/main/java/com/newoether/agora/ui/chat/ContextProjectionState.kt
app/src/main/java/com/newoether/agora/ui/settings/SettingsScaffold.kt
app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt
app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt
app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt
app/src/main/java/com/newoether/agora/viewmodel/ChatViewModelFactory.kt
app/src/main/java/com/newoether/agora/viewmodel/ConversationTitleGenerator.kt
app/src/main/java/com/newoether/agora/viewmodel/GenerationContracts.kt
app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt
app/src/main/java/com/newoether/agora/viewmodel/GenerationPolicies.kt
app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt
app/src/main/java/com/newoether/agora/viewmodel/GenerationToolExecutor.kt
app/src/main/java/com/newoether/agora/viewmodel/MessageGenerationController.kt
app/src/main/java/com/newoether/agora/viewmodel/ProviderPassEffectExecutor.kt
app/src/main/java/com/newoether/agora/viewmodel/ProviderPassRunner.kt
app/src/main/java/com/newoether/agora/viewmodel/ProviderRegistry.kt
app/src/main/java/com/newoether/agora/viewmodel/RagManager.kt
app/src/main/res/values/strings.xml
gradle/libs.versions.toml
ROADMAP.md
```

Note on §6: the four `autopilot/` paths are **untracked, uncommitted files written concurrently during
this session** — they exist on disk (verified) but are not part of the committed tree at `2205ac56`.
Every other path above is tracked and committed.

The one path referenced but deliberately **not** listed as existing is
`app/src/main/java/com/newoether/agora/ui/settings/SettingsAdaptationHistoryPage.kt` — it does **not**
exist; it is named in §4.2 purely as the file to create.

Re-verification commands used:

```bash
# every cited path exists
rg -n "<symbol>" <path>

# no NavHost / type-safe routes anywhere in main sources
rg -n "NavHost|rememberNavController|androidx\.navigation" app/src/
```

---

## 7. Summary of the four answers

1. **SkillManager** — `app/src/main/java/com/newoether/agora/data/SkillManager.kt:10`, ctor
   `SkillManager(context: Context)`. 11 public members (1 `StateFlow<Long>` + `SkillFileInfo` +
   9 functions), all `@Synchronized`, lines 16–198. Constructed exactly once at
   `app/src/main/java/com/newoether/agora/di/AppContainer.kt:74` (`by lazy`), reachable at runtime via
   `ChatViewModel.skillManager` (`ChatViewModel.kt:48`) or `AgoraApplication.requireContainer()`
   (`AgoraApplication.kt:84`). Manual DI, no framework.
2. **Embedding** — remote: `object EmbeddingClient.computeEmbedding(text, apiKey, model, baseUrl): FloatArray?`
   at `app/src/main/java/com/newoether/agora/api/EmbeddingClient.kt:30`; local:
   `object LlamaEngine.computeEmbedding(text, modelPath): FloatArray?` at
   `app/src/main/java/com/newoether/agora/api/LlamaEngine.kt:29`. Config from
   `RagManager.activeEmbeddingModel` (`RagManager.kt:53`), key from
   `RagManager.resolveEmbeddingApiKey()` (`RagManager.kt:542`) or the more correct
   `EmbeddingCacheWorker.resolveEmbeddingConfig` (`EmbeddingCacheWorker.kt:399`). Reference call site:
   `RagToolProvider.kt:417-435`.
3. **Cheap reflection call** — `LlmProvider.generateResponse(messages: List<ChatMessage>, config: ProviderConfig): Flow<StreamEvent>`
   at `app/src/main/java/com/newoether/agora/api/LlmProvider.kt:525`. Minimal args: `ProviderConfig(apiKey, modelId, systemPrompt, baseUrl, thinkingEnabled=false, maxContextWindow=ContextBudget.MIN_TOKENS)`.
   Copy `ConversationTitleGenerator.generateAndPersist` (`ConversationTitleGenerator.kt:73`) wholesale —
   it already does `awaitInitialLoad` → `awaitInitialSync` → model fallback chain → `providerForModel` →
   `awaitActiveKey` → `isConfigured` → `getInstanceOrNull` → `withStreamScope` → collect.
   Do **not** route through `ProviderPassEffectExecutor` (needs a mailbox).
4. **Navigation / settings entry** — **no NavHost exists**; `navigation-compose` is a declared but unused
   dependency (`app/build.gradle.kts:167`). Navigation is boolean overlay state in
   `fun MainNavigation(...)` at `app/src/main/java/com/newoether/agora/MainActivity.kt:273`
   (`showSettings`/`showTasks`/`showRemote` at `:299-301`, overlays at `:511`, `:526`), governed by
   `TopLevelPresentation` (`TopLevelPresentation.kt:8`). New "Adaptation History" attaches as a
   second-level settings page: add a `SettingsCategory` to `baseSettingsGroups` at
   `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt:266-270`, add a `when` branch at
   `SettingsScreen.kt:323-348`, and create `SettingsAdaptationHistoryPage.kt` modelled on
   `SettingsSkillsPage.kt:65`.
