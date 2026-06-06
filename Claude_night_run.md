# Claude night run log

Autonomous pass over the **# Planned** section of `TODO_ideas.md` (Heap skipped).
Each task: brief status, conclusions, questions. Commit per task.

Started: 2026-06-07.

---

## ✅ Native IDEA context menu + image menu items (tasks 1–4)

**Done.** Replaced the Compose `ContextMenuArea` on graph nodes with a native IntelliJ
Swing `JBPopupMenu`.

- `graph-ui`: added `NodeContextMenuRequest(nodeId, imagePath, screenX, screenY)` and an
  `onNodeContextMenu` callback on `GraphVisualizer`/`GraphPanel`. Right-click on a node
  (secondary button) captures the cursor's screen position (`MouseInfo`) and fires the
  callback. When the callback is null (standalone `composeApp`), the old `ContextMenuArea`
  is kept as a fallback.
- `idea-plugin` `GraphTabPanel`: builds the native popup with **Copy** (image → system
  clipboard via `ImageTransferable`), **Open in Finder** (`RevealFileAction.openFile`),
  **Jump to source** (renamed from "View source"), **Refresh**. Copy/Open are disabled when
  the node has no rendered image. Shown on the EDT at the click's screen coords relative to
  the `ComposePanel`.

All modules compile (`graph-ui`, `idea-plugin`, `composeApp`). Visual behavior not run-verified
(no IDE GUI in this session) but logic is straightforward.

---

## ✅ Duplicate previews in subprocess (task 5)

**Root cause (high confidence, code-level):** the in-process renderer
(`ComposableRenderer`) treats the layout-log message *"Sequence doesn't contain element"* —
logged by `ComposeViewAdapter` when a `PreviewParameterProvider` index runs past the end — as
the multi-state loop's stop signal, **even when the render technically succeeds**. The worker
(`WorkerRenderer`) only stopped when the render *result* failed, so when layoutlib logged the
warning but still produced a (duplicate) frame, the worker wrote an extra image and kept going —
hence "14 rendered for 7 states" (each extra index re-renders the last/duplicate value until
layoutlib finally hard-fails).

**Fix:**
- `StdErrLayoutLog` now records whether the "Sequence doesn't contain element" sentinel was
  logged during a render; `WorkerRenderer.render` resets it per request and, after the callbacks
  loop, returns `providerExhausted=true` (no image written) if it was seen — mirroring the
  in-process renderer.
- `PreviewCache.clearIndexedFiles(module, fqn)` deletes stale `${name}_<n>.<ext>` images before
  the multi-state loop in `refreshPreviews`, so the on-disk set matches exactly the real states
  (also stops incremental skip from reading stale higher indices as valid). Unit-tested.

**Caveat:** the worker behavior couldn't be reproduced in this session (needs a real Android
module via the local-repro harness — the AppFlower repo has no preview-provider screens). The fix
is derived from the existing in-process design and is strictly safe (it can only *stop* an
over-render). Worth a quick run-verify on the real project: render `AuthorizeBottomSheetGlobalPreview`
and confirm 7 files, not 14.

---

## ✅ Single-node refresh no longer resets the view (task 6)

**Cause:** `onRefreshNode` called `tab.reloadView()`, which runs `buildFromAppGraphV2` and rebuilds
the entire layout. The display graph briefly becomes null, the `GraphVisualizerInternal`
composable leaves composition, its `rememberSaveable loadedOnce` flag is dropped, and on
re-entry the `LaunchedEffect` re-centers on the entry node — so the canvas jumped to the start.

**Fix:** refresh just the affected node's image via `tab.bumpNodeImageRevision(nodeId)` (the
`AsyncImage` re-keys on the revision and reloads the same-path file from disk). No layout rebuild,
so pan/zoom are preserved. Task 7 (below) extends this to update the full image list.

---

## ✅ Node refresh re-renders all states (task 7)

**Before:** `onRefreshNode` rendered only `screen.selected_state`. Now, for a provider-backed
screen it clears the indexed files and loops over every state (same exhaustion-driven loop as
"Refresh previews"); for a plain screen it renders the single image.

After rendering, it reads the node's images from disk via the new `PreviewCache.listStateImages`
(mirrors the layout builder's `findPreviewImages` so indices line up) and updates **only that
node** in place via the new `GraphViewModel.updateNodeImages` — positions preserved, selected
state clamped to the new range, image revision bumped. So a node that gained/lost states reflects
it without a full rebuild. Unit-tested (`listStateImages` ordering + fallback).

---

## ✅ "Refresh previews" renders the selected graph only (task 9)

`refreshPreviews` iterated every aggregated graph. It now reads the active tab
(`toolWindow.contentManager.selectedContent`) and its selected graph name, filters
`graphSet.graphs` to just that graph, and reloads / reports problems only on the active tab.
`setAllBusy` is kept (single shared render worker — block all tabs' buttons while one renders).
Falls back to all graphs if no tab is active (defensive). Done before task 8 since both touch
`refreshPreviews`.

---
