package com.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceRepository
import com.android.ide.common.resources.ResourceValueMap
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType
import com.google.common.collect.Table

/**
 * Resolves a [ResourceRepository] for a [FolderConfiguration] into the namespace→type→values
 * map shape `ResourceResolver.create` consumes.
 *
 * `ResourceRepositoryUtil` is a Kotlin file facade (`xi=48` metadata) that kotlinc 2.x cannot
 * compile against directly with the bundled jar — call via reflection (see SPIKE_NOTES
 * "Framework resources wiring"). Used for both the framework repo and per-dir user repos.
 */
object ConfiguredResources {
    private val getConfiguredMethod: java.lang.reflect.Method by lazy {
        val util = Class.forName("com.android.ide.common.resources.ResourceRepositoryUtil")
        util.declaredMethods.first {
            it.name == "getConfiguredResources" && it.parameterCount == 2 &&
                Table::class.java.isAssignableFrom(it.returnType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun of(
        repo: ResourceRepository,
        folderConfig: FolderConfiguration,
    ): Map<ResourceNamespace, Map<ResourceType, ResourceValueMap>> {
        val table = getConfiguredMethod.invoke(null, repo, folderConfig)
            as Table<ResourceNamespace, ResourceType, ResourceValueMap>
        return table.rowMap()
    }
}
