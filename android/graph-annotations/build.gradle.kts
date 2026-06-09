import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    alias(libs.plugins.mavenPublish)
}

group = "io.github.keymusicman"
version = "0.1.0"

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.github.keymusicman", "sight-annotations", "0.1.0")
    pom {
        name = "Sight Annotations"
        description = "Annotations for Sight — Android navigation graph visualization"
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
