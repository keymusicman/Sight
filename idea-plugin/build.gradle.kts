plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

group = "com.keymusicman.appflowerplugin"
version = "1.0-SNAPSHOT"

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
    compileOnly(compose.desktop.currentOs)

    intellijPlatform {
        androidStudio("2025.1.4.8")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("org.jetbrains.android")
    }

    testImplementation(libs.kotlin.testJunit)
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
