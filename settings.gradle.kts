rootProject.name = "Sight"

// Inject local.properties into Gradle project properties so providers.gradleProperty() resolves them
file("local.properties").takeIf { it.exists() }?.also { f ->
    val props = java.util.Properties()
    f.inputStream().use(props::load)
    props.forEach { key, value ->
        gradle.startParameter.projectProperties[key.toString()] = value.toString()
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
include(":graph-processor")
include(":graph-renderer")
include(":graph-ui")
include(":idea-plugin")
include(":web-server")
include(":ipc")
include(":render-worker")
