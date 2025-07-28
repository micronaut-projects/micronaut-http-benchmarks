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
}
