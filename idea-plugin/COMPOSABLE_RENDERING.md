# Composable Rendering Rules

How `ComposableRenderer` renders `@Composable` functions via Layoutlib inside the IntelliJ plugin.
Violations of these rules have caused regressions; treat them as hard constraints.

## The rendering pipeline (in order)

```
1. Resolve module + facet
2. Build RenderTask
3. setXmlFile(ComposeViewAdapter XML)
4. inflate()          ← diagnostic only, never abort on failure
5. executeCallbacks() ← loop until settled, then stop
6. render()           ← exactly once, snapshot the result
```

**Never call `render()` twice on the same task.** The first call consumes the canvas state;
the second call produces a blank image. There is no mid-render + final-render pattern.

## inflate() is diagnostic, not a gate

`inflate()` can fail with `ClassNotFoundException` or `NoSuchMethodException` even when the
full `render()` succeeds, because `render()` uses a more permissive class-loading path
internally. Log inflate failures as `(non-fatal)` and always proceed to `executeCallbacks` +
`render()`. Never `return null` on an inflate failure.

## FQN format for `tools:composableName`

`ComposeViewAdapter` parses `tools:composableName` by splitting on the **last** `.`:
- left side → class name passed to `ClassLoader.loadClass()`
- right side → method name searched in that class

For **top-level Kotlin functions** the naive FQN (e.g. `com.example.MyComposable`) puts the
package as the class name, which is not loadable → `ClassNotFoundException`.

The correct form is `com.example.MyFileKt.MyComposable` (file facade class + method name).

`resolveComposableNameForLayoutlib()` fixes this via PSI: it checks whether the class part is
a real class; if not, it searches the package for a class that contains the method. This
handles the case where the composable lives in `SomeOtherFile.kt`, not `MyComposable.kt`.

## Module selection

Use the `.main` source-set sub-module (e.g. `example.app.example-app.main`), not the root
holder module. The root holder causes "holder module ambiguous" errors in Layoutlib's build
services. `AndroidFacet` lives on the holder module, but `ConfigurationManager` and the render
task must be built against `.main`.

## ConfigurationManager needs a file, not a directory

`ConfigurationManager.getConfiguration(vf)` requires a `VirtualFile` pointing to a **file**
(not a directory). Preference order:
1. Source file containing the composable (`sourceFilePath`)
2. `src/main/AndroidManifest.xml` of the module
3. The module directory as last resort

## Frame clock advancement

Compose's `MonotonicFrameClock` inside Layoutlib does not tick on its own. After inflate,
call `task.executeCallbacks(frameTimeNanos)` in a loop until `hasMoreCallbacks()` returns
false or a safe maximum is reached (currently 10 frames). The frame time must be
monotonically increasing; `System.nanoTime()` with `+16_666_666L` increments works.

Without this, Compose never runs its composition pass and the render produces a blank image.

## Stale bytecode

Layoutlib loads compiled `.class` files, not source. A composable added to source but not yet
compiled will fail with `NoSuchMethodException` even though PSI resolves it. If the render
pipeline is otherwise correct but the composable cannot be found, the module needs a rebuild
before rendering will work. The `exportGraph` Gradle task guarantees a compile, so production
use is safe; the debug dialog does not.

## DEBUG_SIMPLE_LAYOUT flag

Set `DEBUG_SIMPLE_LAYOUT = true` in `ComposableRenderer` to render a red `TextView` instead of
`ComposeViewAdapter`. This isolates whether a blank/error result is in the Layoutlib pipeline
itself or in the Compose layer. If the TextView renders but ComposeViewAdapter does not, the
problem is in Compose's tooling classpath or the FQN resolution, not in the render task setup.

## Memory cleanup after each render

Layoutlib leaks native memory aggressively if every per-render handle is not released.
A single missed call lets ~145 MB of bitmap delegates pin per heavy composable, then
never free for the rest of the IDE session. The full cleanup sequence (must run in this
order in the `finally` block of `render()`):

1. **`result.dispose()`** — `RenderResult.dispose()` releases the `ImagePool.Image` handle
   back to the shared pool. Without this the pool keeps the native canvas buffer pinned and
   `System.gc()` cannot reclaim it (the Layoutlib warning *"run GC to reclaim what is
   associated with Java objects"* is misleading — there is still a strong Java ref via the
   pool). Note: `dispose()` only releases the image; it does NOT release `rootViews` or
   `myLogger` — those die only when the `RenderResult` object itself is GC'd.

2. **`inflateResult.dispose()`** — `task.inflate()` returns a separate `RenderResult`. Same
   rules apply.

3. **`task.dispose().get(timeout)`** — `RenderTask.dispose()` returns a `Future<?>`, not
   void. It enqueues the session teardown on an internal `ourDisposeService` executor.
   Without `.get()`, the next render races the previous task's session teardown and the
   leak amplifies.

4. **`LayoutLibrary.clearAllCaches(moduleKey)`** — Bridge keeps per-projectKey caches
   (drawables, themes, fonts, bitmaps loaded via `Resources.getDrawable()`). The projectKey
   is **`ModuleKeyManager.getKey(module)`** (a wrapper object that Layoutlib stores in a
   `WeakHashMap<Module, ModuleKey>`), NOT the `Module` itself — passing `Module` is a
   silent no-op because Bridge looks it up as a map key and finds nothing.
   `AndroidFacetRenderModelModule.getModuleKey()` produces this key; we have to reproduce
   the lookup ourselves because we don't get the resolved key back from the render
   pipeline.

5. **`System.gc()`** — once all Java references are released, GC is the only way to free
   the native bitmaps. `StudioEnvironmentContext.cleanLayoutlibNativeMemory()` (which fires
   the high-memory warning) literally just calls `System.gc()` gated by a flag — there is
   no other cleanup mechanism. Hint GC at least every N renders, or every render under
   memory pressure.

This sequence is necessary but **not sufficient** — see the investigation below for what
this cleanup does NOT cover and why no in-process cleanup can fully fix the leak.

### Per-state leak pattern

Each unique `(composable, stateIndex)` pair loads its own drawables on first render. The
second render of the *same* pair is a cache hit (~0 KB delta). But a new state of the same
composable loads a fresh ~145 MB of drawables. For a composable with N parameter-provider
states, expect N first-render spikes. `clearAllCaches(moduleKey)` per render releases
*some* of these between renders, but the bulk persists (see investigation below).

### Diagnostic memory logging interpretation

When `mem after render #N: heap=X (Δ) native=Y (Δ) rss=Z (Δ)` lines are present, the
deltas reveal where retention is happening:

| heap Δ | native Δ | RSS Δ | Interpretation |
|---|---|---|---|
| ~0 | growing | growing | Pure native leak — Java side is GC'd, native counterparts pinned (clear caches / wrong projectKey) |
| growing | growing | growing | Strong Java refs to bitmap delegates — `RenderResult` not disposed, or a Logger/View tree retained |
| ~0 | ~0 | growing | Memory held outside Layoutlib's counter — AWT/Java2D, Skia, font caches, classloader (see below) |
| ~0 | ~0 | ~0 | Healthy |

# Native memory leak investigation (2026-05)

The cleanup sequence above is correct but only covers the memory that Layoutlib's API
exposes. Empirical investigation showed that the bulk of the leak is held by JVM
subsystems Layoutlib initialized but does not own. This section documents the experiments
and their results so future contributors don't have to rediscover them.

## Observed growth pattern

Single uninterrupted batch of 345 renders on a real project, fresh IDE start:

| Renders completed | Layoutlib native counter | RSS |
|---|---|---|
| 0 (fresh IDE) | ~50 MB | ~3 GB |
| 345 | 2864 MB | 7098 MB |
| 630 (second batch) | 5179 MB | — |
| 637 (one render after 10 min idle) | 5473 MB | — |

The 10-minute idle test ruled out `PhantomReference` queue lag — the native bitmaps are
strongly referenced, not awaiting cleanup.

A separate batch with ~285 renders ended at heap=2059 MB, native=1737 MB, RSS=10108 MB.
The Layoutlib counter only accounts for **~17–40%** of actual process RSS.

## Per-render pattern after applying the cleanup sequence

With all 5 cleanup steps (incl. correct `ModuleKey`) firing on every render:

- Native counter Δ per render: **~20–50 KB** (essentially flat — cleanup IS working)
- RSS Δ per render: swings ±200 MB but trends upward over the batch
- Each unique `(composable, stateIndex)` first-render: **~145 MB native spike**
- Repeat render of same `(composable, stateIndex)`: **~0 KB** (cached)
- Same composable, new state index: another fresh ~145 MB spike

Conclusion: `clearAllCaches(moduleKey)` releases what's in the per-projectKey Bridge
cache, but the underlying native bitmaps that Skia / AWT registered for those resources
remain. The Java handle goes away; the C++ allocation does not.

## Experiments that did NOT free meaningful memory

All results below are from end-of-batch states where standard cleanup had already run.

### Experiment 1: `Bridge.clearAllCaches(null)` (wildcard probe)

Hypothesis: maybe Bridge treats null as "clear all projectKeys."

| Metric | Before | After clearAllCaches(null) | Δ |
|---|---|---|---|
| heap | 1322 MB | 1323 MB | +1 |
| native counter | 2864 MB | 2864 MB | 0 |
| RSS | 7098 MB | 7093 MB | **−5 MB** |

Verdict: **null is not treated as a wildcard.** The 5 MB of RSS drop is GC noise.

### Experiment 2: `LayoutLibrary.dispose()`

Hypothesis: dispose tears down the entire library and forces native release.

| Metric | Before dispose | After dispose + GC×3 + 100ms sleeps | Δ |
|---|---|---|---|
| heap | 1322 MB | 1320 MB | −2 |
| native counter | 2864 MB | 2864 MB | 0 |
| RSS | 7098 MB | 7054 MB | **−44 MB** |

Total: 44 MB freed out of 7098 MB = **0.6%**.

Notes:
- `nativeMemoryUsage` is a self-reported allocation counter; `dispose()` does not
  decrement it. So the counter doesn't drop even when memory is freed.
- We confirmed RSS independently via `ps -o rss= -p <pid>`. The RSS drop was real but
  trivial. The counter and RSS agree that dispose did almost nothing.
- After dispose, `LayoutLibraryLoader.ourLibraryCache` still holds a `SoftReference` to
  the disposed instance; the IDE must be restarted before another render is attempted.

Verdict: **`dispose()` only releases what `Bridge` Java fields directly hold.** It does
not unload native `.dylib`s, doesn't touch AWT/Java2D, doesn't clear font caches, doesn't
drop the user-app classloader, doesn't free direct ByteBuffers.

### Experiment 3: `malloc_zone_pressure_relief(NULL, 0)` via JNA (macOS)

Hypothesis: libSystem may be holding free()'d-but-unreturned pages (fragmentation).

| Metric | Before | After 3× GC | After malloc_zone_pressure_relief |
|---|---|---|---|
| heap | 2059 MB | — | 2180 MB |
| native counter | 1737 MB | — | 1737 MB |
| RSS | 10108 MB | 10067 MB | 10067 MB |

`malloc_zone_pressure_relief` reported **0 bytes freed.** libSystem itself confirmed it
has no fragmented pages to release.

Verdict: **No malloc fragmentation to recover.** The unaccounted memory is genuinely live
and reachable from somewhere — it's not sitting in libc's free list.

## Where the unaccounted ~6 GB lives

In the 10 GB run: heap (2059) + Layoutlib counter (1737) + fragmentation (0) leaves
**~6.3 GB unaccounted**. Likely holders, in descending order of probable magnitude:

| Memory holder | Owner | Reachable via API? |
|---|---|---|
| AWT/Java2D native pixel buffers + accelerated surfaces | `sun.java2d.SurfaceManagerFactory` (JVM-global static) | No — `BufferedImage.flush()` only drops cached surface, not the native pixel data path |
| AWT/Skia font glyph caches | `sun.font.FontManager` + native Skia | No — designed to be JVM-lifetime |
| Skia native (path/paint/canvas scratch buffers) | Loaded `.dylib`s' internal allocators | No — `.dylib` text segments and internal allocators stay loaded until process exit |
| User-app classloader + loaded classes (metaspace) | `ProjectClassLoader` cached for reuse | No — referenced from `LayoutLibraryLoader.ourLibraryCache` and `BridgeContext` |
| Compose runtime statics (`Snapshot`, `Recomposer`, global composition list) | Static fields **inside the user-app classloader** | No — Layoutlib has no reference to them |
| Direct `ByteBuffer`s used by Skia/NIO interop | JVM direct memory pool | Partial — only freed when `Cleaner` runs AND no strong refs remain |

None of these are reachable through any public or semi-public API we found.

## Why each per-state spike is ~145 MB

A new state-index render brings in:
1. **New drawables** — different test data → different icons/images. Each one decodes to
   native bitmaps via Skia.
2. **Fresh Compose recomposition** — the Compose runtime caches measure/layout state
   **per ComposeView instance**, not per `@Composable` function. Each state-index render
   creates a new `ComposeViewAdapter` with its own internal `ComposeView` and a fresh
   measurement cache.
3. **New `BridgeContext`** — per-render the session sets up a fresh context that loads
   themes, drawables, font configs. Even with `clearAllCaches`, the *underlying* native
   bitmaps registered with Skia/AWT remain.

The Bridge releases its handles. But the actual pixel data already crossed into Skia/AWT,
which Layoutlib does not track. From Skia's perspective, "Bridge let go of its handle"
doesn't mean anything; Skia has its own refcounts, and the surface manager / image cache
hold separate refs.

## Why no in-process call can fully fix it

To free the unaccounted memory you would need at least one of:

1. **Drop the user-app classloader** — but then every user class reloads on the next
   render (multi-second cost, defeats the purpose), AND every static reference to the
   classloader inside Layoutlib would have to be broken first. Those references are not
   exposed.
2. **Clear AWT/Skia/font statics** — these are designed to be JVM-lifetime. Most are
   package-private. Some are written in C++ with no Java API.
3. **Force the JVM to release direct memory** — `System.gc()` triggers `Cleaner`
   processing, but only for buffers with no remaining strong refs. We can't enumerate
   the live ones.

## Summary table — what each cleanup mechanism actually frees

| Mechanism | Freed in our tests |
|---|---|
| Per-render full cleanup sequence (steps 1–5 above) | Keeps Layoutlib counter Δ small (~20–50 KB/render), but RSS still grows |
| `Bridge.clearAllCaches(null)` post-batch | −5 MB RSS (noise) |
| `LayoutLibrary.dispose()` + GC×3 post-batch | −44 MB RSS (0.6% of total) |
| `malloc_zone_pressure_relief(NULL, 0)` post-batch | 0 MB (libSystem confirms no fragmentation) |
| **Total in-process recoverable** | **<100 MB out of 7+ GB** |

## Conclusion

The leak is JVM-wide native accumulation that no in-process call can release. Only ending
the process reclaims it. This matches what the `com.android.compose.screenshot` Gradle
plugin appears to do (subprocess isolation — but verify before relying on this claim).

**Action items for any future attempt:**
- Do NOT spend more time hunting in-process cleanup hooks. The above table is
  comprehensive; we tried everything reachable.
- The only viable architecture is **subprocess isolation**: spawn a worker JVM, render
  N composables, kill it, respawn. Either custom (`ProcessBuilder` with the IDE's
  Layoutlib JARs on the classpath) or by shelling out to the Gradle screenshot plugin.
- A useful **stopgap** while subprocess work proceeds: reduce per-render allocation
  surface (smaller render device, e.g. 600×1000 instead of pixel_5's 1080×2400) to
  double the number of renders before the IDE OOMs. Does not stop growth; only delays
  it.

# Subprocess rendering (since 2026-05)

The in-process leak documented above is bounded by spawning a worker JVM per
modulePath, recycled every 50 renders. After recycle, the OS reclaims all native
memory the previous worker held. With the in-process renderer growing RSS by
>5 GB per ~300 renders, this is the only architecture that keeps the IDE usable
through long sessions.

## Components

- **`:render-worker` Gradle module** — packages a Layoutlib-direct renderer with
  no IntelliJ Platform on the classpath. Shadow plugin emits
  `render-worker/build/libs/render-worker-all.jar` (~2.5 MB) bundled into the
  plugin's `lib/` directory by the `copyWorkerFatJar` Gradle task.
- **`:ipc` Gradle module** — `@Serializable` DTOs (`WorkerInit`, `RenderRequest`,
  `RenderResponse`, `Outcome`, `ShutdownRequest`) shared between plugin and worker.
- **`RenderWorkerMain`** — worker entry point. Reads one `WorkerInit` line from
  stdin, then loops reading `RenderRequest` lines and emitting `RenderResponse`
  lines on stdout. A line starting with `{"reason"` terminates the loop.
- **`LayoutlibBootstrap`** — locates `framework_res.jar`, `icudt*.dat`, native
  libs; aliases `ro.system.product.cpu.abilist*` to `ro.product.cpu.abilist*`
  (required by `android.os.Build.<clinit>`); calls the 8-arg `Bridge.init`.
- **`WorkerRenderer`** — per-render: builds `SessionParams` (direct constructor,
  no `Builder`), wires `FrameworkResourceRepository` for theme resolution, forces
  the content frame to MATCH_PARENT, seeds the virtual clocks + advances the Compose
  frame clock, calls `session.render(true)`, writes PNG/JPEG. The raw-`Bridge` render
  path has hard constraints `RenderTask` would otherwise handle for you — see
  "Worker render pipeline (hard constraints)" below.
- **`WorkerLoggerProvider`** — registered at `Int.MAX_VALUE` priority via SPI to
  shadow Layoutlib's default `IJLoggerProvider` (which would pull in IntelliJ
  Platform classes the worker doesn't have).
- **`SubprocessRendererClient`** — owns one worker process. Spawns via
  `ProcessBuilder` using Studio's bundled JBR, JSON-line IPC over stdin/stdout,
  reader thread parses responses, `process.onExit()` handler fails any pending
  requests so callers never hang on worker crashes.
- **`SubprocessRenderer`** — facade with the same signature as
  `ComposableRenderer.render(...)`. Pools clients by `modulePath`, recycles
  each after 50 renders.
- **`WorkerClasspathAssembler`** — at runtime resolves IDE-bundled JARs via
  `PathManager.getHomePath()`. In Studio 2025.x the layoutlib JAR lives under
  `Contents/plugins/design-tools/lib/`; supporting JARs (`layoutlib-api`,
  `sdk-common`, `sdk-tools`, `android.jar`, `ui-animation-tooling-internal`)
  are under `Contents/plugins/android/lib/`; `kxml2*.jar` lives there too.
  Platform `Contents/lib/module-intellij.libraries.guava.jar` and
  `module-intellij.libraries.fastutil.jar` are also required.
- **`UserModuleClasspathResolver`** — derives the user app's runtime classpath
  via `OrderEnumerator` on the IntelliJ `Module`, appended with
  `build/tmp/kotlin-classes/debug/`, the AGP-generated `R.jar`, and the SDK
  platform's `android.jar`.
- **`RendererRouter`** — chooses `ComposableRenderer` vs `SubprocessRenderer`
  based on `PluginSettingsService.State.useSubprocessRenderer`.

## Worker render pipeline (hard constraints)

The worker drives Layoutlib through the **raw `Bridge.createSession` / `RenderSession` API**
(no Studio `RenderTask`), so `WorkerRenderer` must reproduce by hand the setup `RenderTask`
does for free. Each item below was a blank-render bug; treat them as hard constraints. The
in-process rules at the top of this file still apply conceptually — this section is how the
raw API satisfies them.

### Force the content frame to MATCH_PARENT — THE blank-render fix

When driven via raw `Bridge.createSession`, Layoutlib lays out the window content frame
(`android.R.id.content`) with degenerate **0×0 `LinearLayout` params** (width=0, height=0,
weight=0). With `clipChildren=true` the entire app / Compose subtree — which itself measures
correctly to the full device size — is clipped to a 0×0 parent and never paints; only the
DecorView background draws, so every preview is uniform `#FAFAFA`. This affects **all**
content, even a plain `<View>` with a solid background — it is not Compose-specific.

`WorkerRenderer.forceContentFrameFill()` fixes it: after `createSession`, walk up to the
DecorView, `findViewById(android.R.id.content)`, set its layout params to `MATCH_PARENT`
(plus `weight = 1` for the `LinearLayout.LayoutParams`), then `requestLayout()`. The
subsequent forced-measure render lays it out full-size and content paints. The in-process
`RenderTask` path receives a `MATCH_PARENT` content frame already, which is why in-process
never hit this and the subprocess worker was blank from the original spike onward.

This is the proximate cause of the long "subprocess renders are blank" saga. The decor tree
at the failure looked like:

```
DecorView    945×1680  clipChildren=true
  LinearLayout 945×1680
    FrameLayout 0×0  lp=0x0 weight=0     ← content frame collapsed
      … → ComposeViewAdapter 945×1680    ← content, full size, clipped to nothing
```

### Single render — never render twice (raw-API form)

Same rule as the in-process "Never call `render()` twice" above, but the trap is different:
`Bridge.createSession` runs its **own** `scene.render(true)` internally, so a subsequent
explicit `render()` is already the second call and comes back blank. Set
`FLAG_DO_NOT_RENDER_ON_CREATE = true` so `createSession` only inflates; then a single
`session.render(true)` (forceMeasure) is the first and only render.

### Seed the virtual clocks before advancing the frame clock

Mirrors "Frame clock advancement" above, but the raw API requires seeding the clocks
manually (`RenderTask` does it for you). Immediately after `createSession`:
`setSystemBootTimeNanos(0)`, `setSystemTimeNanos(0)`, `setElapsedFrameTimeNanos(500ms)` —
the elapsed-frame value is what makes `RenderSessionImpl` run its first-frame priming draw
(it gates on `mElapsedFrameTimeNanos >= 0`). Then in the `executeCallbacks` loop, call
`setSystemTimeNanos(t)` **before** each `executeCallbacks(t)`: `BridgeRenderSession`
reads the virtual clock (`System_Delegate.nanoTime`), not the method argument, so without
this the Compose `MonotonicFrameClock` never advances.

### Other `RenderTask`-parity `SessionParams` flags

`FLAG_KEY_DISABLE_BITMAP_CACHING = true` (fresh image + `ImageReader` surface each render)
and `FLAG_KEY_RESULT_IMAGE_AUTO_SCALE = true`, both matching `RenderTask`.

### Exact-dp root sizing

The `ComposeViewAdapter` root XML uses **exact dp**, not `wrap_content`.
`tools:previewWidth/previewHeight` are NOT honored by ComposeViewAdapter 1.10.x. The
`RenderRequest` carries the device size in **pixels** (`widthPx`/`heightPx` + `density`); the
HardwareConfig canvas is those pixels verbatim, and the root dp is derived back via
`pxToCeilDp(px, density)` (rounded up so the root never undershoots the canvas). With the
content frame forced to `MATCH_PARENT`, the exact-dp root gives the composition bounded
constraints to fill the device.

### Match in-process `useCustomConfig` gating — fontScale / night / locale

The `RenderRequest` is built plugin-side from `PreviewRenderConfig`, and it **must reproduce the
in-process `Configuration` reset**: when `useCustomConfig == false`, `ComposableRenderer` ignores
the persisted custom settings and renders at neutral defaults (pixel_5, light, `fontScale=1.0`,
system locale, no decor). The subprocess path resolves these through `resolveDeviceRenderSpec`
(device pixels) and `resolveEffectivePreviewParams` (night mode / fontScale / locale / system UI) —
do not read the raw `previewConfig` fields directly. The original bug: `fontScale`, `uiMode` and
`locale` were sent unconditionally, so a persisted `fontScale = 2.0` with `useCustomConfig = false`
made the worker draw text at 2× while Android Studio drew it at 1× (same 1080×2340 canvas, doubled
glyphs). `EffectivePreviewParamsTest` pins this gating.

### Debugging without IDE restarts

The worker speaks newline-delimited JSON IPC (`WorkerInit` then `RenderRequest` on stdin,
`RenderResponse` on stdout). It can be driven straight from a shell against the freshly
built `render-worker/build/libs/render-worker-all.jar` and the IDE's bundled Layoutlib JARs
(see `WorkerClasspathAssembler` for the exact list) — no IDE involved, ~3 s per render. This
is the fast loop for any future render-path change; verify a real render produces non-uniform
pixels before deploying.

## Why a separate JVM

See "Native memory leak investigation" above for the empirical record. Short
version: no in-process cleanup mechanism frees the >5 GB of native memory
Layoutlib/AWT/Skia accumulate per IDE session. Only ending the process reclaims
it. `LayoutLibrary.dispose()` freed only 44 MB of 7098 MB (0.6%); libc-level
`malloc_zone_pressure_relief` freed 0 MB. The unaccounted ~6 GB lives in
AWT/Java2D native pixel buffers, JVM metaspace held by the user-app classloader,
Skia native caches, and direct ByteBuffers — none reachable from any
in-process API.

## What still runs in-process

- Module/facet resolution, classpath assembly, source-file lookup
  (needs the IntelliJ Project model — too heavy to put in the worker).
- `PreviewCache` path resolution and incremental-skip logic.
- Telemetry recording (worker reports `durationMs`; the plugin records the
  `RenderSample`).
- Settings + UI.

## Toggle behavior

`PluginSettingsService.State.useSubprocessRenderer` (default `false` during
rollout — opt-in). When `false`, `RendererRouter` calls
`ComposableRenderer.render()` (in-process). When `true`, it calls
`SubprocessRenderer.render()`. Toggle is exposed in Preferences → AppFlower.

## Known limitations

- **User resources now wired (since 2026-06)** — the worker resolves user
  `R.drawable/string/color/dimen/font/…` via res dirs + R.jars discovered
  plugin-side (`UserResourceResolver`) and passed through `WorkerInit`. The
  worker builds an `AarSourceResourceRepository` per dir, merges it (namespace
  `RES_AUTO`) with the framework repo, scans the R.jars for the
  `int → ResourceReference` map (`ResourceIdRegistry`), and fails soft with typed
  placeholders for any declared-but-unloaded resource. **Remaining gaps:**
  resource *namespacing* (`android.nonTransitiveRClass` per-package namespaces)
  is unsupported (all user resources are treated as `RES_AUTO`); the app's
  Android base theme is not resolved (framework `Theme.Material.Light.NoActionBar`
  is used — Compose previews self-wrap their theme).
- **Device size + density now resolved per-device (since 2026-06)** — the plugin
  reads the selected device's real screen geometry from `ConfigurationManager`
  (`ComposableRenderer.deviceScreenDims` → `resolveDeviceRenderSpec`) and ships
  pixels + density in the `RenderRequest`, mirroring the in-process `Configuration`.
  Previously the worker hardcoded 420 dpi and used the *custom* dp values for every
  device, so e.g. `pixel_5` rendered at 945×1680 instead of 1080×2340.
- **`targetApiLevel` hardcoded to 34** — should derive from the Android
  module's `compileSdk`.
- **No output cropping** — the in-process renderer crops to root view bounds;
  the worker writes the full canvas. Cosmetic; can be added later.
- **Per-render timeout is 60 s** — composables that legitimately take longer
  will be killed.

## Lifecycle

- Workers spawn lazily on first `SubprocessRenderer.render()` for a given
  `modulePath`.
- After 50 renders the client is `close()`d and removed; the next render
  respawns. This keeps native memory bounded.
- On plugin unload, `PluginUnloadListener` calls `SubprocessRenderer.shutdownAll()`
  which closes every client.
- If a worker crashes or its stdout closes, `Process.onExit()` fails any
  pending requests with synthetic FAIL responses so callers never hang.
