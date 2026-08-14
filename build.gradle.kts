import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    id("java")
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
    jacoco
    id("org.jetbrains.dokka") version "2.2.0"
    id("me.champeau.jmh") version "0.7.3"
}

group = "io.github.dornol"
version = "3.2.2"

repositories {
    mavenCentral()
}

val jakartaValidationApi = "jakarta.validation:jakarta.validation-api:3.1.1"

dependencies {
    implementation(libs.slf4jApi)
    // Jakarta Bean Validation is an optional integration point. Users who pull in idkit
    // alongside a validation engine (Spring/Quarkus/Hibernate Validator) get the annotations
    // on the classpath; users who don't incur no extra transitive dependency.
    compileOnly(jakartaValidationApi)
    implementation(kotlin("stdlib-jdk8"))
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitLauncher)
    // compileOnly above is not on the test runtime classpath, so re-declare for tests.
    testImplementation(jakartaValidationApi)
    testImplementation(libs.hibernateValidator)
    testImplementation(libs.expressly)
}

tasks.test {
    useJUnitPlatform()
}

// Keep a per-module XML/HTML coverage report available on every verification run. The report is
// intentionally informational for now; thresholds can be introduced once the integration suite
// has a stable baseline across all supported databases.
tasks.named("check") {
    dependsOn("jacocoTestReport")
    dependsOn("jacocoTestCoverageVerification")
}

tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

subprojects {
    apply(plugin = "jacoco")
    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("jacocoTestReport")
        dependsOn("jacocoTestCoverageVerification")
    }
    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.75".toBigDecimal()
                }
            }
        }
    }
}

// Keep the optional Redis integration module covered by the root verification lifecycle.
tasks.named("check") {
    dependsOn(":idkit-redis:check")
    dependsOn(":idkit-jdbc:check")
    dependsOn(":idkit-spring-boot-autoconfigure:check")
    dependsOn(":idkit-spring-boot-starter-jdbc:check")
    dependsOn(":idkit-spring-boot-starter-redis:check")
}

// Release workflow guard: fail before signing/uploading if a module silently drops out of the
// Maven Central publication graph.
tasks.register("verifyPublicationModules") {
    doLast {
        val expected = listOf(
            ":",
            ":idkit-jdbc",
            ":idkit-redis",
            ":idkit-spring-boot-autoconfigure",
            ":idkit-spring-boot-starter-jdbc",
            ":idkit-spring-boot-starter-redis",
        )
        val missingTasks = expected.filter { path ->
            project(path).tasks.findByName("publishMavenPublicationToMavenCentralRepository") == null
        }
        check(missingTasks.isEmpty()) {
            "Expected Maven Central publication task is missing for: ${missingTasks.joinToString() }"
        }
        check(expected.map { project(it).version }.distinct().size == 1) {
            "All published modules must use the same project version"
        }
        logger.lifecycle("Verified Maven Central publications: ${expected.joinToString()}")
    }
}
kotlin {
    jvmToolchain(11)
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// JMH benchmarks live in src/jmh/kotlin. Run with `./gradlew jmh`.
// The jmh source set has no effect on the published jar — benchmark classes are excluded.
//
// CLI overrides (project properties — me.champeau.jmh does not bind these automatically):
//   -Pjmh.includes=<regex>   filter benchmarks by regex (e.g. GeneratorThroughputBenchmark)
//   -Pjmh.warmup=<n>         warmup iterations
//   -Pjmh.iterations=<n>     measurement iterations
//   -Pjmh.fork=<n>           fork count (use 0 for quick smoke runs — not statistically valid)
fun intProp(name: String, default: Int): Int =
    (project.findProperty(name) as String?)?.toInt() ?: default

jmh {
    warmupIterations.set(intProp("jmh.warmup", 3))
    iterations.set(intProp("jmh.iterations", 5))
    fork.set(intProp("jmh.fork", 1))
    timeUnit.set("us")
    resultFormat.set("TEXT")
    (project.findProperty("jmh.includes") as String?)?.let { includes.set(listOf(it)) }
}

// Generate Javadoc-like HTML for Kotlin using Dokka and package it as javadocJar (required by Maven Central)
val dokkaJavadoc by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)
val javadocJar by tasks.registering(Jar::class) {
    dependsOn(dokkaJavadoc)
    from(dokkaJavadoc.outputDirectory)
    archiveClassifier.set("javadoc")
}

signing {
    sign(publishing.publications)
}

mavenPublishing {
    signAllPublications()
    publishToMavenCentral(automaticRelease = true)

    coordinates("io.github.dornol", "idkit", "$version") // 네임 스페이스, 라이브러리 이름, 버전 순서로 작성

    pom {
        name = "idkit"
        description = "Id Generator Kit"
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
