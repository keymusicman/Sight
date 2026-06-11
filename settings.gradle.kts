rootProject.name = "Sight"

// Inject local.properties into each project as extra properties so findProperty()/hasProperty()
// (used by the signing plugin and vanniktech maven-publish) resolve them.
// NOTE: this must run via gradle.beforeProject, NOT by mutating system/startParameter properties in
// the settings body — Gradle snapshots all project-property sources before the settings script runs,
// so anything set here at settings-eval time never becomes a project property.
val localProps = java.util.Properties().apply {
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
gradle.beforeProject {
    localProps.forEach { key, value ->
        extensions.extraProperties.set(key.toString(), value.toString())
    }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":graph-annotations")
project(":graph-annotations").projectDir = file("android/graph-annotations")

include(":graph-processor")
project(":graph-processor").projectDir = file("android/graph-processor")

include(":sight-gradle-plugin")
project(":sight-gradle-plugin").projectDir = file("android/sight-gradle-plugin")
include(":graph-renderer")
project(":graph-renderer").projectDir = file("shared/graph-renderer")

include(":graph-ui")
project(":graph-ui").projectDir = file("shared/graph-ui")
include(":idea-plugin")
project(":idea-plugin").projectDir = file("idea-plugin/plugin")

include(":web-server")

include(":ipc")
project(":ipc").projectDir = file("idea-plugin/ipc")

include(":render-worker")
project(":render-worker").projectDir = file("idea-plugin/render-worker")
