package io.github.keymusicman.sight.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class SightPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.plugins.apply("com.google.devtools.ksp")

        target.dependencies.add("compileOnly", "io.github.keymusicman:sight-annotations:$VERSION")
        target.dependencies.add("ksp", "io.github.keymusicman:sight-processor:$VERSION")

        target.extensions.configure(KspExtension::class.java) { ksp ->
            // Module's own directory — NOT rootDir. The processor writes the fragment to
            // "$projectRoot/build/graph/" and emits screen `location` paths relative to it,
            // and the IDE plugin reads each module's own build/graph/ and resolves `location`
            // against the module dir. Using rootDir here makes sibling modules overwrite one
            // shared fragment and breaks both fragment discovery and source navigation.
            ksp.arg("projectRoot", target.projectDir.absolutePath)
            ksp.arg("moduleName", target.path)
        }

        target.tasks.register("sight") { task ->
            task.group = "sight"
            task.description = "Generates app-graph-fragment.json via KSP"
            task.dependsOn("compileDebugKotlin")
        }
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
