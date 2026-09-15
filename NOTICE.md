# NOTICE

This repository is a **fork** of [Agora](https://github.com/newo-ether/Agora),
© the Agora authors, licensed under the MIT License.

* Upstream source: <https://github.com/newo-ether/Agora>
* Upstream licence text: `LICENSE` (unmodified, see the file itself)
* Fork owner: **Michael** (`michaelxdips`) — the fork's maintainer. The product is distributed under
  the display name **Hermes X**; "Hermes X" is a name, not a licence claim, and upstream Agora remains
  the upstream authors' work. The autopilot feature set is described in `ROADMAP.md`, and the watch
  module in `STATUS.md` (Phase 7 + Phase 12).
* Fork source: <https://github.com/michaelxdips/Agora>

The maintainer is surfaced in the product itself, not only here: `HermesBuildInfo` (phone) and
`WearBuildInfo` (watch) are the single source of the display name and the maintainer, and both are
shown on screen — Settings → About on the phone, and the Debug panel on the watch.

Bundled third-party components keep their own licences, unchanged from upstream:
`thirdparty/llama.cpp`, `thirdparty/proot`, `thirdparty/talloc` (git submodules — see
`.gitmodules`), and the Gradle dependencies declared in `gradle/libs.versions.toml`.

No upstream file is relicensed by this fork. Modified upstream files are listed, with reasons, in
`UPSTREAM_TOUCHPOINTS.md`.

## Persona rule texts (Phase 6)

The Caveman and Ponytail persona rule texts are vendored, unmodified apart from dropping the upstream
YAML frontmatter (registry metadata, not instruction text). Vendored copies live in `personas/`, with
the pinned ref and file hash recorded in `personas/upstream.lock`.

* **Caveman** — <https://github.com/JuliusBrussee/caveman>, © Julius Brussee, **MIT License**.
  Vendored from tag `v2.6.0`, path `skills/caveman/SKILL.md`.
  Only the skill rule text is used. The repository also ships engine-linked directories
  (`engine/`, `proxy/`, `rewriter/`, `browse/`, `mcp/`, `shrink/`, `shared/platform/`) under the
  **Business Source License 1.1**; **none of those are vendored, linked, or executed** by this fork.
* **Ponytail** — <https://github.com/DietrichGebert/ponytail>, © DietrichGebert, **MIT License**.
  Vendored from tag `v4.10.0`, path `skills/ponytail/SKILL.md`.

Both texts are injected into the user's own active-memory file as delimited blocks and are removed
without trace when the persona is switched off. `NOTICE` is updated by
`scripts/persona_update.sh`'s flow whenever a vendored ref is bumped.
