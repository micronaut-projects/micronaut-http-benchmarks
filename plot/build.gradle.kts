plugins {
    java
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation("software.xdev:chartjs-java-model:2.8.0")
    implementation(project(":load-generator-oci"))
}
