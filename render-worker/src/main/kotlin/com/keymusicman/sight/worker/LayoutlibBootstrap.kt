package com.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ILayoutLog
import com.android.layoutlib.bridge.Bridge
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * Initializes Android Studio's bundled Layoutlib for use from a standalone JVM
 * (no IntelliJ Platform on the classpath).
 *
 * Encapsulates everything that's required before [Bridge.createSession]:
 *
 *  * locating the layoutlib data dir under
 *    `Contents/plugins/design-tools/resources/layoutlib/data/` (Studio 2025.x layout —
 *    older versions had it under `plugins/android/resources/layoutlib/data/`),
 *  * reading `build.prop` from the SDK platform and aliasing
 *    `ro.system.product.cpu.abilist*` -> `ro.product.cpu.abilist*` (otherwise
 *    `Build.<clinit>` reads an empty array and crashes with `ArrayIndexOutOfBoundsException`),
 *  * arch detection (`mac-arm` vs `mac-x86_64` vs `linux` etc.) for the native libs
 *    directory containing `layoutlib_jni` / `libandroid_runtime`,
 *  * scanning `icu/` for the version-specific `.dat` file,
 *  * calling [Bridge.init] with the **correct** 8-argument signature.
 *
 * The plan draft's 6-arg signature is wrong for Studio 2025.x — see SPIKE_NOTES.md
 * "Bridge.init signature".
 */
class LayoutlibBootstrap(
    private val androidStudioRoot: File,
    private val targetApiLevel: Int,
) {
    fun init(): Bridge {
        require(androidStudioRoot.isDirectory) {
            "Android Studio root not a directory: $androidStudioRoot"
        }

        val layoutlibDataDir = locateLayoutlibDataDir(androidStudioRoot)
            ?: error("Layoutlib data dir not found under $androidStudioRoot")

        // Build the property map from the SDK platform's build.prop. Layoutlib's native
        // JNI_OnLoad seeds android.os.SystemProperties from this map automatically — no
        // manual SystemProperties.set() call is needed (see SPIKE_NOTES "native property
        // store + build.prop quirks").
        val sdkBuildProp = locateBuildProp(androidStudioRoot, targetApiLevel)
        val buildPropMap = readBuildProp(sdkBuildProp)
        ensureAbiList(buildPropMap)

        val fontsDir = File(layoutlibDataDir, "fonts").apply {
            require(isDirectory) { "fonts dir not found: $this" }
        }
        val hyphenDataDir = File(layoutlibDataDir, "hyphen-data").absolutePath + "/"
        val keyboardPaths = arrayOf(File(layoutlibDataDir, "keyboards/Generic.kcm").absolutePath)
        val icuDataFile = File(layoutlibDataDir, "icu")
            .listFiles { f -> f.name.endsWith(".dat") }?.firstOrNull()
            ?: error("No .dat file under ${File(layoutlibDataDir, "icu")}")
        val nativeLibDir = locateNativeLibDir(layoutlibDataDir)

        val log: ILayoutLog = StdErrLayoutLog()

        System.err.println("[worker] Bridge.init paths:")
        System.err.println("[worker]   layoutlibData = $layoutlibDataDir")
        System.err.println("[worker]   fontsDir      = $fontsDir")
        System.err.println("[worker]   nativeLibDir  = $nativeLibDir")
        System.err.println("[worker]   icuPath       = ${icuDataFile.absolutePath}")
        System.err.println("[worker]   hyphenDir     = $hyphenDataDir")
        System.err.println("[worker]   keyboards     = ${keyboardPaths.toList()}")
        System.err.println("[worker]   buildProp     = $sdkBuildProp (apiLevel=$targetApiLevel)")

        val bridge = Bridge()
        val ok = bridge.init(
            /* properties     */ buildPropMap,
            /* fontsDir       */ fontsDir,
            /* nativeLibDir   */ nativeLibDir.absolutePath,
            /* icuDataPath    */ icuDataFile.absolutePath,
            /* hyphenDataDir  */ hyphenDataDir,
            /* keyboardPaths  */ keyboardPaths,
            /* enumValueMap   */ emptyMap<String, Map<String, Int>>(),
            /* log            */ log,
        )
        check(ok) { "Bridge.init() returned false" }
        return bridge
    }

    /** Returns the layoutlib `data/` directory, checking 2025.x first then the legacy path. */
    fun locateLayoutlibDataDir(): File? = locateLayoutlibDataDir(androidStudioRoot)

    /** Returns the file used by [Bridge.init] as the `build.prop` source for this target API. */
    fun locateBuildProp(): File = locateBuildProp(androidStudioRoot, targetApiLevel)

    /** Returns the SDK platform `framework_res.jar` path (or null when not present). */
    fun frameworkResJar(): File? = locateLayoutlibDataDir(androidStudioRoot)
        ?.let { File(it, "framework_res.jar").takeIf { f -> f.isFile } }

    companion object {
        private fun locateLayoutlibDataDir(studioRoot: File): File? {
            val candidates = listOf(
                // Studio 2025.x
                File(studioRoot, "Contents/plugins/design-tools/resources/layoutlib/data"),
                // older Studio versions
                File(studioRoot, "Contents/plugins/android/resources/layoutlib/data"),
            )
            return candidates.firstOrNull { it.isDirectory }
        }

        /**
         * Picks the build.prop matching [targetApiLevel] from the layoutlib data dir,
         * falling back to the highest available platform from the user's SDK if needed.
         */
        private fun locateBuildProp(studioRoot: File, targetApiLevel: Int): File {
            // First check the SDK platforms in the user's $ANDROID_HOME / $ANDROID_SDK_ROOT.
            val sdkRoot = System.getenv("ANDROID_HOME")?.let(::File)?.takeIf { it.isDirectory }
                ?: System.getenv("ANDROID_SDK_ROOT")?.let(::File)?.takeIf { it.isDirectory }
                ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.isDirectory }
                ?: File(System.getProperty("user.home"), "Android/Sdk").takeIf { it.isDirectory }

            if (sdkRoot != null) {
                val platformsDir = File(sdkRoot, "platforms")
                val exactPlatform = platformsDir
                    .listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
                    ?.firstOrNull { p ->
                        val name = p.name.removePrefix("android-")
                        // Match either "34" or "34.0" / "34.1" etc.
                        name == targetApiLevel.toString() || name.startsWith("$targetApiLevel.")
                    }
                val highestPlatform = platformsDir
                    .listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
                    ?.maxByOrNull { it.name }
                val platform = exactPlatform ?: highestPlatform
                val candidate = platform?.let { File(it, "build.prop") }
                if (candidate?.isFile == true) return candidate
            }

            error(
                "Could not locate build.prop. Set ANDROID_HOME or install an Android " +
                    "platform under <sdk>/platforms/android-NN containing build.prop."
            )
        }

        private fun readBuildProp(file: File): MutableMap<String, String> {
            val props = Properties()
            FileInputStream(file).use { props.load(it) }
            val out: MutableMap<String, String> = mutableMapOf()
            for ((k, v) in props) out[k.toString()] = v.toString()
            return out
        }

        /**
         * Aliases `ro.system.product.cpu.abilist*` -> `ro.product.cpu.abilist*` if the
         * latter is missing, and supplies arm64 defaults so `Build.<clinit>` doesn't
         * `ArrayIndexOutOfBoundsException` on its `SUPPORTED_64_BIT_ABIS[0]` access.
         */
        private fun ensureAbiList(map: MutableMap<String, String>) {
            fun alias(systemKey: String, productKey: String) {
                val v = map[systemKey]
                if (!v.isNullOrBlank() && map[productKey].isNullOrBlank()) map[productKey] = v
            }
            alias("ro.system.product.cpu.abilist", "ro.product.cpu.abilist")
            alias("ro.system.product.cpu.abilist32", "ro.product.cpu.abilist32")
            alias("ro.system.product.cpu.abilist64", "ro.product.cpu.abilist64")
            if (map["ro.product.cpu.abilist"].isNullOrBlank())
                map["ro.product.cpu.abilist"] = "arm64-v8a"
            if (map["ro.product.cpu.abilist64"].isNullOrBlank())
                map["ro.product.cpu.abilist64"] = "arm64-v8a"
            if (map["ro.product.cpu.abilist32"].isNullOrBlank())
                map["ro.product.cpu.abilist32"] = "armeabi-v7a,armeabi"
        }

        /**
         * Probes the layoutlib data dir for the platform-specific native lib directory
         * containing `layoutlib_jni` and `libandroid_runtime`.
         */
        private fun locateNativeLibDir(layoutlibDataDir: File): File {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch")
            val candidates: List<String> = when {
                os.contains("mac") || os.contains("darwin") -> when (arch) {
                    "aarch64" -> listOf("mac-arm/lib64", "mac/lib64")
                    "x86_64", "amd64" -> listOf("mac-x86_64/lib64", "mac/lib64", "mac-arm/lib64")
                    else -> listOf("mac-arm/lib64", "mac/lib64", "mac-x86_64/lib64")
                }
                os.contains("linux") -> listOf("linux/lib64")
                os.contains("windows") -> listOf("win/lib64")
                else -> listOf("mac-arm/lib64", "linux/lib64", "win/lib64")
            }
            for (rel in candidates) {
                val d = File(layoutlibDataDir, rel)
                if (d.isDirectory) return d
            }
            error(
                "Could not locate layoutlib native lib dir under $layoutlibDataDir for " +
                    "os=$os arch=$arch (tried ${candidates.joinToString()})"
            )
        }
    }
}

