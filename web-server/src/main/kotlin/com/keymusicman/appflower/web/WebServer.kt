package com.keymusicman.appflower.web

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.core.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val json = Json { ignoreUnknownKeys = true }
    val storage = GraphStorageService()

    install(ContentNegotiation) {
        json(json)
    }

    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        post("/api/upload-graph") {
            val tempDir = Files.createTempDirectory("appflower-upload-")
            var graphName: String? = null
            var archiveSeen = false
            var extractionSummary: ZipExtractionSummary? = null

            try {
                val multipart = call.receiveMultipart()
                runCatching {
                    multipart.forEachPart { part ->
                        if (part is PartData.FormItem) {
                            if (part.name == "graphName") {
                                graphName = part.value
                            }
                        } else if (part is PartData.FileItem) {
                            if (part.name == "archive") {
                                if (archiveSeen) {
                                    throw IllegalArgumentException("Only one archive file is allowed")
                                }
                                archiveSeen = true
                                val tempZip = Files.createTempFile(tempDir, "upload-", ".zip")
                                part.provider().use { input ->
                                    tempZip.toFile().outputStream().use { out ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val read = input.readAvailable(buffer, 0, buffer.size)
                                            if (read <= 0) {
                                                break
                                            }
                                            out.write(buffer, 0, read)
                                        }
                                    }
                                }
                                Files.newInputStream(tempZip).use { input ->
                                    extractionSummary = extractZipToDirectory(input, tempDir)
                                }
                                Files.deleteIfExists(tempZip)
                            }
                        }
                        part.dispose()
                    }
                }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid multipart upload", e.message ?: "unknown error")
                    )
                    return@post
                }

                if (!archiveSeen) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing ZIP archive field 'archive'"))
                    return@post
                }

                val providedGraphName = graphName?.trim().orEmpty()
                if (providedGraphName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing graphName form field"))
                    return@post
                }

                val summary = extractionSummary
                if (summary == null || !summary.hasRootGraphJson || !summary.hasScreenshotsFolder) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("ZIP must contain app-graph.json and screenshots/ at archive root")
                    )
                    return@post
                }

                val graphId = runCatching { storage.normalizeGraphId(providedGraphName) }.getOrElse { e ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid graphName", e.message ?: "invalid"))
                    return@post
                }

                if (storage.graphExists(graphId)) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("graphId '$graphId' already exists"))
                    return@post
                }

                val graphFile = tempDir.resolve("app-graph.json").toFile()
                val appGraph = runCatching { json.decodeFromString<AppGraph>(graphFile.readText()) }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid app-graph.json payload", e.message ?: "unknown")
                    )
                    return@post
                }
                if (appGraph.subgraphs.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("app-graph.json contains no subgraphs"))
                    return@post
                }

                val layout = runCatching {
                    buildLayoutGraph(
                        appGraph = appGraph,
                        projectPath = Path.of(tempDir.toString(), "screenshots").toString(),
                        scale = 0.5f,
                    )
                }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to build layout", e.message ?: "unknown")
                    )
                    return@post
                }

                val layoutJson = json.encodeToString(LayoutResponse.serializer(), layout.toResponse())
                val layoutFile = tempDir.resolve("layout.json").toFile()
                layoutFile.writeText(layoutJson)

                runCatching { storage.uploadDirectory(graphId, tempDir) }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to upload graph to storage", e.message ?: "unknown")
                    )
                    return@post
                }

                call.respond(
                    HttpStatusCode.Created,
                    UploadGraphResponse(graphId = graphId, uploadedFiles = summary.fileCount)
                )
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        get("/api/graphs") {
            val graphIds = runCatching { storage.listGraphIds() }.getOrElse { e ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to list graphs", e.message ?: "unknown")
                )
                return@get
            }
            call.respond(GraphListResponse(graphIds))
        }

        get("/api/layout/{graphId}") {
            val graphId = call.parameters["graphId"].orEmpty()
            if (!isValidGraphId(graphId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid graphId path parameter"))
                return@get
            }
            val existingLayoutJson = runCatching { storage.loadLayoutJson(graphId) }.getOrElse { e ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to read layout.json", e.message ?: "unknown")
                )
                return@get
            }
            if (existingLayoutJson != null) {
                call.respondText(existingLayoutJson, ContentType.Application.Json)
                return@get
            }

            val tempDir = Files.createTempDirectory("appflower-layout-backfill-")
            try {
                val downloaded = runCatching { storage.downloadGraphToDirectory(graphId, tempDir) }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to read graph files", e.message ?: "unknown")
                    )
                    return@get
                }
                if (!downloaded) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Graph '$graphId' not found"))
                    return@get
                }

                val graphFile = tempDir.resolve("app-graph.json").toFile()
                if (!graphFile.exists()) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Graph '$graphId' is missing app-graph.json in storage")
                    )
                    return@get
                }

                val appGraph = runCatching { json.decodeFromString<AppGraph>(graphFile.readText()) }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Stored app-graph.json is invalid", e.message ?: "unknown")
                    )
                    return@get
                }
                val layout = runCatching {
                    buildLayoutGraph(
                        appGraph = appGraph,
                        projectPath = Path.of(tempDir.toString(), "screenshots").toString(),
                        scale = 0.5f,
                    )
                }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to build layout", e.message ?: "unknown")
                    )
                    return@get
                }

                val generatedLayoutJson = json.encodeToString(LayoutResponse.serializer(), layout.toResponse())
                runCatching { storage.saveLayoutJson(graphId, generatedLayoutJson) }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to store generated layout.json", e.message ?: "unknown")
                    )
                    return@get
                }
                call.respondText(generatedLayoutJson, ContentType.Application.Json)
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        delete("/api/graph/{graphId}") {
            val graphId = call.parameters["graphId"].orEmpty()
            if (!isValidGraphId(graphId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid graphId path parameter"))
                return@delete
            }
            val deleted = runCatching { storage.deleteGraph(graphId) }.getOrElse { e ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to delete graph", e.message ?: "unknown")
                )
                return@delete
            }
            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Graph '$graphId' not found"))
                return@delete
            }
            call.respond(mapOf("status" to "deleted", "graphId" to graphId))
        }

        staticResources("/", "static")
    }
}

@Serializable
private data class UploadGraphResponse(
    val graphId: String,
    val uploadedFiles: Int
)

@Serializable
private data class GraphListResponse(
    val graphIds: List<String>
)

@Serializable
private data class ErrorResponse(
    val error: String,
    val details: String? = null
)

@Serializable
private data class LayoutResponse(
    val nodes: List<LayoutNodeResponse>,
    val edges: List<LayoutEdgeResponse>
)

@Serializable
private data class LayoutNodeResponse(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val imagePaths: List<String>
)

@Serializable
private data class LayoutEdgeResponse(
    val from: String,
    val to: String,
    val points: List<PointResponse>
)

@Serializable
private data class PointResponse(val x: Float, val y: Float)

private data class ZipExtractionSummary(
    val fileCount: Int,
    val hasRootGraphJson: Boolean,
    val hasScreenshotsFolder: Boolean
)

private fun extractZipToDirectory(input: InputStream, destinationDir: Path): ZipExtractionSummary {
    val root = destinationDir.normalize()
    var fileCount = 0
    var hasRootGraphJson = false
    var hasScreenshotsFolder = false

    ZipInputStream(BufferedInputStream(input)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val normalizedName = entry.name.replace('\\', '/').trimStart('/')
            if (normalizedName.isNotBlank()) {
                val target = root.resolve(normalizedName).normalize()
                require(target.startsWith(root)) { "ZIP contains illegal path: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    if (normalizedName == "screenshots" || normalizedName.startsWith("screenshots/")) {
                        hasScreenshotsFolder = true
                    }
                } else {
                    target.parent?.let { Files.createDirectories(it) }
                    target.toFile().outputStream().use { out -> zip.copyTo(out) }
                    fileCount++
                    if (normalizedName == "app-graph.json") {
                        hasRootGraphJson = true
                    }
                    if (normalizedName.startsWith("screenshots/")) {
                        hasScreenshotsFolder = true
                    }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    return ZipExtractionSummary(
        fileCount = fileCount,
        hasRootGraphJson = hasRootGraphJson,
        hasScreenshotsFolder = hasScreenshotsFolder
    )
}

private fun isValidGraphId(graphId: String): Boolean = graphId.matches(Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$"))

private fun LayoutGraph.toResponse(): LayoutResponse =
    LayoutResponse(
        nodes = nodes.values
            .sortedWith(compareBy({ it.x }, { it.y }, { it.id }))
            .map { node ->
                LayoutNodeResponse(
                    id = node.id,
                    x = node.x,
                    y = node.y,
                    width = node.width,
                    height = node.height,
                    imagePaths = node.imagePaths
                )
            },
        edges = edges.map { edge ->
            LayoutEdgeResponse(
                from = edge.from,
                to = edge.to,
                points = edge.points.map { p -> PointResponse(p.x, p.y) }
            )
        }
    )
