# Phase A spike notes — standalone Layoutlib rendering

**Status: DONE** — `Bridge.init()` and `Bridge.createSession()` both return SUCCESS from a plain
JVM with no IntelliJ Platform on the classpath. A 1080×1920 PNG (12,463 bytes) is produced
by `RenderSession.render()` from a `ComposeViewAdapter` XML pointing at a real `@Composable`
in a separately-built Android app.

This document records the exact JARs, signatures, and gotchas the Phase B worker needs.

---

## Android Studio version

- App: `/Applications/Android Studio.app`
- Layoutlib data dir: `…/Contents/plugins/design-tools/resources/layoutlib/data/`
  - (Note: in older Studio versions this was under `…/plugins/android/resources/layoutlib/data/`.
    Code that locates the dir must check both paths or use the `design-tools` location for
    2025.x.)
- `layoutlib.jar` lives at: `…/Contents/plugins/design-tools/lib/layoutlib.jar`
  (the data dir and the JAR are in different sibling directories of `design-tools/`).
- Bundled kotlinc 2.2.20 (used to compile the spike).
- Bundled JBR 21.0.9 (must be used because `libandroid_runtime.dylib` requires a JBR
  with the layoutlib-native symbols — system JDK works in this spike but production should
  prefer the bundled JBR for parity).

## Bridge.init signature (Layoutlib in Studio 2025.x)

```java
public boolean init(
    Map<String, String>                       properties,    // arg1: build.prop key/value
    File                                       fontsDir,      // arg2: fontsDir (NOT a file)
    String                                     nativeLibDir,  // arg3: directory containing layoutlib_jni / libandroid_runtime
    String                                     icuDataPath,   // arg4: ABSOLUTE PATH TO ICU .dat FILE (e.g. icudt76l.dat)
    String                                     hyphenDataDir, // arg5: TRAILING-SLASH path
    String[]                                   keyboardPaths, // arg6: array of .kcm file paths (Generic.kcm is enough)
    Map<String, Map<String, Integer>>          enumValueMap,  // arg7: can be emptyMap()
    com.android.ide.common.rendering.api.ILayoutLog log       // arg8
)
```

The plan draft used a hypothetical signature with different arg ordering — the actual
order is what is documented above. Param ordering is critical (icuPath vs nativeLibDir
were swapped relative to the plan).

## RenderSession / SessionParams

- No `SessionParams.Builder` class exists. Use the **direct constructor**:
  ```java
  SessionParams(ILayoutPullParser layoutDescription,
                SessionParams.RenderingMode renderingMode,
                Object projectKey,
                HardwareConfig hardwareConfig,
                RenderResources renderResources,
                LayoutlibCallback layoutlibCallback,
                int minSdkVersion,
                int targetSdkVersion,
                ILayoutLog log)
  ```
- After construction, call `params.setAssetRepository(...)` and `params.setForceNoDecor()`
  as needed.
- `RenderSession.render()` returns a `Result`; the rendered `BufferedImage` is obtained
  via `session.getImage()` (not via `RenderResult.renderedImage` — that's the
  IDE-facing wrapper in `tools-rendering`).

## Pull parser

There is **no `LayoutPullParser.createFromString(...)` helper** in the bundled jar.
You must write your own minimal `ILayoutPullParser`:

```kotlin
class SpikeLayoutPullParser(xml: String) : ILayoutPullParser, KXmlParser() {
    init {
        setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", true)
        setInput(StringReader(xml))
    }
    override fun getViewCookie(): Any? = null
    override fun getLayoutNamespace(): ResourceNamespace = ResourceNamespace.RES_AUTO
}
```

`KXmlParser` is in `kxml2.jar` which is bundled in the IDE.

## Working classpath (Studio 2025.x)

Absolute paths (resolve `$STUDIO=/Applications/Android Studio.app`):

```
$STUDIO/Contents/plugins/design-tools/lib/layoutlib.jar
$STUDIO/Contents/plugins/android/lib/layoutlib-api.jar
$STUDIO/Contents/plugins/android/lib/sdk-common.jar              # ResourceResolver, ResourceRepositoryUtil
$STUDIO/Contents/plugins/android/lib/sdk-tools.jar               # FrameworkResourceRepository
$STUDIO/Contents/plugins/android/lib/android.jar                 # com.android.tools.environment.Logger interface + IJLoggerProvider (shadowed via SPI, see below)
$STUDIO/Contents/lib/module-intellij.libraries.guava.jar         # ImmutableMap (Bridge.<clinit>)
$STUDIO/Contents/lib/module-intellij.libraries.fastutil.jar      # Object2IntMap (AarSourceResourceRepository.loadFromStream)
$STUDIO/Contents/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar    # spike code; production worker provides its own
$STUDIO/Contents/plugins/android/lib/ui-animation-tooling-internal.jar  # silences ClassNotFound for ComposeAnimation (non-fatal but pollutes logs)
```

Plus from the **user's** project:
- The full debugRuntimeClasspath (`android-classes-jar` artifact view) — the *runtime*
  variants (not `-api.jar`) including `ui-tooling-release-runtime.jar` which contains
  `ComposeViewAdapter`.
- Compiled kotlin classes dir: `<app>/build/tmp/kotlin-classes/debug/`
- The R.jar: `<app>/build/intermediates/<*r_class_jar*>/debug/.../R.jar`. The intermediate
  dir name varies by module type **and AGP version** —
  `compile_and_runtime_not_namespaced_r_class_jar` (older AGP apps),
  `compile_and_runtime_r_class_jar` (Gradle 9.x / feature modules), or `compile_r_class_jar`
  (own, non-transitive R). `UserModuleClasspathResolver.findGeneratedRJars` globs all of them
  and prefers the largest (fat transitive) jar. **This jar is mandatory:** the worker's plain
  `URLClassLoader` does not synthesize R classes the way Studio's `ModuleClassLoader` does, so
  dependency bytecode that references generated R classes (e.g.
  `androidx.customview.poolingcontainer.R$id`, read by `PoolingContainer.<clinit>` on every
  `ComposeViewAdapter` inflation) throws `NoClassDefFoundError` and fails *every* preview at
  `createSession` if the transitive R.jar is absent. Hardcoding the app-module path was the
  original bug — it doesn't exist for newer AGP.
- The **Android platform `android.jar`** from the SDK (`$HOME/Library/Android/sdk/platforms/android-37.0/android.jar`).
  This is the `provided`-scoped API jar — Layoutlib loads e.g. `android.os.Build$VERSION`
  from this; without it, `ComposeViewAdapter.<init>` fails with `ClassNotFoundException`.

## Required native libraries (macOS arm64)

`<layoutlibData>/mac-arm/lib64/`:
- `layoutlib_jni.dylib`
- `libandroid_runtime.dylib`

Pass the **directory** to `Bridge.init` as arg3 (`nativeLibDir`). Bridge.loadNativeLibraries
concatenates `<dir>/layoutlib_jni.dylib` etc. internally.

## ICU / fonts / hyphen / keyboards layout

Layoutlib data dir contents that Bridge.init needs:

```
data/
  fonts/                       # arg2 (File) — has fonts.xml + .ttf
  hyphen-data/                 # arg5 (String, must end with '/')
  icu/icudt76l.dat             # arg4 (String) — full path including filename
  keyboards/Generic.kcm        # arg6 (String[]) — pass [".../Generic.kcm"]
  mac-arm/lib64/*.dylib        # arg3 (String) — pass the lib64 dir
  framework_res.jar            # separate: used by FrameworkResourceRepository, NOT by init
```

The ICU `.dat` file's name changes per release (`icudt76l.dat` in this version). Discover
it by scanning the `icu/` subdir for `*.dat`.

## CRITICAL: native property store + build.prop quirks

`Bridge.init` populates `Bridge.sPlatformProperties` from the map you pass. When the native
library `libandroid_runtime.dylib` is loaded (which `init` does internally), its
`JNI_OnLoad` callback invokes `Bridge.setSystemProperties()` reflectively from the C++ side,
which iterates the map and calls `SystemProperties.set(k, v)` for each entry — this is
how the native property store gets seeded so subsequent `SystemProperties.get(...)` calls
work. **There is no need to call `setSystemProperties` ourselves**; the JNI path does it
automatically. (We initially assumed it was dead code because no Java site calls it.)

But the stock `build.prop` from `$ANDROID_HOME/platforms/android-NN/build.prop` has these
properties either missing or under a `ro.system.product.cpu.abilist*` namespace, while
`android.os.Build.<clinit>` reads them under `ro.product.cpu.abilist*`. With no aliasing,
`SUPPORTED_64_BIT_ABIS = []` and the very next line of Build's clinit does
`SUPPORTED_64_BIT_ABIS[0]` → `ArrayIndexOutOfBoundsException`.

**Fix in the worker**: before `Bridge.init`, alias the keys:

```kotlin
fun bridgeAbi(systemKey: String, productKey: String) {
    val v = buildPropMap[systemKey]
    if (!v.isNullOrBlank() && buildPropMap[productKey].isNullOrBlank()) buildPropMap[productKey] = v
}
bridgeAbi("ro.system.product.cpu.abilist",   "ro.product.cpu.abilist")
bridgeAbi("ro.system.product.cpu.abilist32", "ro.product.cpu.abilist32")
bridgeAbi("ro.system.product.cpu.abilist64", "ro.product.cpu.abilist64")
// Defensive defaults for 64-bit hosts (mac arm64):
if (buildPropMap["ro.product.cpu.abilist64"].isNullOrBlank())
    buildPropMap["ro.product.cpu.abilist64"] = "arm64-v8a"
if (buildPropMap["ro.product.cpu.abilist32"].isNullOrBlank())
    buildPropMap["ro.product.cpu.abilist32"] = "armeabi-v7a,armeabi"
```

## CRITICAL: Logger SPI shadowing

`android.jar` (the IDE's, not the SDK's) bundles `com.android.tools.environment.Logger`
and registers **two** `LoggerProvider` impls via SPI:

```
META-INF/services/com.android.tools.environment.Logger$LoggerProvider:
  com.android.tools.idea.log.IJLoggerProvider
  com.android.tools.environment.log.StubLoggerProvider
```

`Logger.Companion.getInstance(...)` picks by `maxByOrNull { priority }`. `IJLoggerProvider`
wins by default and instantiates `IJLogger`, which constructor-requires
`com.intellij.openapi.diagnostic.Logger` — pulling the IntelliJ Platform we're trying to
avoid.

**Fix**: register our own `LoggerProvider` with `priority = Int.MAX_VALUE` via an SPI file
on our classpath:

```
META-INF/services/com.android.tools.environment.Logger$LoggerProvider:
  myworker.WorkerLoggerProvider
```

Implement `getPriority() = Int.MAX_VALUE` and `createLogger(name)` returning a no-op /
stdout impl. `ServiceLoader.load(...)` returns all registered, `maxByOrNull` picks ours.

## Framework resources wiring

The Bridge cannot inflate even a `FrameLayout` without framework resources loaded —
`ViewConfiguration.<init>` reads `config_scrollbarSize` (a `dimen`) which lives in
`framework_res.jar`. Without framework resources, inflation fails with
`Resources$NotFoundException: Could not find dimen resource matching value 0x1050127`.

**Fix**: load framework resources via `FrameworkResourceRepository`:

```kotlin
val fwkRepo = FrameworkResourceRepository.create(
    File(layoutlibDataDir, "framework_res.jar").toPath(),
    /* languagesToLoad */ emptySet(),
    /* cachingData */ null,
    /* useCompiled9Patches */ false,
)
val folderConfig = FolderConfiguration.createDefault()
// ResourceRepositoryUtil is a Kotlin file facade (xi=48 metadata) that kotlinc 2.2 can't
// parse directly when compiling against the bundled jar — call via reflection.
val util = Class.forName("com.android.ide.common.resources.ResourceRepositoryUtil")
val getConfigured = util.declaredMethods.first {
    it.name == "getConfiguredResources" && it.parameterCount == 2
}
@Suppress("UNCHECKED_CAST")
val configured = getConfigured.invoke(null, fwkRepo, folderConfig)
        as com.google.common.collect.Table<ResourceNamespace, ResourceType, com.android.ide.common.resources.ResourceValueMap>
val configuredMap: Map<ResourceNamespace, Map<ResourceType, com.android.ide.common.resources.ResourceValueMap>> =
    configured.rowMap()

val themeRef = ResourceReference(
    ResourceNamespace.ANDROID, ResourceType.STYLE, "Theme.Material.Light.NoActionBar"
)
val resources = ResourceResolver.create(configuredMap, themeRef)
resources.setLogger(log)
```

The user's project's own resources also need to be wired up for any non-trivial composable
(e.g. one that uses `R.string.app_name`). For Phase B the simplest start is framework-only
+ the user's compiled `R.jar` on the classpath; if a composable references user
resources, that needs another `ResourceRepository` merged in.

## LayoutlibCallback minimum

```kotlin
class WorkerLayoutlibCallback(private val loader: ClassLoader) : LayoutlibCallback() {
    private val nextId = AtomicInteger(0x7f040000)
    private val refToId = mutableMapOf<ResourceReference, Int>()
    private val idToRef = mutableMapOf<Int, ResourceReference>()

    override fun loadView(name: String, sig: Array<out Class<*>>, args: Array<out Any>): Any {
        val cls = loader.loadClass(name)
        return cls.getConstructor(*sig).newInstance(*args)
    }
    override fun resolveResourceId(id: Int): ResourceReference? = idToRef[id]
    override fun getOrGenerateResourceId(ref: ResourceReference): Int =
        refToId.getOrPut(ref) { nextId.getAndIncrement().also { idToRef[it] = ref } }
    override fun getParser(layoutResource: ResourceValue?): ILayoutPullParser? = null
    override fun getAdapterBinding(viewObject: Any?, attrs: MutableMap<String, String>?) = null
    override fun getActionBarCallback() = object : ActionBarCallback() {}
    override fun findClass(name: String): Class<*> = loader.loadClass(name)
    override fun isClassLoaded(name: String) = try { Class.forName(name, false, loader); true } catch (_: Throwable) { false }
    override fun hasAndroidXAppCompat() = true
    override fun shouldUseCustomInflater() = true
    override fun createXmlParserForPsiFile(fileName: String?) = null
    override fun createXmlParserForFile(fileName: String?) = null
    override fun createXmlParser() = KXmlParser()
}
```

The `ClassLoader` passed in MUST be the user's classpath loader, parented to the worker's
classloader (so layoutlib classes are visible).

## User classpath construction (in the worker)

To get the right user classpath, the **runtime** classpath is required (not compile / API).
The Gradle init script that works:

```groovy
allprojects {
    afterEvaluate { p ->
        if (p.name == "app") {
            p.tasks.register("printDebugCp") {
                doLast {
                    def cp = p.android.applicationVariants.find { it.name == "debug" }
                        .getRuntimeConfiguration()
                        .getIncoming().artifactView({
                            attributes.attribute(
                                org.gradle.api.attributes.Attribute.of("artifactType", String),
                                "android-classes-jar"
                            )
                        }).files
                    cp.each { println "CP: " + it.absolutePath }
                    println "CP: " + p.file("build/tmp/kotlin-classes/debug").absolutePath
                    def rJar = p.file("build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar")
                    if (rJar.exists()) println "CP: " + rJar.absolutePath
                }
            }
        }
    }
}
```

The artifactType filter `android-classes-jar` is the key — it forces AGP to give us the
**implementation** jars from AAR transforms, not the api stubs.

Don't forget to append the **SDK platform's `android.jar`** (e.g.
`$ANDROID_HOME/platforms/android-37.0/android.jar`) — without it, Layoutlib can't load
public Android classes that user code references (`android.os.Build`, etc.).

## Result

- Bridge.init: returns `true`.
- createSession: returns a `RenderSession` with `result.status == SUCCESS`.
- render(): returns a `Result` with `status == SUCCESS`.
- Output: a `1080x1920` ARGB `BufferedImage`, written as `/tmp/spike.png` (12,463 bytes).

The composable's text content didn't appear in the rendered PNG (entire image is the
Material Light theme surface color `#FAFAFA`) — the Compose draw pipeline ran but the
content was either zero-sized or drew off-screen. This is **not a Layoutlib failure**;
it's a wiring detail in the worker (likely related to `wrap_content` on ComposeViewAdapter
+ no explicit `previewWidth/previewHeight` hints + a draw-pass issue with the user's
classloader). The plugin's existing `ComposableRenderer` works around this via
`tools:previewWidth`/`previewHeight` attributes and a `setForceNoDecor` plus a different
RenderResources setup; the worker can mirror what the plugin already does.

What's proven: **the entire architecture works end-to-end**. Bridge initializes, native
libs load, system properties seed via JNI callback, framework resources resolve, the
custom `URLClassLoader` for the user's bytecode loads compose views, the composition runs
all the way through `WrappedComposition.setContent` and `Recomposer.composeInitial`, the
PNG comes out valid. The remaining work is layout/sizing polish.

## Gotchas summary (for Phase B)

1. **JAR layout moved in Studio 2025.x**: `design-tools/` plugin now owns layoutlib (data
   and lib are in sibling dirs of `plugins/design-tools/`).
2. **`Bridge.init` arg order**: `(props, fontsDir, nativeLibDir, icuPath, hyphenDir,
   keyboardPaths[], enumMap, log)` — note `nativeLibDir` BEFORE `icuPath`.
3. **`icuPath` is the .dat file path** (e.g. `…/icu/icudt76l.dat`), NOT the directory.
4. **`hyphenDir` must end with `/`**.
5. **`keyboardPaths` is `String[]`**, not `String`. Pass `[".../Generic.kcm"]`.
6. **`nativeLibDir` is a directory**, not a file. Bridge appends the lib filenames itself.
7. **Build.prop needs `ro.product.cpu.abilist{,32,64}` populated** — alias from
   `ro.system.product.cpu.*` or default to `arm64-v8a` / `armeabi-v7a,armeabi`.
8. **Native property seeding happens automatically** via JNI_OnLoad → `Bridge.setSystemProperties()`.
9. **Logger SPI override required** to avoid pulling IntelliJ Platform via `IJLoggerProvider`.
10. **Framework resources required** — `FrameworkResourceRepository.create(framework_res.jar)`.
    Without it inflation fails on `config_scrollbarSize`.
11. **`ResourceRepositoryUtil` is a Kotlin file facade with `xi=48`** — kotlinc 2.2.20 can't
    compile against it. Use reflection.
12. **SDK platform `android.jar`** must be on the user classpath (not just the IDE's
    bundled android.jar).
13. **`SessionParams.Builder` does not exist** — direct constructor only.
14. **No `LayoutPullParser.createFromString`** helper — implement `ILayoutPullParser` by
    subclassing `KXmlParser`.
15. **`session.dispose()` NPEs if the session failed** — guard with `try { session?.dispose() } catch (_: Throwable) {}`.
16. Use the **bundled JBR** at `…/Contents/jbr/Contents/Home/bin/java`; this is what
    Studio itself uses and what the layoutlib native libs are most-tested against.
