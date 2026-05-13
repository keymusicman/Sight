# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run desktop app
./gradlew :composeApp:run

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
./gradlew :composeApp:jvmTest

# Build IntelliJ plugin
./gradlew :idea-plugin:buildPlugin
```

## Module Architecture

The project is a 5-module Kotlin Multiplatform (KMP) monorepo. All modules target JVM only.

```
graph-renderer  →  graph-ui  →  composeApp (desktop)
     ↓                              ↓
 web-server               idea-plugin (IntelliJ plugin)
```

### graph-renderer
Pure JVM library — no UI dependencies. Owns:
- **Data models** (`model/NavGraph.kt`): `AppGraph`, `Subgraph`, `Screen`, `Connection`, `ConnectionEndpoint`
- **JSON loading** (`loader/GraphLoader.kt`): reads `{projectPath}/app/build/graph/app-graph.json`
- **Layout algorithm** (`model/LayoutGraphBuilder.kt`): flattens nested subgraphs, SCC-based DAG depth, contour-based subtree packing
- **Image rendering** (`renderer/GraphImageRenderer.kt`): renders to `BufferedImage` via Java2D (for PNG export)

Output: `LayoutGraph` — immutable, render-ready graph with absolute node positions and routed Bezier edge curves.

### graph-ui
Compose Multiplatform visualization component. Renders `LayoutGraph` to interactive Compose Canvas with pan/zoom, hover highlighting, and per-node image state carousel. Main entry: `GraphVisualizer.kt` + `GraphViewModel.kt`.

### composeApp
Desktop application: split-view (sidebar controls + canvas). Adds export features (PNG, draw.io XML, ZIP for web upload) on top of `graph-ui`. Entry point: `jvmMain/main.kt`.

### web-server
Ktor HTTP server on port 8080. Accepts ZIP uploads (`app-graph.json` + `screenshots/`), builds layout, stores on Google Cloud Storage, and serves a browser UI. GCS bucket set via `GCS_BUCKET` env var.

### idea-plugin
IntelliJ IDEA plugin (targets 2025.1+). Registers a tool window, scans Gradle modules for the `exportGraph` task, and embeds the `graph-ui` Compose component using IntelliJ's bundled Compose UI.

**Composable rendering**: see [`idea-plugin/COMPOSABLE_RENDERING.md`](idea-plugin/COMPOSABLE_RENDERING.md) for the rules governing how `ComposableRenderer` renders `@Composable` functions via Layoutlib. These rules are hard constraints derived from debugging — violating them causes blank images, inflate failures, or `ClassNotFoundException`.

## Key Architectural Decisions

- **graph-renderer has zero UI dependency** — this is intentional. It can be used from Ktor, IntelliJ, or CLI.
- **`LayoutGraph` is immutable and serializable** — web-server caches it as `layout.json` in GCS so layout doesn't need to be recomputed on each request.
- **App graph format is v2.0** — the JSON root is `AppGraph` with a `subgraphs` map. Each `Subgraph` owns its `screens` and `connections`. Old flat-format docs in `START_HERE.md` / `QUICKSTART.md` are outdated.
- **Screenshot paths**: during layout, image dimensions are read from disk to size nodes. For web, paths are rewritten to CDN URLs before storing `layout.json`.
- **Cycles handled via SCC**: the layout algorithm computes SCCs first, then treats the condensed DAG for depth assignment.

## Key Files

| File | Purpose |
|------|---------|
| `graph-renderer/src/main/kotlin/.../model/NavGraph.kt` | Serializable data models (AppGraph v2.0) |
| `graph-renderer/src/main/kotlin/.../model/LayoutGraphBuilder.kt` | Layout algorithm |
| `graph-ui/src/jvmMain/kotlin/.../ui/GraphVisualizer.kt` | Main Compose canvas component |
| `graph-ui/src/jvmMain/kotlin/.../viewmodel/GraphViewModel.kt` | Graph state management |
| `composeApp/src/jvmMain/kotlin/.../App.kt` | Desktop app root composable |
| `web-server/src/main/kotlin/.../web/WebServer.kt` | Ktor routes + upload handling |
| `idea-plugin/src/main/kotlin/.../FlowToolWindowFactory.kt` | IntelliJ tool window entry point |
| `gradle/libs.versions.toml` | Version catalog for all dependencies |

## Dependency Versions

- Kotlin: 2.3.0
- Compose Multiplatform: 1.10.0
- Ktor: 2.3.12
- IntelliJ Platform plugin: 2.10.5
- kotlinx-serialization: 1.7.1
- kotlinx-coroutines: 1.10.2
