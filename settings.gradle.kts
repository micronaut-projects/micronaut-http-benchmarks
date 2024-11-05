pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
        }
    }
}

plugins {
    id("io.micronaut.bench.variants")
}

rootProject.name = "micronaut-benchmark"

include("load-generator-oci")
include("test-case-pure-netty")
include("test-case-helidon-nima")
include("test-case-spring-boot")
include("test-case-vertx")

configure<io.micronaut.bench.AppVariants> {
    combinations {
        dimension("tcnative") {
            variant("off")
            variant("on")
        }
        dimension("transport") {
            variant("nio")
            variant("epoll")
            variant("iouring")
        }
        dimension("json") {
            variant("jackson")
            variant("serde")
        }
        dimension("micronaut") {
            variant("latest")
        }
        dimension("java") {
            //variant("11")
            variant("17")
        }
    }
}
