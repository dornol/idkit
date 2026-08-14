plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    api(project(":"))
    api(libs.lettuce)
    compileOnly(libs.micrometerCore)
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitLauncher)
    testImplementation(libs.micrometerCore)
    testImplementation(libs.testcontainers)
}

kotlin {
    jvmToolchain(11)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.test {
    useJUnitPlatform()
}

signing {
    sign(publishing.publications)
}

mavenPublishing {
    signAllPublications()
    publishToMavenCentral()
    coordinates("io.github.dornol", "idkit-redis", "$version")

    pom {
        name = "idkit-redis"
        description = "Redis worker identity leases for idkit"
        url = "https://github.com/dornol/idkit/"
        inceptionYear = "2025"
        licenses {
            license {
                name = "MIT"
                url = "https://github.com/dornol/idkit/blob/main/LICENSE"
            }
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/dornol/idkit/issues"
        }
        developers {
            developer {
                id = "dornol"
                name = "dhkim"
                email = "dhkim@dornol.dev"
                url = "https://github.com/dornol/"
            }
        }
        scm {
            url = "https://github.com/dornol/idkit/"
            connection = "scm:git:git://github.com/dornol/idkit.git"
            developerConnection = "scm:git:ssh://git@github.com/dornol/idkit.git"
        }
    }
}
