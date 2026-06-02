package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.diagnostic.Logger
import com.keymusicman.appflowerplugin.ipc.Outcome
import com.keymusicman.appflowerplugin.ipc.RenderRequest
import com.keymusicman.appflowerplugin.ipc.RenderResponse
import com.keymusicman.appflowerplugin.ipc.WorkerInit
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one running worker JVM.
 *
 * Lifecycle: construct → [start] → [render] repeatedly → [close].
 *
 * Thread-safety: [render] / [send] may be called concurrently from multiple plugin threads;
 * writes to the worker's stdin are serialized via a monitor on [stdin]. The reader thread
 * is owned by the client and demultiplexes responses back to callers via [pending].
 *
 * On worker crash, any still-pending [render] calls are completed with a FAIL [RenderResponse]
 * so callers never block forever.
 */
class SubprocessRendererClient(
    private val init: WorkerInit,
    private val workerClasspath: List<String>,
    private val jvmArgs: List<String> = DEFAULT_JVM_ARGS,
) : AutoCloseable {
    private val log = Logger.getInstance(SubprocessRendererClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableResponse>()

    private lateinit var process: Process
    private lateinit var stdin: BufferedWriter
    private lateinit var stdout: BufferedReader
    private lateinit var readerThread: Thread
    @Volatile private var closed = false

    fun start() {
        val javaBin = resolveJavaBinary()
        val cmd = buildList {
            add(javaBin)
            addAll(jvmArgs)
            add("-cp")
            add(workerClasspath.joinToString(File.pathSeparator))
            add(WORKER_MAIN_CLASS)
        }
        log.info("subprocess: spawning worker — java=$javaBin classpathEntries=${workerClasspath.size}")
        if (log.isDebugEnabled) log.debug("subprocess: full command ${cmd.joinToString(" ")}")
        process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()
        stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
        stdout = BufferedReader(InputStreamReader(process.inputStream))

        // Forward worker stderr to IDE log. Daemon — we don't want it pinning the JVM alive.
        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { log.info("worker: $it") }
            } catch (_: IOException) {
                // pipe closed during shutdown — expected
            }
        }, "render-worker-stderr-${process.pid()}").apply { isDaemon = true; start() }

        // Reader thread: drains stdout, decodes responses, completes pending futures.
        readerThread = Thread({
            try {
                stdout.forEachLine { line ->
                    val resp = try {
                        json.decodeFromString(RenderResponse.serializer(), line)
                    } catch (e: Throwable) {
                        log.warn("subprocess: bad response line: $line", e)
                        return@forEachLine
                    }
                    pending.remove(resp.requestId)?.complete(resp)
                }
            } catch (_: IOException) {
                // pipe closed during shutdown — expected
            }
        }, "render-worker-stdout-${process.pid()}").apply { isDaemon = true; start() }

        // If the worker exits unexpectedly (crash / OOM / SIGKILL), drain pending requests
        // with FAIL responses so callers don't block on their CountDownLatch forever.
        process.onExit().thenAccept { p ->
            val exit = p.exitValue()
            val msg = "worker process exited (pid=${p.pid()}, exit=$exit)"
            if (!closed) log.warn("subprocess: $msg with ${pending.size} pending request(s)")
            failAllPending("WorkerExit", msg)
        }

        // Send WorkerInit immediately so the worker can start initializing Layoutlib.
        send(json.encodeToString(WorkerInit.serializer(), init))
    }

    fun nextRequestId(): String = "r${seq.incrementAndGet()}"

    /**
     * Sends [req] and waits up to [timeout] for a response. On timeout the request is
     * abandoned (removed from [pending]) and a synthetic FAIL response returned; the
     * underlying worker may still be processing — caller should usually [close] the
     * client after a timeout to recover from a hung worker.
     */
    fun render(req: RenderRequest, timeout: Duration): RenderResponse {
        val cr = CompletableResponse()
        pending[req.requestId] = cr
        try {
            send(json.encodeToString(RenderRequest.serializer(), req))
        } catch (e: Throwable) {
            pending.remove(req.requestId)
            return RenderResponse(
                requestId = req.requestId,
                outcome = Outcome.FAIL,
                durationMs = 0L,
                errorClass = e.javaClass.name,
                errorMessage = "failed to send request: ${e.message}",
            )
        }
        return cr.await(timeout) ?: run {
            pending.remove(req.requestId)
            log.warn("subprocess: request ${req.requestId} (${req.composableFqn}) timed out after $timeout")
            RenderResponse(
                requestId = req.requestId,
                outcome = Outcome.FAIL,
                durationMs = timeout.toMillis(),
                errorClass = "Timeout",
                errorMessage = "worker did not respond within $timeout",
            )
        }
    }

    private fun send(line: String) {
        synchronized(stdin) {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Best-effort graceful shutdown. If the writer is already broken (worker crashed,
        // stdin closed) the exception is fine — we'll just force-kill below.
        try {
            send(SHUTDOWN_LINE)
        } catch (_: Throwable) {
            // pipe likely already broken — skip waitFor optimization, force-kill below.
        }
        try {
            process.waitFor(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // interrupted — fall through to destroyForcibly
        }
        if (process.isAlive) {
            log.info("subprocess: worker did not exit within ${SHUTDOWN_GRACE_SECONDS}s — destroyForcibly")
            process.destroyForcibly()
        }
        failAllPending("WorkerClosed", "client was closed before response arrived")
    }

    private fun failAllPending(errorClass: String, message: String) {
        val ids = pending.keys.toList()
        ids.forEach { id ->
            pending.remove(id)?.complete(
                RenderResponse(
                    requestId = id,
                    outcome = Outcome.FAIL,
                    durationMs = 0L,
                    errorClass = errorClass,
                    errorMessage = message,
                )
            )
        }
    }

    private fun resolveJavaBinary(): String {
        val asRoot = WorkerClasspathAssembler.androidStudioRoot()
        // macOS .app layout: <root>/Contents/jbr/Contents/Home/bin/java
        val macJbr = File(asRoot, "Contents/jbr/Contents/Home/bin/java")
        if (macJbr.isFile) return macJbr.absolutePath
        // Linux/Windows: jbr is at the top of the install directory.
        val nixJbr = File(asRoot, "jbr/bin/java")
        if (nixJbr.isFile) return nixJbr.absolutePath
        val winJbr = File(asRoot, "jbr/bin/java.exe")
        if (winJbr.isFile) return winJbr.absolutePath
        val fallback = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        log.warn("subprocess: bundled JBR not found under $asRoot — falling back to $fallback")
        return fallback
    }

    private class CompletableResponse {
        private val latch = CountDownLatch(1)
        @Volatile private var value: RenderResponse? = null
        fun complete(r: RenderResponse) {
            if (value == null) {
                value = r
                latch.countDown()
            }
        }
        fun await(d: Duration): RenderResponse? =
            if (latch.await(d.toMillis(), TimeUnit.MILLISECONDS)) value else null
    }

    companion object {
        /** Matches the Main-Class manifest in `render-worker/build.gradle.kts`. */
        private const val WORKER_MAIN_CLASS =
            "com.keymusicman.appflowerplugin.renderworker.worker.RenderWorkerMainKt"

        /** Matches the heuristic shutdown sentinel detected in `RenderWorkerMain.kt`. */
        private const val SHUTDOWN_LINE = """{"reason":"close"}"""

        private const val SHUTDOWN_GRACE_SECONDS = 5L

        /**
         * Default JVM args for the worker process:
         *   - `-Xmx2g`: per SPIKE_NOTES, real renders fit comfortably.
         *   - `-Dfile.encoding=UTF-8`: stdin/stdout IPC is JSON; lock down encoding.
         *   - `--add-opens` flags: Layoutlib reflects on JDK internals (matches the spike's
         *     run.sh). Without these, the worker hits `InaccessibleObjectException` on JDK 17+.
         */
        val DEFAULT_JVM_ARGS: List<String> = listOf(
            // Keep stack traces on repeated implicit exceptions (NPE/AIOOBE) so a residual
            // resource-resolution failure shows its real frame instead of a traceless NPE.
            "-XX:-OmitStackTraceInFastThrow",
            "-Xmx2g",
            "-Dfile.encoding=UTF-8",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.font=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.java2d=ALL-UNNAMED",
        )
    }
}
