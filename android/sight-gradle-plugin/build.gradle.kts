import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-gradle-plugin`
    kotlin("jvm")
    alias(libs.plugins.mavenPublish)
}

group = "io.github.keymusicman"
version = "0.1.0"

dependencies {
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        create("sight") {
            id = "io.github.keymusicman.sight"
            implementationClass = "io.github.keymusicman.sight.gradle.SightPlugin"
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (!project.hasProperty("skipSigning")) {
        signAllPublications()
    }
    coordinates("io.github.keymusicman", "sight-gradle-plugin", "0.1.0")
    pom {
        name = "Sight Gradle Plugin"
        description = "Gradle plugin for Sight — applies KSP, wires sight-annotations and sight-processor, and registers the exportGraph task"
        url = "https://github.com/keymusicman/Sight"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://opensource.org/licenses/Apache-2.0"
            }
        }
        developers {
            developer {
                id = "keymusicman"
                name = "Vasilii Maleev"
                email = "keymusicman@gmail.com"
            }
        }
        scm {
            url = "https://github.com/keymusicman/Sight"
            connection = "scm:git:git://github.com/keymusicman/Sight.git"
            developerConnection = "scm:git:ssh://git@github.com/keymusicman/Sight.git"
        }
    }
}
