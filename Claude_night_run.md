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
