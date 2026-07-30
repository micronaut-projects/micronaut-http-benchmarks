import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

/**
 * This plugin activates epoll support for the Micronaut application.
 */
plugins {
    id("io.micronaut.testcase")
}

val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

dependencies {
    runtimeOnly(variantOf(versionCatalog.findLibrary("netty-io-uring").get()) { classifier("linux-x86_64") })
}

tasks.withType<BuildNativeImageTask>().configureEach {
    enabled = false
}
