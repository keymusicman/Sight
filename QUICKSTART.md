# Quick Start Guide - Navigation Graph Visualizer

## Overview

This is a Kotlin Compose Desktop application that visualizes Android navigation graphs from JSON export files.

## Quick Start

### 1. Build the Application
```bash
cd /Users/keymusicman/example/AppFlower
./gradlew build
```

### 2. Run the Application
```bash
./gradlew run
```

### 3. Use the Application
1. In the left sidebar, paste the path to your Android project
2. Click "Load" button
3. The app will search for `build/graph/app-graph.json` in `<project>/build/graph/`
4. The navigation graph will be displayed with:
   - **Blue circles**: Nodes representing screens/destinations
   - **Gray arrows**: Edges representing transitions between screens
   - **Left sidebar**: List of all nodes and graph statistics

### 4. Example Test
```bash
# The app includes a test graph at:
/tmp/test-android-project/build/graph/app-graph.json

# Run the app and enter this path to see a sample graph
```

## Project Layout

The application is located in the `composeApp` directory:

```
composeApp/
├── build.gradle.kts                          # Build configuration with serialization plugin
├── src/
│   ├── commonMain/kotlin/
│   │   └── com/keymusicman/appflower/
│   │       ├── model/NavGraph.kt             # Data models: Transition, AppGraph, Node, Edge, Graph
│   │       └── ui/GraphVisualizer.kt         # Canvas-based visualization component
│   └── jvmMain/kotlin/
│       └── com/keymusicman/appflower/
│           ├── App.kt                        # Main UI: sidebar + visualizer
│           ├── main.kt                       # Application entry point
│           └── utils/GraphLoader.kt          # JSON file loading and parsing
```

## Key Components

### Data Models (`NavGraph.kt`)
- `Transition`: JSON representation of a navigation transition
- `AppGraph`: Root JSON object with transitions list
- `Node`: Screen/destination with x,y coordinates
- `Edge`: Transition representation
- `Graph`: Complete graph structure with nodes and edges
- **Circular Layout**: Nodes are automatically positioned in a circle

### Graph Loader (`GraphLoader.kt`)
- Loads JSON from `build/graph/app-graph.json`
- Uses kotlinx.serialization for type-safe parsing
- Handles file not found and parse errors gracefully

### Visualizer (`GraphVisualizer.kt`)
- Renders graph on Compose Canvas
- Draws circles for nodes
- Draws arrows for directed edges
- Calculates arrow angles for proper direction visualization

### Main App (`App.kt`)
- Split-view UI: controls on left, visualization on right
- Project path input field
- Load/Clear buttons
- Graph statistics and node list
- Error messages

## Running Without GUI (Headless)

To test the loader independently:
```kotlin
// In GraphLoader.kt
val graph = GraphLoader.loadGraphFromProject("/path/to/project")
```

## JSON File Format

Expected location: `<android-project>/build/graph/app-graph.json`

```json
{
  "transitions": [
    {
      "from": "ScreenA",
      "to": "ScreenB",
      "trigger": "button_click"
    },
    {
      "from": "ScreenB",
      "to": "ScreenA",
      "trigger": "back_pressed"
    }
  ]
}
```

## Troubleshooting

### "Graph file not found"
- Ensure you entered the correct Android project root directory
- Verify that `build/graph/app-graph.json` exists in the project
- The app looks at: `{projectPath}/build/graph/app-graph.json`

### "Failed to load graph"
- Check that the JSON is valid
- Ensure the file has the correct structure with "transitions" array
- Each transition must have "from" and "to" fields

### Application won't start
```bash
# Rebuild the project
./gradlew clean build

# Run with verbose output
./gradlew run --info
```

## Features

✅ Automatic graph file detection
✅ JSON parsing with kotlinx.serialization
✅ Visual node and edge rendering
✅ Circular layout algorithm
✅ Node list display
✅ Graph statistics
✅ Error handling
✅ Fully self-contained (no Android framework required)

## Architecture

```
User Interface
    ↓
App() [Compose]
    ├── Sidebar: Controls & Node List
    └── Canvas: GraphVisualizer
            ↓
        Graph (model)
            ├── Nodes (positioned)
            └── Edges (with triggers)
            ↓
        GraphLoader.loadGraphFromProject()
            ↓
        JSON File (app-graph.json)
```

## Build Details

- **Framework**: Kotlin Compose for Desktop
- **Serialization**: kotlinx.serialization-json
- **Build Tool**: Gradle with KMP (Kotlin Multiplatform)
- **Target**: JVM (Desktop)
- **Java Version**: 11+

## Gradle Configuration

Key additions to `composeApp/build.gradle.kts`:
```kotlin
plugins {
    // ... other plugins
    kotlin("plugin.serialization") version "2.3.0"
}

dependencies {
    implementation(libs.kotlinx.serialization)
}
```

## Next Steps

1. **Test with your Android project**
   - Generate the graph JSON from your Android project
   - Run this app and load the path

2. **Customize the visualization**
   - Adjust node colors and sizes in `GraphVisualizer.kt`
   - Modify layout algorithm in `NavGraph.kt`

3. **Add new features**
   - Zoom/Pan controls
   - Better layout algorithms
   - Export as image
   - Search/filter nodes
