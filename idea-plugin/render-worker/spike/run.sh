#!/bin/bash
# Use /bin/bash explicitly — /usr/bin/env bash may resolve to old /bin/sh on macOS via zsh aliasing,
# and we need bash 4+ assoc arrays.
# Phase A spike runner — compiles SpikeMain.kt and runs it against the bundled Layoutlib.
# Usage:
#   ./run.sh                         # Task 2: just call Bridge.init
#   ./run.sh <user-cp> <fqn>         # Task 3: render a composable
#
# Assumes:
#   - Android Studio at /Applications/Android Studio.app
#   - Android SDK     at $HOME/Library/Android/sdk
#
set -euo pipefail

STUDIO="${STUDIO:-/Applications/Android Studio.app}"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"

KOTLINC="$STUDIO/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
KOTLIN_LIB="$STUDIO/Contents/plugins/Kotlin/kotlinc/lib"
KOTLIN_STDLIB="$KOTLIN_LIB/kotlin-stdlib.jar"
JAVA="$STUDIO/Contents/jbr/Contents/Home/bin/java"

[[ -d "$STUDIO" ]] || { echo "Android Studio not found at $STUDIO" >&2; exit 1; }
[[ -d "$SDK" ]]    || { echo "Android SDK not found at $SDK" >&2; exit 1; }
[[ -x "$KOTLINC" ]] || chmod +x "$KOTLINC" "$STUDIO/Contents/plugins/Kotlin/kotlinc/bin/kotlin"

ANDROID_LIB="$STUDIO/Contents/plugins/android/lib"
DESIGN_LIB="$STUDIO/Contents/plugins/design-tools/lib"

# Working set of JARs needed for compile + run.
# Layoutlib + its API + sdk-common (resources/HardwareConfig deps) + kxml2 (pull parser).
JARS=(
  "$DESIGN_LIB/layoutlib.jar"
  "$ANDROID_LIB/layoutlib-api.jar"
  "$ANDROID_LIB/sdk-common.jar"
  "$ANDROID_LIB/sdk-tools.jar"        # FrameworkResourceRepository, ResourceResolver
  "$ANDROID_LIB/android.jar"          # com.android.tools.environment.Logger
  "$STUDIO/Contents/lib/module-intellij.libraries.guava.jar"
  "$STUDIO/Contents/lib/module-intellij.libraries.fastutil.jar"  # FrameworkResourceRepository needs Object2IntMap
)
# Auto-discover kxml2 (needed for KXmlParser)
KXML="$(find "$STUDIO/Contents" -name 'kxml2*.jar' 2>/dev/null | head -1)"
[[ -n "$KXML" ]] && JARS+=("$KXML")
# Auto-discover other common helpers we may need at runtime.
for n in common.jar guava.jar tools-common-25.jar resources.aar; do
  hit="$(find "$STUDIO/Contents" -name "$n" 2>/dev/null | head -1)"
  [[ -n "$hit" ]] && JARS+=("$hit")
done
# Add EVERY jar in plugins/android/lib whose name implies it carries a Layoutlib dep.
# (cheap insurance — we narrow down only if init fails with a different ClassNotFound)
for n in annotations.jar common.jar android-common.jar android-base-common.jar repository.jar; do
  hit="$ANDROID_LIB/$n"
  [[ -f "$hit" ]] && JARS+=("$hit")
done

# Dedupe (bash 3.2 compat — no assoc arrays).
CP_RAW=""
for j in "${JARS[@]}"; do
  case ":$CP_RAW:" in
    *":$j:"*) ;; # already present
    *) CP_RAW="${CP_RAW:+$CP_RAW:}$j" ;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
mkdir -p "$OUT"

echo "[run.sh] Compiling SpikeMain.kt …"
"$KOTLINC" -cp "$CP_RAW" -d "$OUT/spike.jar" "$HERE/SpikeMain.kt"

# Pack META-INF/services into the spike jar so our SpikeLoggerProvider gets registered
# via ServiceLoader. Without this, IJLoggerProvider wins and pulls in IJ's Logger class.
(cd "$HERE/resources" && "$STUDIO/Contents/jbr/Contents/Home/bin/jar" -uf "$OUT/spike.jar" META-INF)

CP_RUN="$OUT/spike.jar:$KOTLIN_STDLIB:$CP_RAW"

USER_CP="${1:-}"
FQN="${2:-}"

echo "[run.sh] Running spike …"
"$JAVA" \
  -Djava.awt.headless=true \
  -cp "$CP_RUN" \
  spike.SpikeMainKt \
  "$STUDIO" "$SDK" "$USER_CP" "$FQN"
