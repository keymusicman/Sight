/*
 * Phase A spike — prove that Android Studio's bundled Layoutlib can render a @Composable
 * from a plain JVM (no IntelliJ Platform on the classpath).
 *
 * Usage:
 *   spike <android-studio-root> <android-sdk-root> [<user-classpath>] [<composable-fqn>]
 *
 *   android-studio-root  : e.g. /Applications/Android Studio.app
 *   android-sdk-root     : e.g. /Users/<you>/Library/Android/sdk
 *   user-classpath       : (optional) classpath roots separated by ':' for the composable
 *   composable-fqn       : (optional) e.g. com.example.HelloKt.Hello (file-facade + method)
 *
 * Task 2 success: Bridge.init returns true.
 * Task 3 success: writes a non-blank PNG to /tmp/spike.png.
 */

package spike

import com.android.ide.common.rendering.api.Bridge as RenderingBridge
import com.android.ide.common.rendering.api.HardwareConfig
import com.android.ide.common.rendering.api.ILayoutLog
import com.android.ide.common.rendering.api.ILayoutPullParser
import com.android.ide.common.rendering.api.LayoutlibCallback
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.rendering.api.AssetRepository
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.layoutlib.bridge.Bridge as LayoutlibBridge
import com.android.resources.Density
import com.android.resources.ResourceType
import com.android.resources.ScreenOrientation
import com.android.resources.ScreenRound
import com.android.resources.ScreenSize
import com.android.resources.aar.FrameworkResourceRepository
import org.kxml2.io.KXmlParser
import java.io.File
import java.io.FileInputStream
import java.io.StringReader
import java.net.URLClassLoader
import java.util.Properties
import javax.imageio.ImageIO

private val LOG_PREFIX = "[spike]"

private fun log(msg: String) = println("$LOG_PREFIX $msg")
private fun logErr(msg: String) = System.err.println("$LOG_PREFIX ERROR: $msg")

fun main(args: Array<String>) {
    if (args.size < 2) {
        logErr("Usage: spike <android-studio-root> <android-sdk-root> [<user-cp>] [<composable-fqn>]")
        kotlin.system.exitProcess(2)
    }
    val studioRoot = File(args[0]).absoluteFile
    val sdkRoot    = File(args[1]).absoluteFile
    val userCp     = args.getOrNull(2)?.takeIf { it.isNotBlank() }
    val composable = args.getOrNull(3)?.takeIf { it.isNotBlank() }

    require(studioRoot.isDirectory) { "android-studio-root not a dir: $studioRoot" }
    require(sdkRoot.isDirectory)    { "android-sdk-root not a dir: $sdkRoot" }

    val designToolsResources = File(studioRoot, "Contents/plugins/design-tools/resources/layoutlib")
    val layoutlibDataDir     = File(designToolsResources, "data")
    require(layoutlibDataDir.isDirectory) { "Layoutlib data dir not found: $layoutlibDataDir" }

    // Pick the highest installed Android platform.
    val platformsDir = File(sdkRoot, "platforms")
    val platform = platformsDir.listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
        ?.maxByOrNull { it.name }
        ?: error("No android-NN platform installed under $platformsDir")
    log("Using platform: ${platform.name}")

    val buildPropFile = File(platform, "build.prop")
    require(buildPropFile.isFile) { "build.prop missing for ${platform.name}: $buildPropFile" }

    // === Build init args ===
    val props = Properties().apply { FileInputStream(buildPropFile).use { load(it) } }
    val buildPropMap: MutableMap<String, String> = mutableMapOf()
    for ((k, v) in props) buildPropMap[k.toString()] = v.toString()
    // android.os.Build.SUPPORTED_ABIS reads `ro.product.cpu.abilist` directly. Newer SDK
    // build.prop files only define `ro.system.product.cpu.abilist*` — alias them so
    // Build.<clinit> doesn't crash with ArrayIndexOutOfBoundsException on empty abilist.
    fun bridgeAbi(systemKey: String, productKey: String) {
        val v = buildPropMap[systemKey]
        if (!v.isNullOrBlank() && buildPropMap[productKey].isNullOrBlank()) buildPropMap[productKey] = v
    }
    bridgeAbi("ro.system.product.cpu.abilist",   "ro.product.cpu.abilist")
    bridgeAbi("ro.system.product.cpu.abilist32", "ro.product.cpu.abilist32")
    bridgeAbi("ro.system.product.cpu.abilist64", "ro.product.cpu.abilist64")
    // Build.<clinit> reads SUPPORTED_{32,64}_BIT_ABIS[0] unconditionally. On a 64-bit host
    // (macOS arm64), Process.is64Bit()==true → SUPPORTED_64_BIT_ABIS[0] → AIOOBE when
    // ro.product.cpu.abilist64 is empty (which it is in stock platform/build.prop). Default it.
    if (buildPropMap["ro.product.cpu.abilist"].isNullOrBlank())   buildPropMap["ro.product.cpu.abilist"]   = "arm64-v8a"
    if (buildPropMap["ro.product.cpu.abilist64"].isNullOrBlank()) buildPropMap["ro.product.cpu.abilist64"] = "arm64-v8a"
    if (buildPropMap["ro.product.cpu.abilist32"].isNullOrBlank()) buildPropMap["ro.product.cpu.abilist32"] = "armeabi-v7a,armeabi"

    val fontsDir       = File(layoutlibDataDir, "fonts")
    val hyphenDataDir  = File(layoutlibDataDir, "hyphen-data").absolutePath + "/"
    val keyboardPaths  = arrayOf(File(layoutlibDataDir, "keyboards/Generic.kcm").absolutePath)
    // ICU: Bridge expects the ICU data .dat file path.
    val icuDataFile = File(layoutlibDataDir, "icu").listFiles { f -> f.name.endsWith(".dat") }?.firstOrNull()
        ?: error("No .dat file under ${File(layoutlibDataDir, "icu")}")
    val icuPath = icuDataFile.absolutePath

    // Native libs root (mac-arm). Bridge.loadNativeLibraries reads files named "layoutlib_jni" /
    // "libandroid_runtime" from this directory; arg is the DIR, not the file path.
    val nativeLibDir = File(layoutlibDataDir, "mac-arm/lib64").absolutePath
    require(File(nativeLibDir).isDirectory) { "Native lib dir missing: $nativeLibDir" }

    val enumValueMap: Map<String, Map<String, Int>> = emptyMap()
    val log: ILayoutLog = StdoutLayoutLog()

    log("Calling Bridge.init …")
    log("  fontsDir     = $fontsDir")
    log("  nativeLibDir = $nativeLibDir")
    log("  icuPath      = $icuPath")
    log("  hyphenPath   = $hyphenDataDir")
    log("  keyboards    = ${keyboardPaths.toList()}")

    // Discovered Bridge.init signature:
    //   init(
    //     Map<String,String> properties,        // arg1: build.prop key/value
    //     File fontsDir,                         // arg2: passed to SystemFonts_Delegate.setFontLocation
    //     String nativeLibDir,                   // arg3: dir containing layoutlib_jni / libandroid_runtime
    //     String icuDataPath,                    // arg4: absolute path to ICU .dat file
    //     String hyphenDataDir,                  // arg5: trailing-slash dir of .hyb files
    //     String[] keyboardPaths,                // arg6: array of .kcm file paths
    //     Map<String,Map<String,Integer>> enums, // arg7
    //     ILayoutLog log,                        // arg8
    //   ) : boolean
    val bridge = LayoutlibBridge()
    val ok = bridge.init(
        buildPropMap,
        fontsDir,
        nativeLibDir,
        icuPath,
        hyphenDataDir,
        keyboardPaths,
        enumValueMap,
        log,
    )
    if (!ok) {
        logErr("Bridge.init returned false")
        kotlin.system.exitProcess(3)
    }
    log("Bridge initialized SUCCESSFULLY")

    if (userCp == null || composable == null) {
        log("Task 2 DONE. Skipping render — no user classpath / composable provided.")
        bridge.dispose()
        return
    }

    // === Task 3: render a composable ===
    val userJars = userCp.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it) }
    val userUrls = userJars.map { it.toURI().toURL() }.toTypedArray()
    log("User classpath (${userUrls.size} entries):")
    userUrls.forEach { log("  $it") }

    // ComposeViewAdapter splits tools:composableName on the last '.'  ⇒ left=class, right=method.
    val classPart  = composable.substringBeforeLast('.')
    val methodName = composable.substringAfterLast('.')
    log("Rendering composable: class=$classPart method=$methodName")

    // Parent loader = the spike's loader (so layoutlib classes are visible);
    // Then add user's classpath on top.
    val userLoader = URLClassLoader(userUrls, RenderingBridge::class.java.classLoader)

    val xml = """
        <androidx.compose.ui.tooling.ComposeViewAdapter
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            tools:composableName="$composable" />
    """.trimIndent()

    val parser = SpikeLayoutPullParser(xml)

    val hw = HardwareConfig(
        /* screenWidth      */ 1080,
        /* screenHeight     */ 1920,
        /* density          */ Density.XXHIGH, // 480
        /* xdpi             */ 420f,
        /* ydpi             */ 420f,
        /* screenSize       */ ScreenSize.NORMAL,
        /* orientation      */ ScreenOrientation.PORTRAIT,
        /* screenRound      */ ScreenRound.NOTROUND,
        /* softwareButtons  */ true,
    )

    val callback = SpikeLayoutlibCallback(userLoader)

    // Wire up framework resources from layoutlib data dir. The dir must contain framework_res.jar.
    val frameworkResJar = File(layoutlibDataDir, "framework_res.jar")
    log("Loading framework resources from $frameworkResJar …")
    val fwkRepo = FrameworkResourceRepository.create(
        frameworkResJar.toPath(),
        /* languagesToLoad */ emptySet(),
        /* cachingData */ null,
        /* useCompiled9Patches */ false,
    )
    val folderConfig = FolderConfiguration.createDefault()
    // ResourceRepositoryUtil is a Kotlin file facade — call the extension fn via reflection
    // because kotlinc 2.2 can't parse the metadata of the bundled sdk-common.jar.
    val util = Class.forName("com.android.ide.common.resources.ResourceRepositoryUtil")
    val getConfigured = util.declaredMethods.first {
        it.name == "getConfiguredResources" && it.parameterCount == 2 &&
                com.google.common.collect.Table::class.java.isAssignableFrom(it.returnType)
    }
    @Suppress("UNCHECKED_CAST")
    val configured = getConfigured.invoke(null, fwkRepo, folderConfig)
            as com.google.common.collect.Table<ResourceNamespace, ResourceType, com.android.ide.common.resources.ResourceValueMap>
    val configuredMap: Map<ResourceNamespace, Map<ResourceType, com.android.ide.common.resources.ResourceValueMap>> =
        configured.rowMap()
    log("Framework resources loaded: ${fwkRepo.allResources.size} items")

    val themeRef = ResourceReference(
        ResourceNamespace.ANDROID,
        ResourceType.STYLE,
        "Theme.Material.Light.NoActionBar",
    )
    val resources = ResourceResolver.create(configuredMap, themeRef)
    resources.setLogger(log)
    val targetSdk = props.getProperty("ro.build.version.sdk")?.toIntOrNull() ?: 34
    log("Target SDK = $targetSdk")

    val params = SessionParams(
        /* layoutDescription */ parser,
        /* renderingMode     */ SessionParams.RenderingMode.NORMAL,
        /* projectKey        */ Any(),
        /* hardwareConfig    */ hw,
        /* renderResources   */ resources,
        /* layoutlibCallback */ callback,
        /* minSdkVersion     */ 21,
        /* targetSdkVersion  */ targetSdk,
        /* log               */ log,
    )
    params.setAssetRepository(SpikeAssetRepository())
    params.setForceNoDecor()


    log("Creating session …")
    val session = bridge.createSession(params)
    val createRes = session.result
    log("createSession result: status=${createRes.status} success=${createRes.isSuccess}")
    if (!createRes.isSuccess) {
        createRes.exception?.printStackTrace()
    }

    log("Advancing frame clock …")
    var nowNs = System.nanoTime()
    var frames = 0
    var more = session.executeCallbacks(nowNs)
    frames++
    while ((more || frames < 5) && frames < 10) {
        nowNs += 16_666_666L
        more = session.executeCallbacks(nowNs)
        frames++
    }
    log("Frame clock advanced $frames frame(s), hasMoreCallbacks=$more")

    log("Calling session.render() …")
    val renderRes = session.render()
    log("render result: status=${renderRes.status} success=${renderRes.isSuccess}")
    renderRes.exception?.let { it.printStackTrace() }

    val image = session.image
    if (image == null) {
        logErr("session.image == null")
        session.dispose()
        bridge.dispose()
        kotlin.system.exitProcess(4)
    }
    log("Image size: ${image.width}x${image.height}")
    val out = File("/tmp/spike.png")
    ImageIO.write(image, "png", out)
    log("Wrote ${out.absolutePath} (${out.length()} bytes)")
    session.dispose()
    bridge.dispose()
    log("Spike DONE")
}

// ----- minimal helpers below -----

private class StdoutLayoutLog : ILayoutLog {
    override fun warning(tag: String?, message: String?, viewCookie: Any?, data: Any?) {
        println("$LOG_PREFIX [layoutlib WARN][$tag] $message")
    }
    override fun fidelityWarning(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) {
        println("$LOG_PREFIX [layoutlib FIDELITY][$tag] $message")
        throwable?.let { System.err.println(it.stackTraceToString()) }
    }
    override fun error(tag: String?, message: String?, viewCookie: Any?, data: Any?) {
        System.err.println("$LOG_PREFIX [layoutlib ERROR][$tag] $message")
    }
    override fun error(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) {
        System.err.println("$LOG_PREFIX [layoutlib ERROR][$tag] $message")
        throwable?.let { System.err.println(it.stackTraceToString()) }
    }
    override fun logAndroidFramework(priority: Int, tag: String?, message: String?) {
        println("$LOG_PREFIX [android $priority][$tag] $message")
    }
}

/** Minimal ILayoutPullParser that wraps kxml2 parsing of an XML string. */
private class SpikeLayoutPullParser(xml: String) :
    ILayoutPullParser, KXmlParser() {
    init {
        setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", true)
        setInput(StringReader(xml))
    }
    override fun getViewCookie(): Any? = null
    override fun getLayoutNamespace(): ResourceNamespace = ResourceNamespace.RES_AUTO
}

private class SpikeLayoutlibCallback(private val loader: ClassLoader) : LayoutlibCallback() {
    private val nextId = java.util.concurrent.atomic.AtomicInteger(0x7f040000)
    private val refToId = mutableMapOf<ResourceReference, Int>()
    private val idToRef = mutableMapOf<Int, ResourceReference>()

    override fun loadView(name: String, signature: Array<out Class<*>>, args: Array<out Any>): Any {
        val cls = loader.loadClass(name)
        val ctor = cls.getConstructor(*signature)
        return ctor.newInstance(*args)
    }

    override fun resolveResourceId(id: Int): ResourceReference? = idToRef[id]

    override fun getOrGenerateResourceId(ref: ResourceReference): Int =
        refToId.getOrPut(ref) {
            val id = nextId.getAndIncrement()
            idToRef[id] = ref
            id
        }

    override fun getParser(layoutResource: ResourceValue?): ILayoutPullParser? = null

    override fun getAdapterBinding(viewObject: Any?, attributes: MutableMap<String, String>?) = null

    override fun getActionBarCallback() = object : com.android.ide.common.rendering.api.ActionBarCallback() {}

    override fun findClass(name: String): Class<*> = loader.loadClass(name)

    override fun isClassLoaded(name: String): Boolean = try {
        Class.forName(name, false, loader); true
    } catch (_: Throwable) { false }

    override fun hasAndroidXAppCompat(): Boolean = true
    override fun shouldUseCustomInflater(): Boolean = true

    // XmlParserFactory
    override fun createXmlParserForPsiFile(fileName: String?) = null
    override fun createXmlParserForFile(fileName: String?) = null
    override fun createXmlParser() = KXmlParser()
}

/**
 * SPI override for com.android.tools.environment.Logger — registered via
 * META-INF/services/com.android.tools.environment.Logger$LoggerProvider so it outranks
 * IJLoggerProvider (which is bundled in android.jar and would otherwise be picked,
 * pulling in com.intellij.openapi.diagnostic.Logger which doesn't exist standalone).
 */
class SpikeLoggerProvider : com.android.tools.environment.Logger.LoggerProvider {
    override val priority: Int = Int.MAX_VALUE
    override fun createLogger(category: String): com.android.tools.environment.Logger = StdoutEnvLogger(category)
}

private class StdoutEnvLogger(private val cat: String) : com.android.tools.environment.Logger {
    override fun warn(p0: String, p1: Throwable?)  { println("[env $cat WARN]  $p0"); p1?.printStackTrace() }
    override fun error(p0: String, p1: Throwable?) { System.err.println("[env $cat ERROR] $p0"); p1?.printStackTrace() }
    override fun info(p0: String, p1: Throwable?)  { println("[env $cat INFO]  $p0") }
    override fun debug(p0: String, p1: Throwable?) { /* drop */ }
    override val isDebugEnabled: Boolean = false
}

private class SpikeAssetRepository : AssetRepository() {
    override fun isSupported(): Boolean = true
    override fun openAsset(path: String?, mode: Int) = null
    override fun openNonAsset(cookie: Int, path: String?, mode: Int) = null
}
