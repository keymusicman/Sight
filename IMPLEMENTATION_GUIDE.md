# Kotlin Compose Desktop Navigation Graph Visualizer

## Complete Implementation Guide

This document provides a comprehensive overview of the Navigation Graph Visualizer application - a fully functional Kotlin Compose Desktop app that reads and visualizes Android navigation graphs.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [File Structure](#file-structure)
3. [Code Components](#code-components)
4. [Build Configuration](#build-configuration)
5. [Usage Instructions](#usage-instructions)
6. [Technical Details](#technical-details)

---

## Architecture Overview

### Design Pattern: Model-View-Controller (MVC)

```
┌─────────────────────────────────────────────────────────┐
│                    Main Entry Point                     │
│                      main.kt                             │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                   UI Layer (Composable)                  │
│                      App.kt                              │
│     ┌─────────────────┬──────────────────────┐           │
│     │                 │                      │           │
│     ▼                 ▼                      ▼           │
│  Sidebar          Divider            GraphVisualizer     │
│  Controls                                 (Canvas)       │
│  - Path Input                                            │
│  - Load Button                                           │
│  - Node List                                             │
│  - Stats                                                 │
└────────┬──────────────────────────────────┬──────────────┘
         │                                  │
         │ GraphLoader                      │ Graph Model
         │ (FileIO)                         │ (Data)
         ▼                                  ▼
┌──────────────────┐            ┌─────────────────────┐
│  GraphLoader.kt  │            │   NavGraph.kt       │
├──────────────────┤            ├─────────────────────┤
│ loadGraphFromURL │            │ Transition          │
│ findGraphFile    │            │ AppGraph            │
│                  │            │ Node                │
│                  │            │ Edge                │
│                  │            │ Graph               │
└────────┬─────────┘            └──────┬──────────────┘
         │                             │
         └──────────────┬──────────────┘
                        ▼
         ┌──────────────────────────┐
         │  JSON File               │
         │  app-graph.json          │
         │  (from Android project)  │
         └──────────────────────────┘
```

---

## File Structure

### Project Directory
```
AppFlower/
├── composeApp/
│   ├── build.gradle.kts                 # Build configuration
│   └── src/
│       ├── commonMain/kotlin/
│       │   └── com/keymusicman/appflower/
│       │       ├── model/
│       │       │   └── NavGraph.kt       # Data models (SHARED)
│       │       └── ui/
│       │           └── GraphVisualizer.kt # UI Component (SHARED)
│       └── jvmMain/kotlin/
│           └── com/keymusicman/appflower/
│               ├── App.kt                # Main UI (JVM)
│               ├── main.kt               # Entry point (JVM)
│               └── utils/
│                   └── GraphLoader.kt    # File loading (JVM)
├── gradle/
│   └── libs.versions.toml               # Dependency versions
├── NAV_GRAPH_VISUALIZER.md              # Feature documentation
└── QUICKSTART.md                         # Quick start guide
```

### Source Organization

- **commonMain**: Shared code (models, UI components) - works on all platforms
- **jvmMain**: Desktop-specific code (file I/O, main entry point)

---

## Code Components

### 1. Data Models (NavGraph.kt)

Located in: `composeApp/src/commonMain/kotlin/com/keymusicman/appflower/model/NavGraph.kt`

```kotlin
// Serializable models matching JSON structure
@Serializable
data class Transition(
    val from: String,
    val to: String,
    val trigger: String? = null
)

@Serializable
data class AppGraph(
    val transitions: List<Transition>
)

// Internal graph representation
data class Node(
    val id: String,
    var x: Float = 0f,
    var y: Float = 0f
)

data class Edge(
    val from: String,
    val to: String,
    val trigger: String? = null
)

data class Graph(
    val nodes: Set<Node>,
    val edges: List<Edge>
) {
    // Factory function: AppGraph -> Graph
    companion object {
        fun from(appGraph: AppGraph): Graph { ... }
        
        // Circular layout algorithm
        private fun layoutNodes(nodes: List<Node>): List<Node> { ... }
    }
}
```

**Key Features:**
- `@Serializable` annotation for JSON parsing
- Automatic node extraction from transitions
- Circular layout positioning (nodes arranged in a circle)
- Immutable Graph model

### 2. File Loader (GraphLoader.kt)

Located in: `composeApp/src/jvmMain/kotlin/com/keymusicman/appflower/utils/GraphLoader.kt`

```kotlin
object GraphLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadGraphFromProject(projectPath: String): AppGraph? {
        val graphFile = File(projectPath, "build/graph/app-graph.json")
        return if (graphFile.exists()) {
            try {
                val content = graphFile.readText()
                json.decodeFromString<AppGraph>(content)
            } catch (e: Exception) {
                println("Error loading graph: ${e.message}")
                null
            }
        } else {
            println("Graph file not found at: ${graphFile.absolutePath}")
            null
        }
    }

    fun findGraphFile(startPath: String): File? {
        // Search up directory tree (max 5 levels)
        // Returns first found app-graph.json
    }
}
```

**Key Features:**
- Looks at: `{projectPath}/build/graph/app-graph.json`
- Uses kotlinx.serialization for safe JSON parsing
- Graceful error handling
- Ignores unknown JSON fields

### 3. Graph Visualization (GraphVisualizer.kt)

Located in: `composeApp/src/commonMain/kotlin/com/keymusicman/appflower/ui/GraphVisualizer.kt`

```kotlin
@Composable
fun GraphVisualizer(graph: Graph?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        if (graph == null) {
            Text("No graph loaded")
        } else {
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                drawGraph(graph)
            }
        }
    }
}

private fun DrawScope.drawGraph(graph: Graph) {
    // Draw all edges first (so they appear behind nodes)
    graph.edges.forEach { edge ->
        val fromNode = graph.nodes.find { it.id == edge.from }
        val toNode = graph.nodes.find { it.id == edge.to }
        drawEdge(fromPoint, toPoint, nodeRadius, arrowSize)
    }

    // Draw all nodes on top
    graph.nodes.forEach { node ->
        drawNode(Offset(node.x, node.y), node.id, nodeRadius)
    }
}

private fun DrawScope.drawEdge(...) {
    // 1. Calculate vector from source to destination
    // 2. Adjust start/end to account for node radius
    // 3. Draw line with arrow head (two angled lines)
}

private fun DrawScope.drawNode(...) {
    // Draw filled circle
    // Draw circle outline (stroke)
}
```

**Key Features:**
- Canvas-based rendering
- Circular nodes (30px radius)
- Directed arrows with proper angles
- Handles edge and node Z-ordering

### 4. Main UI (App.kt)

Located in: `composeApp/src/jvmMain/kotlin/com/keymusicman/appflower/App.kt`

```kotlin
@Composable
fun App() {
    var projectPath by remember { mutableStateOf("") }
    var graph by remember { mutableStateOf<Graph?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT SIDEBAR (300dp wide)
            Column(...) {
                // Title
                // Input field for project path
                // Load & Clear buttons
                // Error/status message
                // Graph statistics
                // Node list (scrollable)
            }

            // DIVIDER

            // RIGHT CANVAS (fills remaining space)
            GraphVisualizer(graph, modifier = Modifier.weight(1f))
        }
    }
}
```

**Key Features:**
- Split layout: sidebar + canvas
- Input field for project path
- Load button triggers GraphLoader
- Clear button resets state
- Shows statistics: node count, edge count
- Lists all nodes

### 5. Application Entry Point (main.kt)

Located in: `composeApp/src/jvmMain/kotlin/com/keymusicman/appflower/main.kt`

```kotlin
fun main(args: Array<String>) = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Navigation Graph Visualizer",
    ) {
        App()
    }
}
```

**Key Features:**
- Compose Desktop application builder
- Creates main window
- Renders App composable
- Handles window close event

---

## Build Configuration

### Gradle Dependencies (gradle/libs.versions.toml)

```toml
[versions]
kotlin = "2.3.0"
composeMultiplatform = "1.10.0"
kotlinx-serialization = "1.7.1"

[libraries]
compose-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "composeMultiplatform" }
compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "composeMultiplatform" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "composeMultiplatform" }
compose-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "composeMultiplatform" }
kotlinx-serialization = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }

[plugins]
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

### Build Configuration (composeApp/build.gradle.kts)

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.3.0"
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.keymusicman.appflower.MainKt"
    }
}
```

---

## Usage Instructions

### Build
```bash
cd /Users/keymusicman/example/AppFlower
./gradlew build
```

### Run
```bash
./gradlew run
```

### Steps
1. Launch application
2. Enter path to Android project (e.g., `/Users/username/MyApp`)
3. Click "Load"
4. Graph appears on right side with visualization

### Example Test Path
```
/tmp/test-android-project
```

---

## Technical Details

### Data Flow

1. **User Input**: Project path → App state
2. **Graph Loading**: `GraphLoader.loadGraphFromProject(path)` → JSON → AppGraph
3. **Model Conversion**: `Graph.from(appGraph)` → Graph with positioned nodes
4. **Visualization**: `GraphVisualizer` renders Graph on Canvas

### Layout Algorithm

Circular layout in `layoutNodes()`:
```kotlin
val radius = 300f  // Distance from center
val centerX = 600f
val centerY = 400f
val count = nodes.size

nodes.forEachIndexed { index, node ->
    val angle = (2 * PI * index) / count  // Distribute evenly around circle
    node.x = centerX + radius * cos(angle)
    node.y = centerY + radius * sin(angle)
}
```

### Canvas Drawing

**Drawing Order** (bottom to top):
1. Edges (lines with arrows)
2. Nodes (filled circles with outlines)

**Arrow Geometry**:
```
From Node → ─────────────→ To Node (adjusted for radius)
                            ↑ ∠60°
                            │ ∠ 60°
                            └─
```

### Error Handling

- **File not found**: Display message, show path
- **JSON parse error**: Catch exception, log error
- **Empty graph**: Show "Graph is empty" message
- **Invalid path**: Show validation error

---

## JSON File Format

**Expected Location**: `{projectPath}/build/graph/app-graph.json`

**Format**:
```json
{
  "transitions": [
    {
      "from": "ScreenName#ClassName",
      "to": "AnotherScreen#AnotherClass",
      "trigger": "button_click"
    }
  ]
}
```

**Parsing**:
- Uses `kotlinx.serialization`
- Deserializes to `AppGraph` model
- `@Serializable` annotations enable automatic mapping

---

## Deployment

### Running the Application

**From IDE**:
```bash
./gradlew run
```

**Packaged Distribution**:
```bash
./gradlew packageDmg        # macOS
./gradlew packageMsi        # Windows
./gradlew packageDeb        # Linux
```

The package configuration is in `composeApp/build.gradle.kts` under `compose.desktop { application { ... } }`.

---

## Future Enhancements

- [ ] Zoom and pan controls
- [ ] Force-directed layout algorithm
- [ ] Search and filter nodes
- [ ] Export as PNG/SVG
- [ ] Right-click context menu on nodes
- [ ] Show trigger labels on edges
- [ ] Keyboard shortcuts
- [ ] Configuration file for layout customization
- [ ] Multiple graph file support
- [ ] Graph statistics dashboard

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Graph file not found" | Verify path and that `build/graph/app-graph.json` exists |
| "Failed to load graph" | Check JSON format and ensure valid transitions array |
| Build fails | Run `./gradlew clean build` |
| App won't start | Check Java version (11+) and run with `--info` flag |
| No visualization appears | Ensure graph has at least one transition |

---

## Summary

This is a complete, self-contained Kotlin Compose Desktop application that:

✅ Reads Android navigation graph JSON files  
✅ Parses using type-safe serialization  
✅ Builds internal graph model with layout  
✅ Renders visualization with Compose Canvas  
✅ Provides interactive UI for loading projects  
✅ Includes proper error handling  
✅ Requires NO Android framework dependencies  
✅ Is immediately runnable from `main()` function  

**Build**: `./gradlew build`  
**Run**: `./gradlew run`  
**Location**: `/Users/keymusicman/example/AppFlower`
