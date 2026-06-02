package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile

/**
 * Maps the user app's compiled resource ids (from R.jar `static final int` fields) to
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

        /** Build from already-loaded `R$<type>` classes (e.g. `R.drawable::class.java`). */
        fun fromRClasses(rTypeClasses: List<Class<*>>): ResourceIdRegistry {
            val idToRef = HashMap<Int, ResourceReference>()
            val refToId = HashMap<ResourceReference, Int>()
            for (cls in rTypeClasses) {
                val typeName = cls.simpleName // "R$drawable".simpleName == "drawable"
                if (typeName in IGNORED_TYPES) continue
                val type = ResourceType.fromClassName(typeName) ?: continue
                for (f in cls.declaredFields) {
                    val mods = f.modifiers
                    if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) continue
                    if (f.type != Int::class.javaPrimitiveType) continue // skip int[] styleables
                    val id = try { f.getInt(null) } catch (_: Throwable) { continue }
                    val ref = ResourceReference(ResourceNamespace.RES_AUTO, type, f.name)
                    idToRef[id] = ref
                    refToId[ref] = id
                }
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
