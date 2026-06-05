#!/bin/zsh
# Drive the deployed render-worker shadow jar locally with a captured WorkerInit + a RenderRequest.
# No IDE, no plugin restart — ~3 s/render. See idea-plugin/LOCAL_REPRO.md.
#
# Usage:   run.sh [worker-jar]
# Env:     APPFLOWER_STUDIO  Android Studio install (default: /Applications/Android Studio Preview.app)
#          AF_REPRO_WORK     scratch dir for generated IPC + output (default: /tmp/af-repro)
#          AF_WORKER_INIT    captured WorkerInit dump (default: /tmp/af-worker-init.txt)
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

AS="${APPFLOWER_STUDIO:-/Applications/Android Studio Preview.app}/Contents"
JAVA="$AS/jbr/Contents/Home/bin/java"
WORKER_JAR="${1:-$REPO_ROOT/render-worker/build/libs/render-worker-all.jar}"
WORK="${AF_REPRO_WORK:-/tmp/af-repro}"
mkdir -p "$WORK"

CP="$AS/plugins/design-tools/lib/layoutlib.jar"
CP="$CP:$AS/plugins/android/lib/layoutlib-api.jar"
CP="$CP:$AS/plugins/android/lib/sdk-common.jar"
CP="$CP:$AS/plugins/android/lib/sdk-tools.jar"
CP="$CP:$AS/plugins/android/lib/android-base-common.jar"
CP="$CP:$AS/plugins/android/lib/android.jar"
CP="$CP:$AS/plugins/android/lib/ui-animation-tooling-internal.jar"
CP="$CP:$AS/lib/intellij.libraries.guava.jar"
CP="$CP:$AS/lib/intellij.libraries.fastutil.jar"
CP="$CP:$AS/plugins/android/lib/kxml2-2.3.0.jar"
CP="$CP:$WORKER_JAR"

AF_REPRO_WORK="$WORK" python3 "$SCRIPT_DIR/make_input.py"

rm -f "$WORK/out.png"
{ cat "$WORK/init.json"; echo; cat "$WORK/req.json"; echo; } | \
"$JAVA" \
  -XX:-OmitStackTraceInFastThrow -Xmx2g -Dfile.encoding=UTF-8 \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.desktop/sun.font=ALL-UNNAMED \
  --add-opens java.desktop/sun.java2d=ALL-UNNAMED \
  -cp "$CP" \
  com.keymusicman.appflowerplugin.renderworker.worker.RenderWorkerMainKt \
  2> "$WORK/stderr.log"

echo "=== done; RenderResponse JSON on stdout above; stderr in $WORK/stderr.log ==="
ls -la "$WORK/out.png" 2>/dev/null || echo "NO OUTPUT PNG (see $WORK/stderr.log)"
