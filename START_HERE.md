# 🚀 START HERE - Navigation Graph Visualizer

Welcome! This document helps you get started with the Navigation Graph Visualizer application.

## 30-Second Quick Start

```bash
cd /Users/keymusicman/example/AppFlower
./gradlew run
```

Then in the app:
1. Enter path: `/tmp/test-android-project`
2. Click: **Load**
3. See the graph visualization!

## What You're Looking At

A **Kotlin Compose Desktop application** that visualizes Android navigation graphs from JSON files.

### What It Does
- 📂 Takes an Android project path
- 🔍 Finds the `build/graph/app-graph.json` file
- 📊 Parses and visualizes the navigation graph
- 🎨 Shows nodes (screens) and edges (transitions)

### What You Get
- **Left sidebar**: Controls, node list, statistics
- **Right canvas**: Interactive graph visualization with nodes and arrows

## Documentation Quick Links

| Document | Purpose |
|----------|---------|
| **README.md** | Original project README |
| **NAVIGATION_GRAPH_VISUALIZER_README.md** | ⭐ Main overview - START HERE! |
| **QUICKSTART.md** | 5-minute quick start guide |
| **IMPLEMENTATION_GUIDE.md** | Complete technical architecture |
| **CODE_REFERENCE.md** | Full code listings |
| **NAV_GRAPH_VISUALIZER.md** | Feature details |

## Build & Run

### Build
```bash
cd /Users/keymusicman/example/AppFlower
./gradlew build
```

### Run
```bash
./gradlew run
```

### Clean Build
```bash
./gradlew clean build
```

## Using the Application

### Test with Sample Graph
```bash
./gradlew run
# In the app:
# Enter: /tmp/test-android-project
# Click: Load
```

### Use with Your Own Project
```bash
./gradlew run
# In the app:
# Enter: /path/to/your/android/project
# Click: Load
# Graph will load from: build/graph/app-graph.json
```

## Expected JSON Format

The app looks for: `{projectPath}/build/graph/app-graph.json`

```json
{
  "transitions": [
    {
      "from": "ScreenA",
      "to": "ScreenB",
      "trigger": "button_click"
    }
  ]
}
```

## Key Features

✅ Automatic graph file detection  
✅ Type-safe JSON parsing  
✅ Circular node layout  
✅ Interactive visualization  
✅ Statistics display  
✅ Error handling  
✅ No Android framework required  

## Project Location

```
/Users/keymusicman/example/AppFlower
├── composeApp/src/
│   ├── commonMain/
│   │   ├── model/NavGraph.kt
│   │   └── ui/GraphVisualizer.kt
│   └── jvmMain/
│       ├── App.kt
│       ├── main.kt
│       └── utils/GraphLoader.kt
└── Documentation files...
```

## Next Steps

1. **Read**: `NAVIGATION_GRAPH_VISUALIZER_README.md` for full overview
2. **Build**: `./gradlew build`
3. **Run**: `./gradlew run`
4. **Test**: Use `/tmp/test-android-project`
5. **Explore**: Read other documentation files

## Need Help?

- **Quick overview**: See `NAVIGATION_GRAPH_VISUALIZER_README.md`
- **Step-by-step**: See `QUICKSTART.md`
- **Technical details**: See `IMPLEMENTATION_GUIDE.md`
- **Complete code**: See `CODE_REFERENCE.md`

---

**Everything is ready to use!** Just run `./gradlew run` and start exploring. 🎉
