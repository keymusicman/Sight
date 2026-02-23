# Navigation Graph Visualizer

A Kotlin Compose Desktop application that visualizes Android navigation graphs from exported JSON files.

## Features

- **Load Android Projects**: Browse and select Android project directories
- **Automatic Graph Detection**: Finds `build/graph/app-graph.json` automatically
- **Parse Navigation Graphs**: Uses kotlinx.serialization to parse graph definitions
- **Visual Graph Display**: 
  - Nodes displayed as circles with IDs
  - Edges shown as arrows connecting nodes
  - Circular layout for better visualization
  - Interactive UI with node and edge information
- **Fully Self-Contained**: No external dependencies on Android framework

## Building

The project is already set up as a Kotlin Multiplatform (KMP) Compose Desktop application.

```bash
./gradlew build
```

## Running

### From the IDE
```bash
./gradlew run
```

### With a Project Path (command line argument)
```bash
./gradlew run --args="/path/to/android/project"
```

## Usage

1. **Launch the Application**
   ```bash
   ./gradlew run
   ```

2. **Enter Project Path**
   - In the left sidebar, enter the path to your Android project
   - Example: `/Users/username/Projects/MyAndroidApp`

3. **Load Graph**
   - Click the "Load" button
   - The app will search for `build/graph/app-graph.json` in the project
   - If found, the graph will be displayed with nodes and edges

4. **View Graph Information**
   - Number of nodes and edges is displayed
   - List of all nodes is shown in the sidebar
   - Edges are displayed as arrows with node connections

## Project Structure

```
composeApp/
├── src/
│   ├── commonMain/kotlin/com/keymusicman/appflower/
│   │   ├── model/
│   │   │   └── NavGraph.kt          # Data models and graph structure
│   │   └── ui/
│   │       └── GraphVisualizer.kt   # Compose visualization component
│   └── jvmMain/kotlin/com/keymusicman/appflower/
│       ├── App.kt                   # Main UI with sidebar and controls
│       ├── main.kt                  # Application entry point
│       └── utils/
│           └── GraphLoader.kt       # JSON parsing and file loading
```

## Data Models

### v2.0 Format (Current)

#### GraphMetadata
```kotlin
@Serializable
data class GraphMetadata(
    val version: String,
    val generated_at: String
)
```

#### Screen
```kotlin
@Serializable
data class Screen(
    val id: String,
    val function: String,
    val location: String,
    val screenshot_location: String
)
```

#### ConnectionEndpoint
```kotlin
@Serializable
data class ConnectionEndpoint(
    val type: String,  // "screen" or "subgraph"
    val subgraph: String,
    val screen_id: String? = null  // Only for "screen" type
)
```

#### Connection
```kotlin
@Serializable
data class Connection(
    val from: ConnectionEndpoint,
    val to: ConnectionEndpoint
)
```

#### Subgraph
```kotlin
@Serializable
data class Subgraph(
    val key: String,
    val qualified_name: String,
    val location: String,
    val root_screen: String,
    val screens: List<Screen>,
    val connections: List<Connection>
)
```

#### AppGraphV2
```kotlin
@Serializable
data class AppGraphV2(
    val metadata: GraphMetadata,
    val subgraphs: Map<String, Subgraph>
)
```

### Internal Models (Unchanged)

#### Node
Represents a screen/destination in the navigation graph with image paths.

#### Edge
Represents a transition from one node to another.

#### Graph
Internal graph representation built from AppGraphV2:
- Nodes use qualified IDs: `"subgraph_key:screen_id"`
- Screen-to-subgraph connections resolve to target's root_screen
- Screenshot paths loaded from screen.screenshot_location

## Expected Graph JSON Format

The visualizer supports **v2.0 format** with subgraphs and nested connections:

```json
{
  "metadata": {
    "version": "2.0",
    "generated_at": "1771775333367"
  },
  "subgraphs": {
    "main": {
      "key": "main",
      "qualified_name": "com.example.screenshottest.mainGraph",
      "location": "app/src/screenshotTest/java/money/example/screenshottest/NewGraph.kt",
      "root_screen": "MainScreen",
      "screens": [
        {
          "id": "MainScreen",
          "function": "MainScreenScreenshots::MainScreen",
          "location": "app/src/screenshotTest/java/money/example/feature/mainscreen/MainScreenScreenshots.kt",
          "screenshot_location": "app/src/screenshotTestDebug/reference/money/example/feature/mainscreen/MainScreenScreenshots/"
        }
      ],
      "connections": [
        {
          "from": {"type": "screen", "subgraph": "main", "screen_id": "MainScreen"},
          "to": {"type": "screen", "subgraph": "main", "screen_id": "RemittanceChooseCountry"}
        }
      ]
    }
  }
}
```

### Key Features of v2.0 Format:
- **Metadata**: Version and generation timestamp
- **Subgraphs**: Screens organized into logical navigation subgraphs
- **Nested Connections**: Each subgraph has its own connections array
- **Screenshot Locations**: Direct paths to screenshot directories for each screen
- **Screen-to-Subgraph Links**: Connections can target entire subgraphs (resolved to root_screen)
- **Node IDs**: Automatically prefixed with subgraph key (e.g., "main:MainScreen", "kyc:EmploymentStatus")

## Technical Details

- **Framework**: Kotlin Compose Desktop (Jetpack Compose for Desktop)
- **Serialization**: kotlinx.serialization
- **Layout**: Circular layout for nodes using mathematical positioning
- **Rendering**: Compose Canvas for 2D drawing with custom arrow shapes

## Features Not Included

- No Android framework dependencies
- No @Composable code from Android projects
- Pure Kotlin Desktop implementation
- File-based reading only (no runtime parsing of Android navigation annotations)

## Testing

A sample graph JSON is provided at `/tmp/test-android-project/build/graph/app-graph.json` for testing purposes.

To test:
```bash
./gradlew run
# Enter: /tmp/test-android-project
# Click Load
```

## Dependencies

Key dependencies (managed in `gradle/libs.versions.toml`):
- `org.jetbrains.compose` - Compose Desktop framework
- `org.jetbrains.kotlinx:kotlinx-serialization-json` - JSON serialization
- `kotlin` - Kotlin standard library

## Known Limitations

- Graph file must be located at `build/graph/app-graph.json` within the project
- Only v2.0 format is supported (legacy v1.0 format deprecated)
- Text rendering in the canvas is minimal (node IDs in sidebar instead)
- Circular layout may overlap labels for large graphs (can be extended with better layout algorithms)
- No zoom/pan controls (can be added as enhancement)
- Subgraph visual grouping not yet implemented (planned enhancement)

## Future Enhancements

- Add zoom and pan functionality
- Implement better layout algorithms (force-directed, hierarchical)
- Add filtering and search
- Export graph as image
- Support for multiple graph files
- Interactive node details on click
- Edge label display in visualization
