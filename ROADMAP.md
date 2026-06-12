# Roadmap

A living list of where Sight is headed. Nothing here is a commitment or a schedule — it's a snapshot
of intent. Have an opinion, or want to pick something up? [Open an issue](https://github.com/keymusicman/Sight/issues)
or see [CONTRIBUTING.md](CONTRIBUTING.md).

## Recently shipped

- Native IDE context menu on graph nodes (copy image, reveal in Finder, jump to source)
- Per-node refresh that re-renders all of a screen's states without rebuilding the whole graph
- Stop button to cancel an in-progress render and keep what was already produced
- Subprocess renderer fixes: correct state counts (no duplicated previews), dark-mode resource
  resolution via `values-night`

## Planned / under consideration

- **Export** — graph as PNG and as an interactive HTML page
- **CI mode** — generate graph artifacts headlessly for pull-request checks
- **Diffing** — compare two graph versions (e.g. PR vs. main) and highlight screenshot changes
- **Graph health** — flag screens with no previews/states and screens unreachable from any entry point
- **Preserve pan/zoom on bulk refresh** — update node images in place rather than rebuilding the layout

## Ideas (unscheduled)

- visualize screen with its incoming/outgoing navigation connections on click
- Navigation argument visualization (parameters passed between screens)
- State matrix rendering — all states of a screen in one grid, with side-by-side comparison
- Interaction simulation — click → navigate → render the next screen
- Deep-link simulation for screens
- Collapsing subgraphs by feature/module
- Transitions with conditions (`if state → navigate`)
- Preview matrices: theme (light/dark), locale, device size, RTL
- Snapshot rendering cache
- Show analytics events and permission requirements per screen
- Auto-generate flow documentation and onboarding/tutorial diagrams
- Track navigation-structure changes over time
