plugins {
    java
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    implementation(libs.chartjs)
    implementation(project(":load-generator-oci"))
    implementation(libs.async.profiler.jfr.converter)
    implementation(libs.openjdk.jmc.flightrecorder.writer)
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-objectstorage")
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
