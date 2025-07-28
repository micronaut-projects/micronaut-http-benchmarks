plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

dependencies {
    implementation("io.micronaut.gradle:micronaut-gradle-plugin:4.5.4")
    implementation("gradle.plugin.com.github.johnrengelman:shadow:7.1.2")
}
