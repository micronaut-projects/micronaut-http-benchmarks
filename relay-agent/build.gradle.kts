plugins {
    java
    application
    id("org.graalvm.buildtools.native")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":relay-api"))
    implementation(libs.slf4j.simple)
    implementation(libs.netty.handler)
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

application {
    mainClass.set("io.micronaut.benchmark.relay.agent.Main")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        all {
            buildArgs.add("--initialize-at-build-time=io.netty.util.internal.CleanerJava25")
            buildArgs.add("-Os")
            buildArgs.add("-H:+SharedArenaSupport")
            buildArgs.add("-H:AbortOnTypeReachable=com.sun.org.apache.xerces.internal.impl.xs.traversers.XSDHandler")
            buildArgs.addAll("--emit", "build-report")
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                @Suppress("UnstableApiUsage")
                nativeImageCapable.set(true)
            })
        }
    }
}
