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
