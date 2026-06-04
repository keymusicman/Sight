package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.resources.ResourceValueMap
import com.android.resources.ResourceType
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Combines framework (ANDROID) resources with user (RES_AUTO) resources into the map
 * `ResourceResolver.create` consumes, and makes resolution *total* over the declared user
 * resources: any reference declared in R.jar but absent from the loaded repo gets a typed
 * placeholder (fail-soft), so a single missing resource degrades to a blank instead of an NPE.
 */
object ResourceMapMerger {
    /**
     * Absolute path to a 1×1 fully transparent PNG on disk, used as the placeholder *value* for
     * missing DRAWABLE/MIPMAP refs. layoutlib resolves a file-path drawable to a `BitmapDrawable`,
     * which is what Compose's `imageResource`/`painterResource` casts to. A `#00000000` COLOR
     * value would instead resolve to a `ColorDrawable`, whose cast to `BitmapDrawable` throws
     * `ClassCastException` and aborts the *entire* composition (root measures 0×0 → blank image).
     * With a real bitmap, a genuinely missing image degrades to a blank box and the rest renders.
     */
    private val transparentPngPath: String? by lazy {
        runCatching {
            val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) // pixels default to 0x0 = transparent
            val f = File.createTempFile("af-missing-drawable", ".png").apply { deleteOnExit() }
            ImageIO.write(img, "png", f)
            f.absolutePath
        }.getOrNull()
    }

    private fun placeholderValue(type: ResourceType): String = when (type) {
        ResourceType.DRAWABLE, ResourceType.MIPMAP -> transparentPngPath ?: "#00000000"
        ResourceType.COLOR -> "#00000000"
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
