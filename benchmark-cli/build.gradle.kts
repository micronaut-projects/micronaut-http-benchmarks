plugins {
    id("io.micronaut.library")
    application
}
repositories { mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    implementation(project(":benchmark-api"))
    implementation(project(":plot"))
    implementation("io.micronaut.picocli:micronaut-picocli")
    implementation("io.micronaut:micronaut-http-client")
    runtimeOnly("io.micronaut.toml:micronaut-toml")
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
micronaut {
    version(libs.versions.micronaut.asProvider().get())
    processing { incremental(true); annotations("io.micronaut.benchmark.cli.*") }
}
application {
    applicationDefaultJvmArgs =
        listOf("--enable-native-access=ALL-UNNAMED"); mainClass.set("io.micronaut.benchmark.cli.Bench")
}
tasks.withType<Test> { useJUnitPlatform() }
