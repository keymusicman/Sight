package io.github.keymusicman.sight.worker

import io.github.keymusicman.sight.ipc.RenderRequest
import io.github.keymusicman.sight.ipc.RenderResponse
import io.github.keymusicman.sight.ipc.WorkerInit
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Worker main entry point. Protocol:
 *
 *  1. Parent writes one [WorkerInit] JSON object on a single line of stdin.
 *  2. Worker initializes Layoutlib, prints `worker: ready (pid=…)` on stderr.
 *  3. Parent writes [RenderRequest] JSON objects, one per line, on stdin.
 *     Worker replies with a [RenderResponse] JSON object on stdout (one line),
 *     followed by a newline + flush.
 *  4. To stop, parent writes a [io.github.keymusicman.sight.ipc.ShutdownRequest]
 *     JSON (detected by `{"reason"` prefix) or simply closes stdin.
 *
 * Stdout is reserved for IPC; all logging goes to stderr.
 */
private val json = Json { ignoreUnknownKeys = true }

fun main() {
    val reader = BufferedReader(InputStreamReader(System.`in`))
    val out = System.out

    val initLine = reader.readLine() ?: error("no init line on stdin")
    val init = json.decodeFromString(WorkerInit.serializer(), initLine)

    val bridge = LayoutlibBootstrap(File(init.androidStudioRoot), init.targetApiLevel).init()
    val userCl = newUserClassLoader(init.userClasspath, Thread.currentThread().contextClassLoader)
    val renderer = WorkerRenderer(
        bridge = bridge,
        userClassLoader = userCl,
        androidStudioRoot = File(init.androidStudioRoot),
        userResDirs = init.userResDirs,
        rJarPaths = init.rJarPaths,
    )

    System.err.println("worker: ready (pid=${ProcessHandle.current().pid()})")

    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        if (line.startsWith("{\"reason\"")) break // ShutdownRequest sentinel
        val req = try {
            json.decodeFromString(RenderRequest.serializer(), line)
        } catch (e: Throwable) {
            System.err.println("worker: bad request line: ${e.message}")
            continue
        }
        val resp = renderer.render(req)
        out.println(json.encodeToString(RenderResponse.serializer(), resp))
        out.flush()
    }
    runCatching { bridge.dispose() }
}
