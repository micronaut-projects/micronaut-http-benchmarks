plugins { id("io.micronaut.library") }
repositories { mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    api("io.micronaut.serde:micronaut-serde-api")
    api("io.micronaut:micronaut-jackson-databind")
    api(libs.hyperfoil.api)
    api(libs.hyperfoil.core)
    api("io.hyperfoil:hyperfoil-http:${libs.versions.hyperfoil.get()}")
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
micronaut { version(libs.versions.micronaut.asProvider().get()) }
tasks.withType<Test> { useJUnitPlatform() }
