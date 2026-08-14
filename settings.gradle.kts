pluginManagement {
    includeBuild("build-logic")
    plugins {
        id("io.micronaut.library") version "5.0.2"
        id("org.graalvm.buildtools.native") version "0.11.3"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "micronaut-benchmark"

include("load-generator-oci")
include("plot")
include("relay-agent")
include("relay-api")
