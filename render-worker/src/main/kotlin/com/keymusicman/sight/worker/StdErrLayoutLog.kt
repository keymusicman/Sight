package com.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ILayoutLog

/**
 * Minimal [ILayoutLog] that forwards everything to stderr so it stays out of the
 * IPC stdout stream (which is reserved for JSON responses to the parent process).
 *
 * It also watches for the "Sequence doesn't contain element" message that
 * `ComposeViewAdapter` logs when a `PreviewParameterProvider` index is past the end of the
 * provider. The in-process renderer ([com.keymusicman.sight.plugin.ComposableRenderer])
 * treats this message — even on an otherwise *successful* render — as the multi-state loop's
 * termination signal. Layoutlib, driven via raw `Bridge.createSession`, does not always fail the
 * render in that case (it can log the warning and still produce a duplicate frame), so the worker
 * would write an extra image per state and the graph showed ~2× the real number of states.
 * Recording the sentinel here lets [WorkerRenderer] mirror the in-process behavior and stop.
 */
internal class StdErrLayoutLog : ILayoutLog {

    @Volatile
    var sawProviderExhausted: Boolean = false
        private set

    /** Reset before each render so the flag reflects only the current request. */
    fun resetProviderExhausted() {
        sawProviderExhausted = false
    }

    private fun note(message: String?, throwable: Throwable? = null) {
        if (sawProviderExhausted) return
        if (message?.contains(PROVIDER_EXHAUSTED_MARKER) == true ||
            throwable?.message?.contains(PROVIDER_EXHAUSTED_MARKER) == true
        ) {
            sawProviderExhausted = true
        }
    }

    override fun warning(tag: String?, message: String?, viewCookie: Any?, data: Any?) {
        note(message)
        System.err.println("[layoutlib WARN][$tag] $message")
    }
    override fun fidelityWarning(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) {
        note(message, throwable)
        System.err.println("[layoutlib FIDELITY][$tag] $message")
        throwable?.printStackTrace(System.err)
    }
    override fun error(tag: String?, message: String?, viewCookie: Any?, data: Any?) {
        note(message)
        System.err.println("[layoutlib ERROR][$tag] $message")
    }
    override fun error(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) {
        note(message, throwable)
        System.err.println("[layoutlib ERROR][$tag] $message")
        throwable?.printStackTrace(System.err)
    }
    override fun logAndroidFramework(priority: Int, tag: String?, message: String?) {
        note(message)
        System.err.println("[android $priority][$tag] $message")
    }

    companion object {
        const val PROVIDER_EXHAUSTED_MARKER = "Sequence doesn't contain element"
    }
}
