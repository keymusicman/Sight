package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceValueMap
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType
import com.android.resources.aar.AarSourceResourceRepository
import java.io.File

/**
 * A merged user resource repository built from on-disk `res` directories (the module's own
 * source res dirs + transitive-dependency res dirs, resolved plugin-side). Each dir becomes an
 * [AarSourceResourceRepository] in the [ResourceNamespace.RES_AUTO] namespace; [configuredFor]
 * merges them for a given [FolderConfiguration].
 *
 * Precedence: dirs are passed in priority order (module's own first); on a (type, name)
 * collision the higher-priority dir wins.
 */
class UserResourceRepository(resDirs: List<String>) {
    private val repos: List<AarSourceResourceRepository> = resDirs.mapIndexedNotNull { i, dir ->
        val d = File(dir)
        if (!d.isDirectory) {
            System.err.println("worker: user res dir not found, skipping: $dir")
            return@mapIndexedNotNull null
        }
        runCatching { AarSourceResourceRepository.create(d.toPath(), "user-res-$i") }
            .onFailure { System.err.println("worker: failed to load res dir $dir: ${it.message}") }
            .getOrNull()
    }

    fun isEmpty(): Boolean = repos.isEmpty()

    /** Merged RES_AUTO type→values for [fc]. First dir wins on collisions. */
    fun configuredFor(fc: FolderConfiguration): Map<ResourceType, ResourceValueMap> {
        val merged = LinkedHashMap<ResourceType, ResourceValueMap>()
        for (repo in repos) {
            val resAuto = ConfiguredResources.of(repo, fc)[ResourceNamespace.RES_AUTO] ?: continue
            for ((type, map) in resAuto) {
                val into = merged.getOrPut(type) { ResourceValueMap.create() }
                for (value in map.values()) {
                    if (!into.containsKey(value.name)) into.put(value)
                }
            }
        }
        return merged
    }
}
