plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

group = "com.keymusicman.appflower"
version = "1.0.0"

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":graph-renderer"))
            // Compose and lifecycle are provided at runtime by the consumer:
            //   - composeApp via compose.desktop.currentOs
            //   - idea-plugin via composeUI() bundled with IntelliJ
            compileOnly(compose.desktop.currentOs)
            compileOnly(libs.androidx.lifecycle.viewmodelCompose)
            compileOnly(libs.androidx.lifecycle.runtimeCompose)
            // These have no Android/Compose transitive deps; safe to bundle.
            implementation(libs.kotlinx.serialization)
            implementation(libs.kotlinx.coroutinesSwing)

            implementation(libs.logging.kermit)
        }
    }
}
