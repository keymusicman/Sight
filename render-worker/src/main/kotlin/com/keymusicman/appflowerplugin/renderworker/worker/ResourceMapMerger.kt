package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.resources.ResourceValueMap
import com.android.resources.ResourceType

/**
 * Combines framework (ANDROID) resources with user (RES_AUTO) resources into the map
 * `ResourceResolver.create` consumes, and makes resolution *total* over the declared user
 * resources: any reference declared in R.jar but absent from the loaded repo gets a typed
 * placeholder (fail-soft), so a single missing resource degrades to a blank instead of an NPE.
 */
object ResourceMapMerger {
    private fun placeholderValue(type: ResourceType): String = when (type) {
        ResourceType.DRAWABLE, ResourceType.MIPMAP, ResourceType.COLOR -> "#00000000"
        ResourceType.DIMEN -> "0dp"
        else -> "" // string and everything else
    }

    fun merge(
        framework: Map<ResourceNamespace, Map<ResourceType, ResourceValueMap>>,
        user: Map<ResourceType, ResourceValueMap>,
        declaredRefs: Set<ResourceReference>,
        onPlaceholder: (ResourceReference) -> Unit = {},
    ): Map<ResourceNamespace, Map<ResourceType, ResourceValueMap>> {
        // Copy user maps so placeholder injection doesn't mutate the caller's maps.
        val userByType = LinkedHashMap<ResourceType, ResourceValueMap>()
        for ((type, map) in user) {
            val copy = ResourceValueMap.create()
            for (v in map.values()) copy.put(v)
            userByType[type] = copy
        }
        for (ref in declaredRefs) {
            if (ref.namespace != ResourceNamespace.RES_AUTO) continue
            val map = userByType.getOrPut(ref.resourceType) { ResourceValueMap.create() }
            if (!map.containsKey(ref.name)) {
                map.put(
                    ResourceValueImpl(
                        ResourceNamespace.RES_AUTO,
                        ref.resourceType,
                        ref.name,
                        placeholderValue(ref.resourceType),
                    ),
                )
                onPlaceholder(ref)
            }
        }
        val combined = LinkedHashMap<ResourceNamespace, Map<ResourceType, ResourceValueMap>>()
        combined.putAll(framework)
        combined[ResourceNamespace.RES_AUTO] = userByType
        return combined
    }
}
