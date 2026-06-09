package com.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile

/**
 * Maps the user app's compiled resource ids (from R.jar `static int` fields, final or not) to
 * [ResourceReference]s under the [ResourceNamespace.RES_AUTO] namespace, and back.
 *
 * Layoutlib's `Resources_Delegate.getValue(id,…)` resolves an int id to a reference via
 * `LayoutlibCallback.resolveResourceId`; without this map every user resource id resolves to
 * null and `painterResource` / `stringResource` dereference the null value during composition.
 *
 * Non-namespaced builds only — all user resources live under RES_AUTO. `styleable` (int[])
 * fields are skipped.
 */
class ResourceIdRegistry private constructor(
    private val idToRef: Map<Int, ResourceReference>,
    private val refToId: Map<ResourceReference, Int>,
) {
    fun resolve(id: Int): ResourceReference? = idToRef[id]
    fun idOf(ref: ResourceReference): Int? = refToId[ref]
    fun declaredRefs(): Set<ResourceReference> = refToId.keys
    fun size(): Int = idToRef.size

    companion object {
        private val IGNORED_TYPES = setOf("styleable")

        // Base for synthesized R ids. Sits below the real app package (`0x7f……`) and the
        // callback's on-demand generator (`0x7f040000+`, see WorkerLayoutlibCallback) so the three
        // id spaces never collide; the value only has to round-trip through resolveResourceId.
        private const val SYNTHETIC_ID_BASE = 0x7E000000

        /** Build from already-loaded `R$<type>` classes (e.g. `R.drawable::class.java`). */
        fun fromRClasses(rTypeClasses: List<Class<*>>): ResourceIdRegistry {
            val idToRef = HashMap<Int, ResourceReference>()
            val refToId = HashMap<ResourceReference, Int>()
            var synthCounter = 0
            // distinct(): the same R class can be enumerated from several jars (thin + fat) but
            // the classloader returns one Class — process it once so synthetic ids aren't churned.
            for (cls in rTypeClasses.distinct()) runCatching {
                // Derive the resource type from the BINARY name (e.g. "androidx.credentials.R$id"
                // -> "id"), NOT Class.getSimpleName(): getSimpleName() reads the InnerClasses
                // attribute and validates outer/inner consistency, which throws
                // IncompatibleClassChangeError when the R outer class and its R$<type> inner come
                // from different jars on the merged user classloader (thin feature R.jar + merged
                // app R.jar) that disagree on that attribute. The binary name is a plain string.
                val typeName = cls.name.substringAfterLast('$')
                if (typeName in IGNORED_TYPES) return@runCatching
                val type = ResourceType.fromClassName(typeName) ?: return@runCatching
                for (f in cls.declaredFields) {
                    val mods = f.modifiers
                    // Read every `static int` field. Real AGP app/library R classes use
                    // NON-final ids (`public static int`), so requiring `final` here registered
                    // zero ids and every painterResource resolved to null → blank render.
                    if (!Modifier.isStatic(mods)) continue
                    if (f.type != Int::class.javaPrimitiveType) continue // skip int[] styleables
                    val current = try { f.getInt(null) } catch (_: Throwable) { continue }
                    // Real AGP R fields are non-final with no <clinit>; their true id lives only in
                    // a ConstantValue attribute the JVM ignores for non-final fields, so getstatic
                    // (and getInt here) yields 0. Materialize a synthetic id INTO the field so the
                    // user's `getstatic R.x` returns it; resolveResourceId maps it back. Mirrors
                    // Studio's ModuleClassLoader dynamic R ids. Fields that already carry a real id
                    // (final+inlined, or clinit-initialized) are registered by that value as-is.
                    val id = if (current != 0) {
                        current
                    } else {
                        val syn = SYNTHETIC_ID_BASE + synthCounter++
                        try {
                            runCatching { f.isAccessible = true }
                            f.setInt(null, syn)
                        } catch (_: Throwable) {
                            continue // can't materialize (e.g. final/0) — leave unresolvable
                        }
                        syn
                    }
                    val ref = ResourceReference(ResourceNamespace.RES_AUTO, type, f.name)
                    idToRef[id] = ref
                    refToId[ref] = id
                }
            }.onFailure {
                System.err.println("worker: skipped R class ${cls.name}: ${it::class.java.name}: ${it.message}")
            }
            return ResourceIdRegistry(idToRef, refToId)
        }

        /**
         * Scan each jar for `…/R$<type>.class` entries, load them via [loader], and read their
         * fields. [loader] must be the user classloader (so the R classes resolve).
         */
        fun fromJars(rJarPaths: List<String>, loader: ClassLoader): ResourceIdRegistry {
            val classes = mutableListOf<Class<*>>()
            for (path in rJarPaths) {
                val jarFile = runCatching { JarFile(File(path)) }.getOrNull() ?: continue
                jarFile.use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { it.name.removeSuffix(".class").replace('/', '.') }
                        .filter { it.substringAfterLast('.').startsWith("R$") }
                        .forEach { fqn ->
                            runCatching { Class.forName(fqn, true, loader) }
                                .onSuccess { classes.add(it) }
                                .onFailure { System.err.println("worker: could not load R class $fqn: ${it.message}") }
                        }
                }
            }
            return fromRClasses(classes)
        }
    }
}
