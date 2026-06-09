package com.keymusicman.sight.model

/**
 * Test implementation of ImageDimensionResolver that returns constant dimensions.
 * Used for deterministic testing where image dimensions are known.
 */
class ConstantImageDimensionResolver(
    private val width: Int = 540,
    private val height: Int = 360
) : ImageDimensionResolver {
    override suspend fun resolveDimension(imagePath: String): Pair<Int, Int>? = width to height
}

/**
 * Test helper for building layout graphs with predictable dimensions and gaps.
 */
object TestLayoutBuilder {
    suspend fun buildWithConstants(
        appGraph: AppGraph,
        width: Int = 540,
        height: Int = 360,
        scale: Float = 0.33f,
        gaps: LayoutGaps = LayoutGaps()
    ) = LayoutGraphBuilder.build(
        appGraph = appGraph,
        projectPath = null,
        scale = scale,
        imageResolver = ConstantImageDimensionResolver(width, height),
        gaps = gaps
    )
}
