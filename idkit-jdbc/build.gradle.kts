plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

group = rootProject.group
version = rootProject.version

repositories { mavenCentral() }

dependencies {
    api(project(":"))
    compileOnly(libs.micrometerCore)
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitLauncher)
    testImplementation(libs.micrometerCore)
    testImplementation(libs.testcontainers)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql)
    testImplementation(libs.mariadb)
    testImplementation(libs.mssql)
    testImplementation(libs.oracle)
}

kotlin { jvmToolchain(11) }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(11)) } }
tasks.test {
    useJUnitPlatform()
    exclude("**/*IntegrationTest.class")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs Docker-backed JDBC integration tests."
    group = "verification"
    useJUnitPlatform()
    include("**/*IntegrationTest.class")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.named("check") { dependsOn(integrationTest) }

tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
    executionData(layout.buildDirectory.file("jacoco/integrationTest.exec"))
}
tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
    dependsOn(integrationTest)
    executionData(layout.buildDirectory.file("jacoco/integrationTest.exec"))
}

signing { sign(publishing.publications) }
mavenPublishing {
    signAllPublications()
    publishToMavenCentral(automaticRelease = true)
    coordinates("io.github.dornol", "idkit-jdbc", "$version")
    pom {
        name = "idkit-jdbc"
        description = "JDBC worker identity leases for idkit"
        url = "https://github.com/dornol/idkit/"
        inceptionYear = "2025"
        licenses { license { name = "MIT"; url = "https://github.com/dornol/idkit/blob/main/LICENSE" } }
        issueManagement { system = "GitHub"; url = "https://github.com/dornol/idkit/issues" }
        developers { developer { id = "dornol"; name = "dhkim"; email = "dhkim@dornol.dev"; url = "https://github.com/dornol/" } }
        scm {
            url = "https://github.com/dornol/idkit/"
            connection = "scm:git:git://github.com/dornol/idkit.git"
            developerConnection = "scm:git:ssh://git@github.com/dornol/idkit.git"
        }
    }
}
