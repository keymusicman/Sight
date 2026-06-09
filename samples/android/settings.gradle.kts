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
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
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

// Expose :graph-annotations and :graph-processor from the Sight root build
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.keymusicman:sight-annotations")).using(project(":graph-annotations"))
        substitute(module("io.github.keymusicman:sight-processor")).using(project(":graph-processor"))
    }
}

rootProject.name = "sample-android"
include(":app")
