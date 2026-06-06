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

- [ ] Use native idea context menu instead of the custom one

## Image context menu features
- [ ] "Copy" (image to clipboard)
- [ ] "Open in Finder"
- [ ] "View source" -> "Jump to source"

## Features and fixes
- [ ] In a separate process, previews are duplicated. For example, for SelectAddressFromListGlobalPreview there is 1 state, but 2 are rendered (same). For AuthorizeBottomSheetGlobalPreview there are 14 previews rendered while there are only 7 states 
- [ ] Do not refresh the whole graph when a single node is refreshed. At the moment, after refreshing the whole graph jumps to the beginning
- [ ] On node refresh via context menu should refresh all the states (at the moment only selected state is updated)
- [ ] User can stop rendering with a button (and show what was rendered before stop)
- [ ] "Refresh previews" should refresh the currently selected graph only
- [ ] For some reason SettingsContentPreview renders light while it is dark theme, investigate and fix
