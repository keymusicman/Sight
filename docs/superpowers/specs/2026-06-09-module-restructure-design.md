# Module Restructure & Docs Cleanup

**Date:** 2026-06-09

## Goal

Group the eight root-level Gradle modules into purposeful directories, and delete stale docs. All Gradle module paths (`:graph-annotations`, `:idea-plugin`, etc.) stay unchanged — only the physical directory layout moves.

## Target Directory Layout

```
android/
  graph-annotations/      ← :graph-annotations
  graph-processor/        ← :graph-processor
idea-plugin/
  plugin/                 ← :idea-plugin
  ipc/                    ← :ipc
  render-worker/          ← :render-worker
shared/
  graph-renderer/         ← :graph-renderer
  graph-ui/               ← :graph-ui
samples/
  android/                ← standalone Gradle project (was sample-android/)
web-server/               ← :web-server (stays at root)
```

### Rationale

| Group | Modules | Why |
|-------|---------|-----|
| `android/` | annotations, processor | What Android developers add to their projects |
| `idea-plugin/` | plugin, ipc, render-worker | Tightly coupled IntelliJ integration |
| `shared/` | renderer, ui | Internal libraries shared by plugin and web-server |
| `samples/android/` | standalone project | Scales to `samples/web/` etc. in future |
| root | web-server | Standalone service, doesn't belong to any group |

## Gradle Path Strategy

All modules are aliased in `settings.gradle.kts` so no `build.gradle.kts` or type-safe accessor references change:

```kotlin
include(":graph-annotations")
project(":graph-annotations").projectDir = file("android/graph-annotations")

include(":graph-processor")
project(":graph-processor").projectDir = file("android/graph-processor")

include(":graph-renderer")
project(":graph-renderer").projectDir = file("shared/graph-renderer")

include(":graph-ui")
project(":graph-ui").projectDir = file("shared/graph-ui")

include(":idea-plugin")
project(":idea-plugin").projectDir = file("idea-plugin/plugin")

include(":ipc")
project(":ipc").projectDir = file("idea-plugin/ipc")

include(":render-worker")
project(":render-worker").projectDir = file("idea-plugin/render-worker")

include(":web-server")
// web-server stays at root, no alias needed
```

`sample-android/` is a standalone Gradle project (not included in root `settings.gradle.kts`). Its `includeBuild("..")` still points to root, and module references (`projects.graphAnnotations` etc.) resolve via the aliased paths above — no changes needed inside `sample-android/`.

## Docs to Delete

| File | Reason |
|------|--------|
| `Claude_night_run.md` | Session transcript from one autonomous run; no ongoing value |
| `render-experiments.md` | Historical perf table; conclusions are in the code |
| `render-timings-chart.md` | Raw chart data for render-experiments; no standalone use |
| `render-worker/SPIKE_NOTES.md` | Marked DONE; knowledge captured in COMPOSABLE_RENDERING.md and code |
| `idea-plugin/README.md` | IntelliJ Platform Template boilerplate; not about Sight |
| `CODE_REFERENCE.md` | Outdated: references non-existent `build-logic/`, old `appflower` brand, v3 schema that doesn't match current v2 implementation |

## Docs to Reorganize

| Current | Action | Reason |
|---------|--------|--------|
| `README.md` | Trim + update | Move Cloud Run/Docker/GCS ops section to `web-server/README.md`; pull Mermaid pipeline diagram from CODE_REFERENCE in its place; update module table for new directory layout |
| `EXAMPLE.md` | Move → `docs/annotation-semantics.md` | It's a behavioral spec (resolution rules, `dropUnconnected`, disambiguation logic), not examples; valuable contributor reference |

## Docs to Keep (no changes)

- `CLAUDE.md` — needs path updates after module move (see below)
- `TODO_ideas.md` — feature backlog
- `idea-plugin/COMPOSABLE_RENDERING.md` — hard constraints, valid
- `idea-plugin/LOCAL_REPRO.md` — repro harness docs, valid

## Files Requiring Path Updates

After the physical move, these files reference old paths and must be updated:

- **`settings.gradle.kts`** — replace flat `include` calls with aliased versions (see above)
- **`CLAUDE.md`** — module directory paths in the architecture table and key files table
- **`README.md`** — module table directory paths; add Mermaid pipeline diagram; remove ops section
- **`sample-android/settings.gradle.kts`** — verify `includeBuild("..")` still resolves; no change expected

## Out of Scope

- Renaming Gradle module identifiers (`:graph-annotations` etc. stay as-is)
- Changes to `build.gradle.kts` inter-module dependencies
- Any source code changes