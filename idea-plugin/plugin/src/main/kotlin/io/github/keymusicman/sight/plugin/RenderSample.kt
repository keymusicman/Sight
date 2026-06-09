package io.github.keymusicman.sight.plugin

data class GcStats(val collectionCount: Long, val collectionTimeMs: Long) {
    operator fun minus(other: GcStats) = GcStats(
        collectionCount  = collectionCount  - other.collectionCount,
        collectionTimeMs = collectionTimeMs - other.collectionTimeMs,
    )
}

enum class RenderOutcome { SUCCESS, FAIL, SKIP }

data class RenderSample(
    val inflateMs   : Long,
    val callbacksMs : Long,
    val renderMs    : Long,
    val writeMs     : Long,
    val totalMs     : Long,
    val format      : OutputFormat,
    val outcome     : RenderOutcome,
    val heapBefore  : Long,
    val heapAfter   : Long,
    val gcDelta     : GcStats,
) {
    val heapDelta: Long get() = heapAfter - heapBefore
}
