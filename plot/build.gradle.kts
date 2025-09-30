plugins {
    java
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.chartjs)
    implementation(project(":load-generator-oci"))
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-objectstorage")
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
