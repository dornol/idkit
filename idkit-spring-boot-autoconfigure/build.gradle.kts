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
    api(project(":"))
    compileOnly(project(":idkit-jdbc"))
    compileOnly(project(":idkit-redis"))
    compileOnly(libs.springBootAutoconfigure)
    compileOnly(libs.springBootActuator)
    compileOnly(libs.springBootConfigurationProcessor)
    compileOnly(libs.springContext)
    compileOnly(libs.micrometerCore)
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor:${libs.versions.springBoot.get()}")
    testImplementation(libs.springBootAutoconfigure)
    testImplementation(libs.springBootActuator)
    testImplementation("org.springframework.boot:spring-boot-test:${libs.versions.springBoot.get()}")
    testImplementation(libs.springContext)
    testImplementation(project(":idkit-jdbc"))
    testImplementation(project(":idkit-redis"))
    testImplementation(libs.testcontainers)
    testImplementation(libs.postgresql)
    testImplementation(libs.lettuce)
    testImplementation(libs.micrometerCore)
    testImplementation(libs.junitSpring)
    testRuntimeOnly(libs.junitSpringLauncher)
    testImplementation("org.assertj:assertj-core:3.27.3")
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.test { useJUnitPlatform() }

mavenPublishing {
    signAllPublications()
    publishToMavenCentral(automaticRelease = true)
    coordinates("io.github.dornol", "idkit-spring-boot-autoconfigure", "$version")
    pom {
        name = "idkit-spring-boot-autoconfigure"
        description = "Spring Boot auto-configuration for idkit worker identity leases"
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
