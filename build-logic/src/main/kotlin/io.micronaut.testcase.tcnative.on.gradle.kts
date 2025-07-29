/**
 * This plugin adds tcnative support for the Micronaut application.
 */
plugins {
    id("io.micronaut.testcase")
}

dependencies {
    runtimeOnly("io.netty:netty-tcnative-boringssl-static::linux-x86_64")
}
