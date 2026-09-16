<div align="center">
  <img src="app/src/main/assets/agora_transparent_large.png" alt="Hermes X" width="120" />

  # Hermes X

  **An Android BYOK LLM client with agentic tools — and a Wear OS companion app.**

  A fork of [Agora](https://github.com/newo-ether/Agora) by the Agora authors (MIT).
  Maintained by **Michael** ([`michaelxdips`](https://github.com/michaelxdips)).
  Upstream attribution is unchanged — see [`NOTICE.md`](NOTICE.md).

  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-blue.svg)](https://kotlinlang.org/)

  <img src="assets/feature_graphic.png" alt="A BYOK AI app that takes back your data sovereignty." width="100%" />
</div>

> **Not published on any store.** Upstream Agora ships on F-Droid and Google Play; this fork does not.
> It builds from source, installs side by side with upstream Agora (`com.hermes.app` vs
> `com.newoether.agora`), and the upstream user manual still applies to the shared product.

## What this fork adds

| Addition | Where | Notes |
|---|---|---|
| **Autopilot memory** | `app/src/main/java/com/newoether/agora/autopilot/` | After a conversation goes idle, a reflection pass proposes memory facts and writes them with a before/after snapshot in its own Room DB (`hermes_autopilot.db`). Never extends upstream's DB. |
| **Adaptation History + undo** | Settings → Memory & Data → *Adaptation History* | Every change is listed with a diff and a one-tap undo; a correction heuristic rolls back a bad adaptation automatically. Daily cap of 5. |
| **Persona system** | `personas/`, `app/src/main/assets/personas/` | Vendored Caveman and Ponytail rule texts (MIT, pinned refs in `personas/upstream.lock`) injected as delimited blocks into the active-memory file, removed without trace when switched off. |
| **Wear OS module** | `wear/` | Standalone watch app: BYOK on the watch itself, or pairing with the phone app over the Data Layer. Typing, voice input, an offline queue with exactly-once delivery, and held-question cards. |
| **Upstream sync automation** | `scripts/upstream_sync.sh`, `.github/workflows/upstream-sync.yml` | Dry-run by default; the merge is scripted and the CI job runs it on a schedule. |
| **Touchpoint guard** | `scripts/touchpoint_guard.sh`, `UPSTREAM_TOUCHPOINTS.md` | Upstream files are read-mostly. Every deliberate edit to one is registered with a line budget, and the guard fails the build when an unregistered edit or a blown budget appears. |

Rebranding is confined to `applicationId` (`com.hermes.app`), the display name, and resource
overlays — no upstream behaviour is rewritten. The full list of edited upstream files, with reasons
and budgets, is in [`UPSTREAM_TOUCHPOINTS.md`](UPSTREAM_TOUCHPOINTS.md).

## Screenshots

Captured on the `hermes_x86_64` emulator and the `hermes_wear5` AVD during phase verification —
test-build screenshots, not marketing shots.

<table>
<tr>
<td width="50%"><img src="evidence/phase0-6/08-adaptation-history.png" alt="Adaptation History" width="100%"/></td>
<td width="50%"><img src="evidence/p8-03-wear-chat-release.png" alt="Wear OS chat" width="100%"/></td>
</tr>
<tr>
<td><b>Phone —</b> Adaptation History: the autopilot toggle, the daily cap, and the undo list.</td>
<td><b>Watch —</b> the chat screen: composer, voice, and the held-question queue.</td>
</tr>
</table>

More evidence lives in [`evidence/`](evidence/) and is indexed in [`STATUS.md`](STATUS.md).

## Inherited from upstream

Everything below is Agora's, unchanged, and is documented in full in the
[upstream user manual](https://newo-ether.github.io/Agora/) and [`ARCHITECTURE.md`](ARCHITECTURE.md):

- **Nine built-in provider types:** OpenAI, Anthropic, Google Gemini, DeepSeek, Qwen/DashScope, OpenRouter, Groq, Ollama, and Local llama.cpp; custom endpoints support OpenAI-compatible, Google, or Anthropic protocols.
- **Tree-structured conversations:** edit or regenerate earlier messages without discarding alternative branches.
- **Token-budget context:** 4K–1M estimated-token budgets and non-destructive Compact capsules that retain a verbatim recent suffix.
- **Agentic tools:** web search, memory, past-conversation RAG, image generation, MCP servers, Tasks/Loops, remote shell/files, durable Conch jobs, and an F-Droid Alpine sandbox.
- **Local intelligence:** GGUF chat models and local embeddings through llama.cpp.
- **Portable data:** versioned `.agora` ZIP archives, ChatGPT/Claude imports, and scheduled backups.
- **Customizable UI:** Material 3 themes, fonts, haptics, thinking/tool presentation, and 12 explicit interface languages plus system default.

## Build from source

Targets **Android SDK 36** with **JDK 21**. Submodules are required (`thirdparty/llama.cpp`,
`thirdparty/proot`).

```bash
git clone --recurse-submodules https://github.com/michaelxdips/Agora.git
cd Agora

export JAVA_HOME=<path-to-jdk-21>
export ANDROID_HOME=<path-to-android-sdk>

./gradlew assembleFdroidDebug          # or assemblePlayDebug
./gradlew :app:testFdroidDebugUnitTest # JVM unit tests
bash scripts/touchpoint_guard.sh       # upstream-edit guard
```

Release builds sign through `local.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). That file and any `*.jks` are excluded via `.git/info/exclude` and are never
committed — a fresh clone builds debug only until you supply your own key.

The F-Droid flavor needs `./build-proot.sh` to have run before packaging (the CI workflow does this
for you). The `play` flavor does not.

## Documentation

| Document | What it is |
|---|---|
| [`AGENTS.md`](AGENTS.md) | The fork's ground rules. Read this before touching anything. |
| [`ROADMAP.md`](ROADMAP.md) | Phases delivered, with the evidence table for each. |
| [`STATUS.md`](STATUS.md) | The evidence log: command output, test counts, APK hashes, and the audit findings that were not fixed. |
| [`CODE_MAP.md`](CODE_MAP.md) | One row per file this fork owns, with a "worth a close read?" column. |
| [`UPSTREAM_TOUCHPOINTS.md`](UPSTREAM_TOUCHPOINTS.md) | Every edited upstream file and its line budget. |
| [`UPSTREAM_SYNC.md`](UPSTREAM_SYNC.md) | How an upstream merge is performed here. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Upstream's runtime, persistence, providers, tools, and data flows. |
| [`V2_BACKLOG.md`](V2_BACKLOG.md) | Candidate work with a cost/benefit table, including the "never" column. |
| [`PRIVACY.md`](PRIVACY.md) · [`NOTICE.md`](NOTICE.md) | Privacy policy and attribution. |

The audit and mandate documents this fork accumulated while it was being built (`GAP_ANALYSIS.md`,
`AUDIT_PLAN.md`, `AUDIT_REPORT.md`, `CLEANUP_SCAN.md`, `CLEANUP_REPORT.md`, `HANDOVER.md`,
`MEGA_PROMPT_*.md`) are deliberately **not** in the repository. Where one of them is cited as the
source of a recorded result, `STATUS.md` says so.

## Contributing

Issues and pull requests are welcome. Two rules matter more than the rest:

1. **`main` must always build.** Work on a branch and merge only after verification.
2. **Never disable a test or a guard to make a gate pass.**

## License

MIT, unchanged from upstream — see [`LICENSE`](LICENSE). The fork does not relicense any upstream
file. The bundled persona rule texts are third-party MIT works, credited in [`NOTICE.md`](NOTICE.md).
