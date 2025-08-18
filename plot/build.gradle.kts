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
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
