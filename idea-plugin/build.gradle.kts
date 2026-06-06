import java.util.Properties

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

group = "com.keymusicman.appflowerplugin"
version = "1.0.70-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":graph-renderer"))
    implementation(project(":graph-ui"))
    implementation(project(":ipc"))
    implementation(libs.kotlinx.serialization)
    compileOnly(compose.desktop.currentOs)

    intellijPlatform {
        androidStudio("2025.1.4.8")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("org.jetbrains.android")
    }

    testImplementation(libs.kotlin.testJunit)

    implementation("io.opentelemetry:opentelemetry-sdk:1.43.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "AI-251"
        }
        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

kotlin {
    jvmToolchain(21)
}

val generateOtelConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/otel")
    val localPropsFile = file("local.properties")
    inputs.file(localPropsFile).optional(true)
    outputs.dir(outDir)
    doLast {
        val props = Properties().also { p ->
            localPropsFile.takeIf { it.exists() }?.inputStream()?.use(p::load)
        }
        val otelEndpoint = props["OTEL_GC_ENDPOINT"] as String?
        val otelToken    = props["OTEL_GC_TOKEN"] as String?

        val dir = outDir.get().asFile
        dir.mkdirs()
        dir.resolve("OtelConfig.kt").writeText(
            """
            package com.keymusicman.appflowerplugin.appflowerplugin
            internal object OtelConfig {
                const val OTEL_ENABLED     = ${otelEndpoint != null}
                const val OTLP_ENDPOINT    = "${otelEndpoint ?: ""}"
                const val OTLP_AUTH_HEADER = "${otelToken?.trim('"')?.substringAfter("=") ?: ""}"
            }
            """.trimIndent()
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(generateOtelConfig)

tasks {
    val copyWorkerFatJar by registering(Copy::class) {
        dependsOn(":render-worker:shadowJar")
        from(project(":render-worker").layout.buildDirectory.file("libs/render-worker-all.jar"))
        into(prepareSandbox.flatMap { it.pluginDirectory.dir("lib") })
        // The Sync-based prepareSandbox would wipe the worker JAR if we ran beforehand.
        mustRunAfter(prepareSandbox)
    }

    prepareSandbox {
        finalizedBy(copyWorkerFatJar)
    }

    prepareJarSearchableOptions {
        mustRunAfter(copyWorkerFatJar)
    }

    buildPlugin {
        dependsOn(copyWorkerFatJar)
        archiveFileName.set("AppFlower.zip")
    }
}
