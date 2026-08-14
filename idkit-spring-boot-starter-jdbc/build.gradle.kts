plugins {
    kotlin("jvm")
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

group = rootProject.group
version = rootProject.version

repositories { mavenCentral() }

dependencies {
    api(project(":idkit-spring-boot-autoconfigure"))
    api(project(":idkit-jdbc"))
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.test { useJUnitPlatform() }

mavenPublishing {
    signAllPublications()
    publishToMavenCentral()
    coordinates("io.github.dornol", "idkit-spring-boot-starter-jdbc", "$version")
    pom {
        name = "idkit-spring-boot-starter-jdbc"
        description = "Spring Boot starter for idkit JDBC worker identity leases"
        url = "https://github.com/dornol/idkit/"
        licenses { license { name = "MIT"; url = "https://github.com/dornol/idkit/blob/main/LICENSE" } }
        developers { developer { id = "dornol"; name = "dhkim"; email = "dhkim@dornol.dev" } }
        scm {
            url = "https://github.com/dornol/idkit/"
            connection = "scm:git:git://github.com/dornol/idkit.git"
            developerConnection = "scm:git:ssh://git@github.com/dornol/idkit.git"
        }
    }
}
