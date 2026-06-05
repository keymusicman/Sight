# Render-worker local repro harness

Drive the **deployed `render-worker` shadow jar** directly from a shell — no IDE, no plugin
restart — to debug Layoutlib renders. One render is ~3 s, so this is the fast loop for any
render-path change (fonts, system UI, sizing, blank-render bugs).

It speaks the worker's real IPC: newline-delimited JSON on stdin (`WorkerInit` then
`RenderRequest`), `RenderResponse` JSON on stdout, everything else (Layoutlib logs, stack
traces, `DIAG-*` prints) on stderr.

The scripts are committed in **`idea-plugin/local-repro/`**. Generated IPC + output go to a
scratch work dir (`/tmp/af-repro` by default; override with `AF_REPRO_WORK`), which is *not*
committed.

## Files

Committed (in `idea-plugin/local-repro/`):

| File | Role |
|------|------|
| `run.sh` | Assembles the classpath, runs `make_input.py`, pipes `init.json`+`req.json` into `RenderWorkerMainKt`, writes `out.png` + `stderr.log` into the work dir. |
| `make_input.py` | Parses the captured WorkerInit dump → `init.json` (`WorkerInit`) + `req.json` (`RenderRequest`). Edit the `render_req` block to change composable / device / flags. |

Generated (in the work dir, default `/tmp/af-repro/`):

| File | Role |
|------|------|
| `init.json`, `req.json` | Generated IPC messages. |
| `out.png`, `stderr.log` | Render output + worker stderr from the last run. |

Input (captured once, default `/tmp/af-worker-init.txt`, override with `AF_WORKER_INIT`):
the worker's per-project inputs (Studio root, user classpath, res dirs, R.jars) — see
"Refreshing inputs".

## Usage

```bash
# 1. Build the jar after any worker change:
./gradlew :render-worker:shadowJar

# 2. Render (defaults to render-worker/build/libs/render-worker-all.jar):
./idea-plugin/local-repro/run.sh
#    or against a specific jar:
./idea-plugin/local-repro/run.sh /path/to/render-worker-all.jar

# 3. Inspect (work dir defaults to /tmp/af-repro):
open /tmp/af-repro/out.png
grep -iE "DIAG|error|exception" /tmp/af-repro/stderr.log
```

Overridable via env: `APPFLOWER_STUDIO` (Studio install), `AF_REPRO_WORK` (work dir),
`AF_WORKER_INIT` (captured input dump).

To change what's rendered, edit the `render_req` dict in `make_input.py`:
- `composableFqn` must be the **layoutlib-resolved** name (file-facade class), e.g.
  `pkg.OnboardingScreenKt.OnboardingPreview` — not `pkg.OnboardingPreview`. The plugin
  normally does this PSI resolution; here you write it by hand.
- `widthPx`/`heightPx`/`density`: device pixels (pixel_5 = `1080×2340 @ 440`).
- `showSystemUi`, `nightMode`, `fontScale`, `locale`: per-render config.

## Refreshing inputs (`/tmp/af-worker-init.txt`)

The classpath / res dirs / R.jars are resolved per-project inside the IDE, so they must be
captured from a **real** plugin run. The file format `make_input.py` expects:

```
androidStudioRoot=/Applications/Android Studio Preview.app
---rJarPaths---
<abs path to each generated R.jar>
---userResDirs---
<abs path to each res dir>
---classpath---
<abs path to each user/dep/AAR jar>
```

Capture it by temporarily logging the `WorkerInit` the plugin builds (in
`SubprocessRenderer.clientFor` / `WorkerClasspathAssembler`) and reformatting to the sections
above, or by dumping it from `RenderWorkerMain` on the first `WorkerInit` it reads. Re-capture
whenever the target project's dependencies change.

## run.sh classpath (Studio 2025.x)

The worker needs Layoutlib + a few IntelliJ libs on the classpath (the plugin assembles this
via `WorkerClasspathAssembler`; `run.sh` mirrors it):

```
plugins/design-tools/lib/layoutlib.jar
plugins/android/lib/{layoutlib-api,sdk-common,sdk-tools,android-base-common,android,ui-animation-tooling-internal,kxml2-2.3.0}.jar
lib/intellij.libraries.{guava,fastutil}.jar
+ render-worker-all.jar
```

Run with Studio's bundled JBR (`Contents/jbr/.../bin/java`) and these `--add-opens`
(java.base lang/reflect/io/util, java.desktop sun.font/sun.java2d).

## Gotchas

- **Always rebuild the jar** (`:render-worker:shadowJar`) before `run.sh` — it does not build
  for you. A stale jar silently renders old behavior.
- A real (non-blank) render is ~20 KB+ PNG with non-uniform pixels. A ~6 KB uniform PNG means
  the blank-render path (see `idea-plugin/COMPOSABLE_RENDERING.md`).
- stdout is reserved for the `RenderResponse` JSON; everything diagnostic goes to stderr.
- `targetApiLevel` is pinned to 36 in `make_input.py` (android-36 `build.prop` carries
  `ro.build.version.sdk_full`, which `Build.VERSION.<clinit>` needs — older platforms throw).
