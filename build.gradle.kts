import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    kotlin("jvm") version "1.9.25"
    id("com.vanniktech.maven.publish") version "0.28.0"
    id("signing")
    id("org.jetbrains.dokka") version "1.9.20"
}

group = "dev.dornol"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib-jdk8"))
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
    kotlinOptions {
        jvmTarget = "11"
    }
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
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    coordinates("io.github.dornol", "idkit", "$version") // 네임 스페이스, 라이브러리 이름, 버전 순서로 작성

    // POM 설정
    pom {
        /**
        name = '[라이브러리 이름]'
        description = '[라이브러리 설명]'
        url = '[오픈소스 Repository Url]'
         */
        name = "idkit"
        description = "Snow Flake Id Generator"
        url = "<https://github.com/dornol/idkit>"

        // 라이선스 정보
        licenses {
            license {
                name = "MIT"
                url = "<https://github.com/dornol/idkit/blob/main/LICENSE>"
            }
        }

        // 개발자 정보
        developers {
            developer {
                id = "dornol"
                name = "dhkim"
                email = "dhkim@dornol.dev"
            }
        }

        /**
        connection = 'scm:git:github.com/[Github 사용자명]/[오픈소스 Repository 이름].git'
        developerConnection = 'scm:git:ssh://github.com/[Github 사용자명]/[오픈소스 Repository 이름].git'
        url = '<https://github.com/>[Github 사용자명]/[오픈소스 Repository 이름]/tree/[배포 브랜치명]'
         */
        scm {
            connection = "scm:git:github.com/dornol/idkit.git"
            developerConnection = "scm:git:ssh://github.com:dornol/idkit.git"
            url = "<https://github.com/dornol/idkit/tree/main>"
        }
    }
}