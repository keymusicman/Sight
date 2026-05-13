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
