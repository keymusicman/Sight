package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ILayoutLog

/**
 * Minimal [ILayoutLog] that forwards everything to stderr so it stays out of the
 * IPC stdout stream (which is reserved for JSON responses to the parent process).
 */
internal class StdErrLayoutLog : ILayoutLog {
    override fun warning(tag: String?, message: String?, viewCookie: Any?, data: Any?) {
        System.err.println("[layoutlib WARN][$tag] $message")
    }
    override fun fidelityWarning(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) {
        System.err.println("[layoutlib FIDELITY][$tag] $message")
        throwable?.printStackTrace(System.err)
    }
    override fun error(tag: String?, message: String?, viewCookie: Any?, data: Any?) {
        System.err.println("[layoutlib ERROR][$tag] $message")
    }
    override fun error(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) {
        System.err.println("[layoutlib ERROR][$tag] $message")
        throwable?.printStackTrace(System.err)
    }
    override fun logAndroidFramework(priority: Int, tag: String?, message: String?) {
        System.err.println("[android $priority][$tag] $message")
    }
}
