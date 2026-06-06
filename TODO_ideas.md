# Heap

- [ ] Deep link simulation for screens
- [ ] Navigation argument visualization (show parameters passed between screens)
- [ ] State matrix rendering (all states of a screen in one grid)
- [ ] Side-by-side comparison of screen states
- [ ] Interaction simulation (click → navigate → render next screen)
- [ ] Highlight unreachable screens in the graph
- [ ] Collapsing subgraphs by feature/module
- [-] Export graph as interactive HTML
- [ ] Export graph as PNG
- [ ] CI mode to generate artifacts automatical/ly
- [ ] Diff between two graph versions (e.g., PR vs main)
- [ ] Screenshot diff between state variants
- [ ] Highlight screens missing previews/states
- [ ] Highlight screens not reachable from any entry point
- [ ] Automatic grouping of screens by navigation graph
- [ ] Show transitions with conditions (if state → navigate)
- [ ] Visualize loading/error/empty states explicitly
- [ ] Parameter injection editor for previewing different inputs
- [ ] Locale preview matrix (one screen → multiple locales)
- [ ] Theme preview matrix (light/dark)
- [ ] Device size matrix preview
- [ ] RTL layout preview
- [ ] Snapshot rendering cache
- [ ] Automatic state exploration (detect possible states via state machines)
- [ ] Jump to source code from graph node
- [ ] Show analytics events triggered by screens
- [ ] Show permission requirements for screens
- [ ] Generate documentation of flows automatically
- [ ] Generate onboarding/tutorial flows diagram
- [ ] Track changes in navigation structure over time
- [ ] "Render all previews" mode

# Planned

- [x] Use native idea context menu instead of the custom one

## Image context menu features
- [x] "Copy" (image to clipboard)
- [x] "Open in Finder"
- [x] "View source" -> "Jump to source"

## Features and fixes
- [x] In a separate process, previews are duplicated. For example, for SelectAddressFromListGlobalPreview there is 1 state, but 2 are rendered (same). For AuthorizeBottomSheetGlobalPreview there are 14 previews rendered while there are only 7 states 
- [x] Do not refresh the whole graph when a single node is refreshed. At the moment, after refreshing the whole graph jumps to the beginning
- [x] On node refresh via context menu should refresh all the states (at the moment only selected state is updated)
- [x] User can stop rendering with a button (and show what was rendered before stop)
- [x] "Refresh previews" should refresh the currently selected graph only
- [x] For some reason SettingsContentPreview renders light while it is dark theme, investigate and fix
  <!-- Worker now sets FolderConfiguration NightModeQualifier (was only setting params.uiMode), so
       values-night resources resolve in dark mode. Needs run-verify on the real app. If the issue
       is per-preview uiMode (not the global setting), see the improvement note below. -->

# Improvement ideas (from the night run)

Thoughts surfaced while working through the Planned list. Not started — just notes.

- **Per-preview UI mode / config.** Today night mode (and device/font/locale) is a single global
  `PreviewRenderConfig`; each `@Preview`'s own `uiMode`/`device`/`fontScale`/`locale` is ignored.
  Capture them per preview in the `exportGraph` task → fragment JSON → `Screen` → `RenderRequest`,
  so a screen annotated dark renders dark regardless of the global toggle. This is the proper fix
  for the SettingsContentPreview symptom if it turns out to be per-preview rather than global.
- **Preserve view on "Refresh previews".** Like the single-node fix (task 6), a full refresh still
  calls `reloadView()` and snaps the canvas back to the entry node. Could update node images in
  place (reuse `GraphViewModel.updateNodeImages`) instead of rebuilding the layout, so pan/zoom
  survive a bulk refresh too.
- **Per-tab busy state.** Rendering disables the toolbar on *all* tabs (single shared worker). If
  the worker pool ever goes per-module/parallel, make busy/stop/progress per-tab.
- **Cancel the Gradle build too.** The Stop button only cancels rendering; "Build graph" runs an
  external Gradle task that keeps going. Could wire it to the Gradle run's cancellation.
- **Copy/Open-in-Finder for a chosen state.** The native context menu copies/reveals the
  *currently selected* state image. A submenu to pick which state (or "reveal the whole preview
  folder") could be handy.
- **Incremental refresh for providers.** Task 5 clears a provider's indexed files before
  re-rendering (needed for correctness), which defeats incremental skip for multi-state screens.
  A content-hash or per-state mtime check could restore skip while still pruning stale states.
- **Run-verify harness for theming.** The local-repro harness drives the worker but has no golden
  for light/dark. A tiny fixture composable using `values-night` + a pixel assertion would let the
  night-mode path (task 10) be regression-tested without the full app.
