package io.github.keymusicman.sight.plugin

/**
 * Outcome of the per-render staleness check [SubprocessRenderer] runs against its pooled worker.
 */
sealed interface StalenessDecision {
    /** Nothing changed on disk — reuse the worker as-is. */
    object Fresh : StalenessDecision

    /** Output was touched but the bytecode is identical (e.g. a no-op rebuild). Keep the worker;
     *  store [newCheapStamp] so the next render's cheap gate matches and we don't re-hash. */
    data class NoOpRefresh(val newCheapStamp: Long) : StalenessDecision

    /** Bytecode changed — discard the worker and spawn a fresh one. */
    object Recycle : StalenessDecision
}

/**
 * Two-tier staleness rule. The cheap stat-only stamp is the gate; the (expensive) content hash is
 * consulted only when that gate moved — so [currentContentHash] is a lazy supplier and is never
 * invoked on the unchanged fast path.
 */
fun decideStaleness(
    storedCheapStamp: Long,
    storedContentHash: String,
    currentCheapStamp: Long,
    currentContentHash: () -> String,
): StalenessDecision {
    if (currentCheapStamp == storedCheapStamp) return StalenessDecision.Fresh
    return if (currentContentHash() == storedContentHash) {
        StalenessDecision.NoOpRefresh(currentCheapStamp)
    } else {
        StalenessDecision.Recycle
    }
}
