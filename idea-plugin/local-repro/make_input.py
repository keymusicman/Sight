#!/usr/bin/env python3
"""Build the WorkerInit + RenderRequest JSON lines for a local worker repro.

Parses a captured WorkerInit dump and writes init.json + req.json into the work dir.
Edit the `render_req` block below to change which composable / device / flags are rendered.
See idea-plugin/LOCAL_REPRO.md.

Env:
  AF_REPRO_WORK   work dir for init.json / req.json / out.png (default: /tmp/af-repro)
  AF_WORKER_INIT  captured WorkerInit dump (default: /tmp/af-worker-init.txt)
"""
import json, os

WORK = os.environ.get("AF_REPRO_WORK", "/tmp/af-repro")
INIT_DUMP = os.environ.get("AF_WORKER_INIT", "/tmp/af-worker-init.txt")
os.makedirs(WORK, exist_ok=True)

# Dump format (see LOCAL_REPRO.md "Refreshing inputs"):
#   androidStudioRoot=<path>
#   ---rJarPaths---  / ---userResDirs---  / ---classpath---   (one abs path per line)
dump = open(INIT_DUMP).read().splitlines()
studio_root = None
r_jars, res_dirs, classpath = [], [], []
section = None
for line in dump:
    if line.startswith("androidStudioRoot="):
        studio_root = line.split("=", 1)[1]; continue
    if line.startswith("---rJarPaths"):
        section = "r"; continue
    if line.startswith("---userResDirs"):
        section = "res"; continue
    if line.startswith("---classpath---"):
        section = "cp"; continue
    if not line.strip():
        continue
    if section == "r":
        r_jars.append(line)
    elif section == "res":
        res_dirs.append(line)
    elif section == "cp":
        classpath.append(line)

worker_init = {
    "androidStudioRoot": studio_root,
    "userClasspath": classpath,
    "targetApiLevel": 36,
    "userResDirs": res_dirs,
    "rJarPaths": r_jars,
}

# composableFqn MUST be the layoutlib-resolved (file-facade) name — the plugin does this PSI
# resolution at runtime; here you write it by hand. e.g. pkg.FooScreenKt.FooPreview
render_req = {
    "requestId": "repro-1",
    "composableFqn": "com.example.feature.startup.onboarding.OnboardingScreenKt.OnboardingPreview",
    "parameterProviderFqn": "com.example.feature.startup.onboarding.OnboardingStatePreviewParameterProvider",
    "stateIndex": 0,
    "outputPath": os.path.join(WORK, "out.png"),
    "outputFormat": "PNG",
    "jpegQuality": 85,
    # Device size in PIXELS + density (plugin resolves these from the device; pixel_5 = 1080x2340@440).
    "widthPx": 1080,
    "heightPx": 2340,
    "density": 440,
    "nightMode": False,
    "fontScale": 1.0,
    "locale": "",
    "showSystemUi": False,
}

with open(os.path.join(WORK, "init.json"), "w") as f:
    f.write(json.dumps(worker_init))
with open(os.path.join(WORK, "req.json"), "w") as f:
    f.write(json.dumps(render_req))

print(f"work={WORK} studio_root={studio_root}")
print(f"classpath entries={len(classpath)} resDirs={len(res_dirs)} rJars={len(r_jars)}")
