pluginManagement {
    plugins {
        kotlin("jvm") version "2.3.21"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "idkit"
include(":idkit-redis")
include(":idkit-jdbc")
include(":idkit-spring-boot-autoconfigure")
include(":idkit-spring-boot-starter-jdbc")
include(":idkit-spring-boot-starter-redis")
