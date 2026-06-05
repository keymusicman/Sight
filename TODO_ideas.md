# Heap

- [ ] Deep link simulation for screens
- [ ] Navigation argument visualization (show parameters passed between screens)
- [ ] State matrix rendering (all states of a screen in one grid)
- [ ] Side-by-side comparison of screen states
- [ ] Automatic discovery of state variants from sealed classes / enums
- [ ] Interaction simulation (click → navigate → render next screen)
- [ ] Navigation path generation (possible flows through the app)
- [ ] Highlight unreachable screens in the graph
- [ ] Highlight cyclic navigation loops
- [ ] Graph filtering (feature/module/package)
- [ ] Collapsing subgraphs by feature/module
- [-] Export graph as SVG
- [-] Export graph as interactive HTML
- [ ] CI mode to generate artifacts automatical/ly
- [ ] Diff between two graph versions (e.g., PR vs main)
- [ ] Screenshot diff between state variants
- [ ] Highlight screens missing previews/states
- [ ] Highlight screens not reachable from any entry point
- [ ] Automatic grouping of screens by navigation graph
- [ ] Show transitions with conditions (if state → navigate)
- [-] Show ViewModel/state owner for each screen
- [ ] Visualize loading/error/empty states explicitly
- [ ] Parameter injection editor for previewing different inputs
- [ ] Locale preview matrix (one screen → multiple locales)
- [ ] Theme preview matrix (light/dark)
- [ ] Device size matrix preview
- [ ] RTL layout preview
- [-] Performance metrics overlay (compose recomposition count, measure/layout time)
- [ ] Snapshot rendering cache
- [ ] Automatic state exploration (detect possible states via state machines)
- [-] Interactive graph navigation (click node → render screen)
- [-] Bookmark frequently inspected screens
- [x] Search for screens by name or route
- [ ] Jump to source code from graph node
- [x] Jump to composable preview in IDE
- [-] Detect duplicate screens/routes
- [-] Detect unused navigation arguments
- [ ] Show analytics events triggered by screens
- [ ] Show permission requirements for screens
- [ ] Generate documentation of flows automatically
- [ ] Generate onboarding/tutorial flows diagram
- [ ] Track changes in navigation structure over time
- [-] Plugin action: render graph for current module only
- [-] Plugin action: render graph for current screen
- [-] Plugin action: render all states of selected composable

# Planned

## Plugin settings UI
- [x] Plugin settings page (persistent per-project or IDE-level)
- [x] Setting: output image format (PNG / JPEG / BMP) with quality slider for JPEG
- [ ] Setting: incremental rendering (skip composables whose source hasn't changed since last render)

## Rendering performance
- [ ] JPEG output format experiment (target: ~15–25ms write vs current 132ms PNG)
- [x] BMP output format experiment (zero encode cost, large files)
- [ ] Incremental rendering: compare source file lastModified vs existing image timestamp, skip if unchanged

## Features
- [ ] Render all previews
- [ ] No graph mode (only marked nodes)
- [ ] Multi-graph support. Per-graph observation rather than per-module
- [ ] Stop rendering (show what was rendered)
- [ ] On load jump to graph starting node rather than leftmost node