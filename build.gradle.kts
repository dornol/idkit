import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.10"
    id("java")
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
    id("org.jetbrains.dokka") version "1.9.20"
    id("me.champeau.jmh") version "0.7.3"
}

group = "io.github.dornol"
version = "3.0.0"

repositories {
    mavenCentral()
}

val jakartaValidationApi = "jakarta.validation:jakarta.validation-api:3.0.2"

dependencies {
    api("org.slf4j:slf4j-api:2.0.17")
    // Jakarta Bean Validation is an optional integration point. Users who pull in idkit
    // alongside a validation engine (Spring/Quarkus/Hibernate Validator) get the annotations
    // on the classpath; users who don't incur no extra transitive dependency.
    compileOnly(jakartaValidationApi)
    implementation(kotlin("stdlib-jdk8"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // compileOnly above is not on the test runtime classpath, so re-declare for tests.
    testImplementation(jakartaValidationApi)
    testImplementation("org.hibernate.validator:hibernate-validator:8.0.1.Final")
    testImplementation("org.glassfish.expressly:expressly:5.0.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
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
    publishToMavenCentral()

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