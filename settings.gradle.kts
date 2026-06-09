rootProject.name = "Sight"

// Inject local.properties into Gradle project properties so providers.gradleProperty() resolves them
file("local.properties").takeIf { it.exists() }?.also { f ->
    val props = java.util.Properties()
    f.inputStream().use(props::load)
    props.forEach { key, value ->
        System.setProperty("org.gradle.project.${key}", value.toString())
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
