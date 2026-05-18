# Design: Copy graph-annotations & graph-processor + sample-android

Date: 2026-06-09

## Goal

Bring the KSP annotation library from the private Android repo into AppFlower so it lives alongside the visualizer that consumes its output. Add a minimal sample Android project that demonstrates annotation usage and generates a real `app-graph-fragment.json`.

## Scope

- Copy `:graph-annotations` and `:graph-processor` into AppFlower as first-class subprojects.
- Renames: package to `com.keymusicman.graph`. Annotation class names (`AppFlowGraph`, `AppFlowScreen`, `AppFlowTransition`) unchanged (Sight rename is a separate step).
- Add `sample-android/` as a standalone nested Gradle project with a minimal Android app.
- No changes to existing AppFlower modules.

## Module placement

Two new subprojects added at root level in `settings.gradle.kts`:

```
:graph-annotations   kotlin("jvm"), zero dependencies, 3 source files
:graph-processor     kotlin("jvm"), depends on ksp-api, one unit test
```

Both modules:
- `group = "com.keymusicman"`
- `version = "0.1.0"`

`gradle/libs.versions.toml` additions:
- `ksp = "2.3.2"` version entry
- `ksp-api = { module = "com.google.devtools.ksp:symbol-processing-api", version.ref = "ksp" }` library alias
- `ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }` plugin alias

## graph-annotations source

Package `com.keymusicman.graph`. Three files copied verbatim except for package declaration:

- `AppGraph.kt` — `@AppFlowGraph(name, entrySubgraph, dropUnconnected)`
- `AppFlowScreen.kt` — `@AppFlowScreen(subgraph, id, isRoot)`, source retention, targets FUNCTION, repeatable
- `AppFlowTransition.kt` — `@AppFlowTransition(toScreen, toSubgraph, fromScreen, fromSubgraph, trigger)`, source retention, targets FUNCTION + CLASS, repeatable

## graph-processor source

Package `com.keymusicman.graph.processor`. Files copied verbatim except:
- Package declarations updated
- Annotation FQNs in `getSymbolsWithAnnotation(...)` calls updated to `com.keymusicman.graph.*`
- `META-INF/services/` provider registration file updated to new FQN

Existing unit test (`FragmentJsonTest`) copied unchanged (tests `buildFragmentJson` directly, no FQN strings).

## sample-android project

Standalone Gradle project at `sample-android/`. Has its own `settings.gradle.kts`, `build.gradle.kts`, and `gradle/libs.versions.toml`. Not included in AppFlower's root `settings.gradle.kts`.

### Composite build wiring

`sample-android/settings.gradle.kts`:
```kotlin
includeBuild("..")  // exposes com.keymusicman:graph-annotations, com.keymusicman:graph-processor
include(":app")
```

`sample-android/app/build.gradle.kts` dependencies:
```kotlin
implementation("com.keymusicman:graph-annotations")
ksp("com.keymusicman:graph-processor")
```

KSP options:
```kotlin
ksp {
    arg("projectRoot", rootDir.absolutePath)
    arg("moduleName", ":app")
}
```

Running `./gradlew :app:kspDebugKotlin` from `sample-android/` writes `sample-android/build/graph/app-graph-fragment.json`.

### Sample app content

Minimal Android app — no real navigation, no uikit dependencies. A stub `MainActivity` with empty `setContent {}`.

Screens are `@Preview`-only composables annotated with `@AppFlowScreen` + `@AppFlowTransition`. Each screen has 2–3 `@Preview` annotations covering distinct states (e.g. default, loading, error).

Subgraphs and screens:

| Subgraph    | Screen        | isRoot | Transitions |
|-------------|---------------|--------|-------------|
| onboarding  | Welcome       | true   | → Login (cta_tap) |
| onboarding  | Login         | false  | → main/Home (login_success) |
| main        | Home          | true   | → profile/Profile (avatar_tap) |
| profile     | Profile       | false  | ← (entered from main) |

`AppGraph.kt` declares `@AppFlowGraph(entrySubgraph = "onboarding")` on an object, with any cross-subgraph wiring not expressible on the screen function itself.

### sample-android gradle versions

- AGP: 8.10.1 (latest stable)
- Kotlin: 2.3.0 (matches AppFlower root)
- KSP: 2.3.2 (matches graph-processor dependency)
- Compose BOM: latest stable at time of implementation
- `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`

## What this does NOT include

- Publishing to Maven Central or any remote repository.
- A Gradle convention plugin for applying annotations in consumer projects.
- The Sight rename (`@SightGraph` etc.) — deferred to a separate step.
- Any UI beyond stub screens (no real theming, no navigation graph wiring).
