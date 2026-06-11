import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    alias(libs.plugins.mavenPublish)
}

group = "io.github.keymusicman"
version = "0.0.1"

kotlin {
    jvmToolchain(17) // keep consumable on JDK 17 (Android/AGP baseline)
}

dependencies {
    implementation(libs.ksp.api)
    testImplementation(kotlin("test"))
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (!project.hasProperty("skipSigning")) {
        signAllPublications()
    }
    coordinates("io.github.keymusicman", "sight-processor", "0.0.1")
    pom {
        name = "Sight Processor"
        description = "KSP annotation processor for Sight — Android navigation graph visualization"
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
