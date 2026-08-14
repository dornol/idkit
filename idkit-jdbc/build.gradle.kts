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
    compileOnly("io.micrometer:micrometer-core:1.14.13")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.micrometer:micrometer-core:1.14.13")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation("com.mysql:mysql-connector-j:9.4.0")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    testImplementation("com.microsoft.sqlserver:mssql-jdbc:13.2.0.jre11")
    testImplementation("com.oracle.database.jdbc:ojdbc11:23.8.0.25.04")
}

kotlin { jvmToolchain(11) }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(11)) } }
tasks.test { useJUnitPlatform() }

signing { sign(publishing.publications) }
mavenPublishing {
    signAllPublications()
    publishToMavenCentral()
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
