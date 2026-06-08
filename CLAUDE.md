# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run web server (http://localhost:8080)
./gradlew :web-server:run

# Build all modules
./gradlew build

# Clean build
./gradlew clean build

# Run tests
./gradlew test

# Run tests for a specific module
./gradlew :graph-renderer:test
./gradlew :graph-processor:test

# Build IntelliJ plugin
./gradlew :idea-plugin:buildPlugin

# Generate graph fragment from sample Android project
cd sample-android && ./gradlew :app:kspDebugKotlin
```

## Module Architecture

The project is a multi-module Kotlin monorepo. All modules target JVM only.

```
graph-annotations  ←  (consumer Android project, annotates @Composable screens)
graph-processor    ←  (KSP processor, generates app-graph-fragment.json)
                                    ↓
                         app-graph-fragment.json
                                    ↓
graph-renderer  →  graph-ui  →  idea-plugin (IntelliJ plugin)
     ↓
 web-server
```

### graph-annotations
Pure JVM library. Defines the three source-retention annotations consumer Android projects apply to their screens:
- `@AppFlowGraph` — marks the graph entry object; declares the entry subgraph
- `@AppFlowScreen` — marks a `@Preview` composable as a screen node (subgraph, id, isRoot)
- `@AppFlowTransition` — declares a navigation edge (from/to screen+subgraph, trigger)

### graph-processor
KSP symbol processor. Scans for `@AppFlowGraph`, `@AppFlowScreen`, `@AppFlowTransition` and writes `build/graph/app-graph-fragment.json`. Consumer projects pass `projectRoot` and `moduleName` as KSP options.

### graph-renderer
Pure JVM library — no UI dependencies. Owns:
- **Data models** (`model/NavGraph.kt`): `AppGraph`, `Subgraph`, `Screen`, `Connection`, `ConnectionEndpoint`
- **JSON loading** (`loader/GraphLoader.kt`): reads `{projectPath}/app/build/graph/app-graph.json`
- **Layout algorithm** (`model/LayoutGraphBuilder.kt`): flattens nested subgraphs, SCC-based DAG depth, contour-based subtree packing
- **Image rendering** (`renderer/GraphImageRenderer.kt`): renders to `BufferedImage` via Java2D (for PNG export)

Output: `LayoutGraph` — immutable, render-ready graph with absolute node positions and routed Bezier edge curves.

### graph-ui
Compose Multiplatform visualization component. Renders `LayoutGraph` to interactive Compose Canvas with pan/zoom, hover highlighting, and per-node image state carousel. Main entry: `GraphVisualizer.kt` + `GraphViewModel.kt`.

### web-server
Ktor HTTP server on port 8080. Accepts ZIP uploads (`app-graph.json` + `screenshots/`), builds layout, stores on Google Cloud Storage, and serves a browser UI. GCS bucket set via `GCS_BUCKET` env var.

### idea-plugin
IntelliJ IDEA plugin (targets 2025.1+). Registers a tool window, scans Gradle modules for the `exportGraph` task, and embeds the `graph-ui` Compose component using IntelliJ's bundled Compose UI.

**Composable rendering**: see [`idea-plugin/COMPOSABLE_RENDERING.md`](idea-plugin/COMPOSABLE_RENDERING.md) for the rules governing how `ComposableRenderer` renders `@Composable` functions via Layoutlib. These rules are hard constraints derived from debugging — violating them causes blank images, inflate failures, or `ClassNotFoundException`.

**Debugging the subprocess renderer**: see [`idea-plugin/LOCAL_REPRO.md`](idea-plugin/LOCAL_REPRO.md) for the local repro harness (`idea-plugin/local-repro/run.sh`) that drives the deployed `render-worker` shadow jar from a shell (~3 s/render, no IDE) — the fast loop for render-path changes (fonts, system UI, sizing, blank renders).

### sample-android
Standalone Gradle project (`sample-android/`). Demonstrates annotation usage — 4 screens across 3 subgraphs, 3 preview states each. Uses `includeBuild("..")` composite build to depend on `:graph-annotations` and `:graph-processor` directly from source.

## Key Architectural Decisions

- **graph-renderer has zero UI dependency** — this is intentional. It can be used from Ktor, IntelliJ, or CLI.
- **`LayoutGraph` is immutable and serializable** — web-server caches it as `layout.json` in GCS so layout doesn't need to be recomputed on each request.
- **App graph format is v2.0** — the JSON root is `AppGraph` with a `subgraphs` map. Each `Subgraph` owns its `screens` and `connections`. Old flat-format docs in `START_HERE.md` / `QUICKSTART.md` are outdated.
- **Screenshot paths**: during layout, image dimensions are read from disk to size nodes. For web, paths are rewritten to CDN URLs before storing `layout.json`.
- **Cycles handled via SCC**: the layout algorithm computes SCCs first, then treats the condensed DAG for depth assignment.

## Key Files

| File | Purpose |
|------|---------|
| `graph-annotations/src/main/kotlin/.../AppGraph.kt` | `@AppFlowGraph`, `@AppFlowScreen`, `@AppFlowTransition` annotations |
| `graph-processor/src/main/kotlin/.../GraphSymbolProcessor.kt` | KSP processor — scans annotations, writes fragment JSON |
| `graph-renderer/src/main/kotlin/.../model/NavGraph.kt` | Serializable data models (AppGraph v2.0) |
| `graph-renderer/src/main/kotlin/.../model/LayoutGraphBuilder.kt` | Layout algorithm |
| `graph-ui/src/jvmMain/kotlin/.../ui/GraphVisualizer.kt` | Main Compose canvas component |
| `graph-ui/src/jvmMain/kotlin/.../viewmodel/GraphViewModel.kt` | Graph state management |
| `web-server/src/main/kotlin/.../web/WebServer.kt` | Ktor routes + upload handling |
| `idea-plugin/src/main/kotlin/.../FlowToolWindowFactory.kt` | IntelliJ tool window entry point |
| `gradle/libs.versions.toml` | Version catalog for all dependencies |

## Dependency Versions

- Kotlin: 2.3.0
- Compose Multiplatform: 1.11.0-beta03
- Ktor: 2.3.12
- IntelliJ Platform plugin: 2.10.5
- kotlinx-serialization: 1.7.1
- kotlinx-coroutines: 1.10.2
- KSP: 2.3.2
