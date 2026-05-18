package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.tools.environment.Logger

/**
 * SPI override for [com.android.tools.environment.Logger.LoggerProvider].
 *
 * `android.jar` (bundled with Android Studio's android plugin — NOT the SDK android.jar)
 * registers `com.android.tools.idea.log.IJLoggerProvider` and
 * `com.android.tools.environment.log.StubLoggerProvider` via SPI.
 * `Logger.Companion.getInstance(...)` picks by `maxByOrNull { priority }`.
 * `IJLoggerProvider` would win by default and instantiate `IJLogger`, which
 * constructor-requires `com.intellij.openapi.diagnostic.Logger` — pulling the IntelliJ
 * Platform we're avoiding in the standalone worker.
 *
 * We register this provider at `Int.MAX_VALUE` to ensure ours is selected.
 *
 * Registered via `META-INF/services/com.android.tools.environment.Logger$LoggerProvider`.
 */
class WorkerLoggerProvider : Logger.LoggerProvider {
    override val priority: Int = Int.MAX_VALUE
    override fun createLogger(name: String): Logger = StdoutEnvLogger(name)
}

private class StdoutEnvLogger(private val cat: String) : Logger {
    override fun warn(message: String, throwable: Throwable?) {
        System.err.println("[env $cat WARN] $message")
        throwable?.printStackTrace(System.err)
    }
    override fun error(message: String, throwable: Throwable?) {
        System.err.println("[env $cat ERROR] $message")
        throwable?.printStackTrace(System.err)
    }
    override fun info(message: String, throwable: Throwable?) {
        System.err.println("[env $cat INFO] $message")
    }
    override fun debug(message: String, throwable: Throwable?) {
        // drop
    }
    override val isDebugEnabled: Boolean = false
}
