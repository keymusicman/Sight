# Contributing to Sight

Thanks for your interest in Sight! Contributions of all kinds are welcome — bug reports, feature
ideas, docs fixes, and code. This guide gets you set up and explains how we work.

Sight is maintainer-led: a small core reviews and merges changes. We genuinely want active
contributors, and we'll do our best to give thoughtful reviews — but please be patient, reviews can
take a little while. Opening an issue to align *before* a large change is the fastest path to a merge.

## Ways to contribute

- **Report a bug** — open an issue with steps to reproduce, what you expected, and what happened.
- **Suggest a feature** — check [ROADMAP.md](ROADMAP.md) and open an issue to discuss it first.
- **Improve docs** — typos, clarifications, and examples are always welcome.
- **Send a pull request** — for anything non-trivial, please open or comment on an issue first so we
  can agree on the approach before you invest time.

Look for issues labelled **`good first issue`** if you're not sure where to start.

## Development setup

**Prerequisites**

- **JDK 17** (the build pins `jvmToolchain(17)` — the Android/AGP baseline)
- **Android Studio 2025.1+** (only needed for the IDE plugin / rendering work)
- Git

**Clone and build**

```shell
git clone https://github.com/keymusicman/Sight.git
cd Sight
./gradlew build -PskipSigning 
```

`-PskipSigning` skips artifact signing, which is only needed for publishing — you don't need any GPG
keys or `local.properties` to build and test locally.

**Run the tests**

```shell
./gradlew test
./gradlew :graph-renderer:test    # a single module
```

**Build the Android Studio plugin**

```shell
./gradlew :idea-plugin:buildPlugin
# → idea-plugin/plugin/build/distributions/Sight.zip
```

Install it via *Settings → Plugins → ⚙ → Install Plugin from Disk…* and point it at `Sight.zip`.

**Try the sample**

```shell
cd samples/android
./gradlew :app:kspDebugKotlin   # → app/build/graph/app-graph-fragment.json
```

The sample is a standalone Gradle project that uses `includeBuild("../..")` to depend on the
annotations and processor directly from source — handy for testing annotation changes end to end.

## Project layout

Sight is a multi-module Kotlin monorepo (JVM only). The [README](README.md#modules) has the module
table; deeper references:

- [`CLAUDE.md`](CLAUDE.md) — architecture overview and key files (also used by AI coding assistants)
- [`docs/annotation-semantics.md`](docs/annotation-semantics.md) — exact meaning of each annotation
- [`idea-plugin/plugin/COMPOSABLE_RENDERING.md`](idea-plugin/plugin/COMPOSABLE_RENDERING.md) — hard
  rules for the Layoutlib rendering path
- [`idea-plugin/plugin/LOCAL_REPRO.md`](idea-plugin/plugin/LOCAL_REPRO.md) — fast local harness for
  debugging the subprocess renderer without restarting the IDE (~3 s/render)

## Coding style

- Kotlin official style (`kotlin.code.style=official`). Match the conventions already in the files
  you touch.
- Keep modules focused: `graph-renderer` has **zero UI dependencies** by design — don't add any.
- Add or update tests for behavior changes. Make sure `./gradlew build test` is green before pushing.

## Pull request workflow

1. **Branch** off `main` (`main` is protected — direct pushes are disabled; everything lands via PR).
2. Keep PRs small and focused — one logical change per PR is much easier to review.
3. Reference the issue you're addressing (e.g. `Fixes #123`).
4. Make sure CI is green. The CI workflow runs `./gradlew build -PskipSigning` on every PR.
5. Write a clear PR description: what changed, why, and how you verified it.

We don't require a CLA. By contributing, you agree that your contributions are licensed under the
project's [Apache License 2.0](LICENSE) (inbound = outbound).

## Releases

Releases are cut by maintainers. The
[`release` workflow](.github/workflows/release.yml) automates publishing to Maven Central and the
JetBrains Marketplace when a `v*` tag is pushed.

Thanks again for helping make Sight better! 💙
