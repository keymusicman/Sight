package com.keymusicman.sight.web

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.Storage.BlobListOption
import com.google.cloud.storage.StorageOptions
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

class GraphStorageService(
    private val storage: Storage = StorageOptions.getDefaultInstance().service,
    private val bucketName: String = System.getenv("GCS_BUCKET") ?: "your-gcs-bucket",
    private val rootPrefix: String = "app-graph",
    private val publicBaseUrl: String = (
        System.getenv("GRAPH_CDN_BASE_URL")?.trim()?.trimEnd('/')
            ?: "https://storage.googleapis.com/$bucketName/$rootPrefix"
        )
) {
    fun normalizeGraphId(graphName: String): String {
        val normalized = graphName
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        require(normalized.isNotBlank()) { "graphName must contain at least one alphanumeric character" }
        require(normalized.length <= 80) { "graphName resolves to graphId longer than 80 characters" }
        return normalized
    }

    fun graphExists(graphId: String): Boolean {
        val blobId = BlobId.of(bucketName, graphJsonObjectName(graphId))
        return storage.get(blobId) != null
    }

    fun listGraphIds(): List<String> {
        val seen = linkedSetOf<String>()
        val pages = storage.list(bucketName, BlobListOption.prefix("$rootPrefix/"))
        pages.iterateAll().forEach { blob ->
            val rest = blob.name.removePrefix("$rootPrefix/")
            val graphId = rest.substringBefore('/')
            if (graphId.isNotBlank()) {
                seen.add(graphId)
            }
        }
        return seen.toList().sorted()
    }

    fun loadAppGraphJson(graphId: String): String? {
        val blob = storage.get(BlobId.of(bucketName, graphJsonObjectName(graphId))) ?: return null
        return String(blob.getContent())
    }

    fun loadLayoutJson(graphId: String): String? {
        val blob = storage.get(BlobId.of(bucketName, layoutJsonObjectName(graphId))) ?: return null
        return String(blob.getContent())
    }

    fun saveLayoutJson(graphId: String, layoutJson: String) {
        val blobInfo = BlobInfo.newBuilder(bucketName, layoutJsonObjectName(graphId))
            .setContentType("application/json")
            .build()
        storage.create(blobInfo, layoutJson.toByteArray())
    }

    fun buildPublicAssetUrl(graphId: String, relativePath: String): String {
        val normalizedPath = relativePath.trim().trimStart('/').replace('\\', '/')
        return "$publicBaseUrl/$graphId/$normalizedPath"
    }

    fun uploadDirectory(graphId: String, sourceDir: Path) {
        Files.walk(sourceDir).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() }
                .forEach { file ->
                    val relative = sourceDir.relativize(file).invariantSeparatorsPathString
                    val objectName = "$rootPrefix/$graphId/$relative"
                    val blobInfo = BlobInfo.newBuilder(bucketName, objectName).build()
                    Files.newInputStream(file).use { input ->
                        val writer = storage.writer(blobInfo)
                        Channels.newOutputStream(writer).use { out -> input.copyTo(out) }
                    }
                }
        }
    }

    fun deleteGraph(graphId: String): Boolean {
        val blobs = storage.list(bucketName, BlobListOption.prefix("$rootPrefix/$graphId/")).iterateAll().toList()
        if (blobs.isEmpty()) {
            return false
        }
        blobs.forEach { blob -> storage.delete(blob.blobId) }
        return true
    }

    fun downloadGraphToDirectory(graphId: String, destinationDir: Path): Boolean {
        val prefix = "$rootPrefix/$graphId/"
        val blobs = storage.list(bucketName, BlobListOption.prefix(prefix)).iterateAll().toList()
        if (blobs.isEmpty()) {
            return false
        }
        blobs.forEach { blob ->
            val relative = blob.name.removePrefix(prefix)
            if (relative.isBlank() || blob.name.endsWith("/")) {
                return@forEach
            }
            val target = destinationDir.resolve(relative).normalize()
            target.parent?.let { Files.createDirectories(it) }
            target.toFile().outputStream().use { out -> blob.downloadTo(out) }
        }
        return true
    }

    private fun graphJsonObjectName(graphId: String): String = "$rootPrefix/$graphId/app-graph.json"

    private fun layoutJsonObjectName(graphId: String): String = "$rootPrefix/$graphId/layout.json"
}
