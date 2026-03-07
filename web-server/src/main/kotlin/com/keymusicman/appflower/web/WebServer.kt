package com.keymusicman.appflower.web

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.http.content.defaultResource
import io.ktor.server.http.content.resource
import io.ktor.server.http.content.staticResources
import io.ktor.utils.io.core.readBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipInputStream

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
        })
    }
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
    }

    val json = Json { ignoreUnknownKeys = true }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        post("/api/layout") {
            val payload = call.receiveText()
            val appGraph = runCatching { json.decodeFromString<AppGraph>(payload) }.getOrElse { e ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid app-graph.json payload", "details" to (e.message ?: "unknown"))
                )
                return@post
            }

            val layout = buildLayoutGraph(appGraph, projectPath = null, scale = 0.5f)
            call.respond(layout.toResponse())
        }

        post("/api/layout-zip") {
            val multipart = call.receiveMultipart()
            var archiveBytes: ByteArray? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem && part.name == "archive") {
                    archiveBytes = part.provider().readBytes()
                }
                part.dispose()
            }

            if (archiveBytes == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing ZIP archive in multipart field 'archive'")
                )
                return@post
            }

            val tempDir = Files.createTempDirectory("appflower-zip-").toFile()
            try {
                unzipArchiveSafely(archiveBytes!!, tempDir)

                val graphFile = File(tempDir, "app-graph.json")
                if (!graphFile.exists()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "ZIP must contain app-graph.json at archive root")
                    )
                    return@post
                }

                val appGraph = runCatching {
                    json.decodeFromString<AppGraph>(graphFile.readText())
                }.getOrElse { e ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid app-graph.json in ZIP", "details" to (e.message ?: "unknown"))
                    )
                    return@post
                }

                val layout = buildLayoutGraph(appGraph, projectPath = tempDir.absolutePath, scale = 0.5f)
                val inlineImages = buildInlineImageDataByNode(layout)
                call.respond(layout.toResponse(inlineImages))
            } finally {
                tempDir.deleteRecursively()
            }
        }

        staticResources("/", "static") {
            defaultResource("index.html", "static")
            resource("index.html", "static/index.html")
        }
    }
}

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
    val imagePaths: List<String>,
    val imageDataUrl: String? = null
)

@Serializable
private data class LayoutEdgeResponse(
    val from: String,
    val to: String,
    val points: List<PointResponse>
)

@Serializable
private data class PointResponse(val x: Float, val y: Float)

private fun LayoutGraph.toResponse(imageDataByNodeId: Map<String, String?> = emptyMap()): LayoutResponse =
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
                    imagePaths = node.imagePaths,
                    imageDataUrl = imageDataByNodeId[node.id]
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

private fun unzipArchiveSafely(archiveBytes: ByteArray, destinationDir: File) {
    val root = destinationDir.toPath().normalize()
    ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val resolved = root.resolve(entry.name).normalize()
            if (!resolved.startsWith(root)) {
                throw IllegalArgumentException("ZIP contains illegal path: ${entry.name}")
            }
            if (entry.isDirectory) {
                resolved.toFile().mkdirs()
            } else {
                resolved.parent?.toFile()?.mkdirs()
                resolved.toFile().outputStream().use { out -> zip.copyTo(out) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
}

private fun buildInlineImageDataByNode(layout: LayoutGraph): Map<String, String?> =
    layout.nodes.values.associate { node ->
        val imagePath = node.imagePaths.firstOrNull()
        val imageData = imagePath?.let { path ->
            val file = File(path)
            if (!file.exists()) {
                null
            } else {
                val mime = when (file.extension.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    else -> "image/png"
                }
                val encoded = Base64.getEncoder().encodeToString(file.readBytes())
                "data:$mime;base64,$encoded"
            }
        }
        node.id to imageData
    }
