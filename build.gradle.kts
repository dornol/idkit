plugins {
    id("java")
    kotlin("jvm")
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka") version "1.9.20"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "dev.dornol"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib-jdk8"))
}

// Package Kotlin sources
java {
    withSourcesJar()
}

// Generate Javadoc-like HTML for Kotlin using Dokka and package it as javadocJar (required by Maven Central)
val dokkaJavadoc by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)
val javadocJar by tasks.registering(Jar::class) {
    dependsOn(dokkaJavadoc)
    from(dokkaJavadoc.outputDirectory)
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(javadocJar.get())

            pom {
                name.set("idkit")
                description.set("Kotlin/JVM Snowflake ID generator library.")
                url.set("https://github.com/dornol/idkit")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("dornol")
                        name.set("dhkim")
                        email.set("dhkim@dornol.dev")
                    }
                }
                scm {
                    url.set("https://github.com/dornol/idkit")
                    connection.set("scm:git:https://github.com/dornol/idkit.git")
                    developerConnection.set("scm:git:ssh://git@github.com/dornol/idkit.git")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/dornol/idkit/issues")
                }
            }
        }
    }
}

// Signing setup — reads keys from gradle.properties or environment variables
// Supported properties (preferred in ~/.gradle/gradle.properties):
//   signing.keyId, signing.password, signing.key (armored PGP private key)
// Or environment variables: SIGNING_KEY_ID, SIGNING_PASSWORD, SIGNING_KEY
val signingKey: String? = (findProperty("signing.key") as String?) ?: System.getenv("SIGNING_KEY")
val signingKeyId: String? = (findProperty("signing.keyId") as String?) ?: System.getenv("SIGNING_KEY_ID")
val signingPassword: String? = (findProperty("signing.password") as String?) ?: System.getenv("SIGNING_PASSWORD")

signing {
    isRequired = signingKey != null && !project.version.toString().endsWith("-SNAPSHOT")
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications)
    }
}

// Sonatype (OSSRH) Nexus publishing configuration
// Provide credentials via properties: ossrhUsername / ossrhPassword or env vars OSSRH_USERNAME / OSSRH_PASSWORD
nexusPublishing {
    this.repositories {
        sonatype {
            // Using the newer s01 host
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set((findProperty("ossrhUsername") as String?) ?: System.getenv("OSSRH_USERNAME"))
            password.set((findProperty("ossrhPassword") as String?) ?: System.getenv("OSSRH_PASSWORD"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}