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
